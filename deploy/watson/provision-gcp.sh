#!/usr/bin/env bash
# Provision watson's GCP resources: APIs, Casita's service account and no-delete role, the (default) Firestore
# database (delete protection, point-in-time recovery) with its schema marker and deny-all Security Rules
# (firestore.rules), a private network, and the e2-micro VM.
#
#   deploy/watson/provision-gcp.sh deploy/watson/watson.conf            # dry run: reads, checks, prints the plan
#   deploy/watson/provision-gcp.sh deploy/watson/watson.conf --apply    # creates what's missing, after the same checks
#
# Run from your Mac with gcloud signed in as the project owner (gcloud auth login); needs python3 (for JSON).
#
# Dry run: only reads. It lists the enabled APIs, and reads each resource only if its API is already enabled (so
#   it never asks to enable one); with no signed-in account it prints the plan for an empty project. It never
#   enables an API, creates or changes anything, or sends a write request.
# Both modes: what exists is checked against watson.conf by provision_check.py. A security- or cost-relevant
#   mismatch (a VM named watson in another zone, a VM on another network or with another service account, an
#   extra firewall rule or a widened one, a role with other permissions, extra roles on the service account,
#   a service-account key, Firestore in another location or mode or without delete protection or PITR, Security
#   Rules other than firestore.rules, ...) is a
#   PROBLEM: the run stops before changing anything. This script never replaces, updates or deletes a resource
#   that exists, never creates a key, and never touches billing.
# --apply: enables the missing APIs, reads and checks everything again, then creates only what is missing.
#   Run the dry run again afterwards: every line should be OK.
#
# Exit status: 0 = fine, 1 = PROBLEM found (nothing changed), 2 = bad usage or settings.
set -euo pipefail

usage() { echo "usage: provision-gcp.sh <watson.conf> [--apply]" >&2; exit 2; }
if [ $# -lt 1 ] || [ $# -gt 2 ]; then usage; fi
conf="$1"; mode="${2:-dry-run}"
case "$mode" in dry-run|--apply) ;; *) usage ;; esac
[ -f "$conf" ] || { echo "no such settings file: $conf" >&2; exit 2; }
here="$(cd "$(dirname "$0")" && pwd)"

# shellcheck source=/dev/null
. "$conf"
for v in PROJECT_ID REGION ZONE FIRESTORE_LOCATION VM_NAME NETWORK SUBNET SUBNET_RANGE SERVICE_ACCOUNT ROLE_ID; do
  val="${!v:-}"
  [ -n "$val" ] || { echo "$v is not set in $conf" >&2; exit 2; }
  case "$val" in *"<"*|*">"*) echo "$v is still a placeholder ($val) in $conf" >&2; exit 2 ;; esac
  case "$val" in *[!A-Za-z0-9./_-]*) echo "$v has unexpected characters ($val) in $conf" >&2; exit 2 ;; esac
done
case "$ZONE" in "$REGION"-?) ;; *) echo "ZONE $ZONE isn't in REGION $REGION ($conf)" >&2; exit 2 ;; esac
command -v gcloud >/dev/null || { echo "gcloud isn't installed" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 is needed (for reading gcloud's JSON)" >&2; exit 2; }

SA_EMAIL="$SERVICE_ACCOUNT@$PROJECT_ID.iam.gserviceaccount.com"
ROLE_NAME="projects/$PROJECT_ID/roles/$ROLE_ID"
PERMS=datastore.entities.get,datastore.entities.list,datastore.entities.create,datastore.entities.update,datastore.databases.getMetadata
APIS=compute.googleapis.com,firestore.googleapis.com,iap.googleapis.com,iam.googleapis.com,cloudresourcemanager.googleapis.com,firebaserules.googleapis.com
IAP_RANGE=35.235.240.0/20
FW_RULE="$NETWORK-allow-iap-ssh"
DOC_URL="https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents/meta/schema"
# Firestore Security Rules for the (default) database: the release named cloud.firestore points at a ruleset.
RULES_API="https://firebaserules.googleapis.com/v1/projects/$PROJECT_ID"
RULES_FILE="$here/firestore.rules"
[ -f "$RULES_FILE" ] || { echo "$RULES_FILE is missing" >&2; exit 2; }
# Never answer gcloud's "enable this API and retry?" prompt, in either mode.
export CLOUDSDK_CORE_DISABLE_PROMPTS=1
G=(gcloud --project="$PROJECT_ID" --quiet)

inv="$(mktemp -d)"; trap 'rm -rf "$inv"' EXIT

# fetch NAME CMD...: a read. Its JSON goes to NAME.json; "not found" to NAME.absent; any other failure to NAME.error.
fetch() {
  local name="$1"; shift
  if "$@" > "$inv/$name.json" 2> "$inv/$name.err"; then rm -f "$inv/$name.err"; return 0; fi
  rm -f "$inv/$name.json"
  if grep -qiE 'NOT_FOUND|was not found|not found|does not exist' "$inv/$name.err"; then
    mv "$inv/$name.err" "$inv/$name.absent"
  else
    mv "$inv/$name.err" "$inv/$name.error"
  fi
}

# token_header: the Authorization header for curl, on stdin (never on a command line, never printed).
token_header() { printf 'Authorization: Bearer %s\n' "$(gcloud auth print-access-token)"; }

# get NAME URL: a REST GET as you (token on stdin). 200 -> NAME.json, 404 -> NAME.absent, anything else -> NAME.error.
get() {
  local name="$1" url="$2" code
  code="$(token_header | curl -sS -o "$inv/$name.body" -w '%{http_code}' -H @- -H "x-goog-user-project: $PROJECT_ID" "$url" 2>/dev/null || echo 000)"
  case "$code" in
    200) mv "$inv/$name.body" "$inv/$name.json" ;;
    404) mv "$inv/$name.body" "$inv/$name.absent" ;;
    *) { echo "HTTP $code"; head -c 300 "$inv/$name.body" 2>/dev/null; } > "$inv/$name.error"; rm -f "$inv/$name.body" ;;
  esac
}

# inventory: read everything that can be read without enabling an API. Read-only.
inventory() {
  rm -rf "$inv"; mkdir -p "$inv"
  account="$(gcloud auth list --filter=status:ACTIVE --format='value(account)' 2>/dev/null | head -1 || true)"
  if [ -z "$account" ]; then signed_in=no; return 0; fi
  signed_in=yes
  if ! "${G[@]}" services list --enabled --format='value(config.name)' > "$inv/services.txt" 2> "$inv/services.err"; then
    rm -f "$inv/services.txt"; return 0
  fi
  has() { grep -qx "$1" "$inv/services.txt"; }
  if has iam.googleapis.com; then
    fetch sa "${G[@]}" iam service-accounts describe "$SA_EMAIL" --format=json
    if [ -f "$inv/sa.json" ]; then
      fetch sa-keys "${G[@]}" iam service-accounts keys list --iam-account="$SA_EMAIL" --managed-by=user --format=json
      fetch sa-policy "${G[@]}" iam service-accounts get-iam-policy "$SA_EMAIL" --format=json
    fi
    fetch role "${G[@]}" iam roles describe "$ROLE_ID" --format=json
  fi
  if has cloudresourcemanager.googleapis.com; then
    fetch project-policy "${G[@]}" projects get-iam-policy "$PROJECT_ID" --format=json
  fi
  if has firestore.googleapis.com; then
    fetch firestore "${G[@]}" firestore databases describe --database='(default)' --format=json
    if [ -f "$inv/firestore.json" ]; then
      # A GET of meta/schema: read-only. 200 = present, 404 = absent, anything else = error.
      code="$(token_header | curl -sS -o "$inv/schema.body" -w '%{http_code}' -H @- -H "x-goog-user-project: $PROJECT_ID" "$DOC_URL" 2>/dev/null || echo 000)"
      case "$code" in
        200) mv "$inv/schema.body" "$inv/schema.json" ;;
        404) mv "$inv/schema.body" "$inv/schema.absent" ;;
        *) { echo "HTTP $code"; head -c 300 "$inv/schema.body" 2>/dev/null; } > "$inv/schema.error"; rm -f "$inv/schema.body" ;;
      esac
    fi
  fi
  if has firebaserules.googleapis.com; then
    # The released Firestore rules, and the ruleset they point at: two GETs.
    get rules-release "$RULES_API/releases/cloud.firestore"
    if [ -f "$inv/rules-release.json" ]; then
      ruleset="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1])).get("rulesetName",""))' "$inv/rules-release.json" 2>/dev/null || true)"
      case "$ruleset" in
        "projects/$PROJECT_ID/rulesets/"*) get rules-ruleset "https://firebaserules.googleapis.com/v1/$ruleset" ;;
        *) echo "the cloud.firestore release names no ruleset of this project" > "$inv/rules-ruleset.error" ;;
      esac
    fi
  fi
  if has compute.googleapis.com; then
    # Every VM of that name, in every zone: a second watson must never be created.
    fetch instances "${G[@]}" compute instances list --format=json   # every zone; matched by exact name in the check
    disk="$(python3 -I -c 'import json,sys
v=[i for i in json.load(open(sys.argv[1])) if i.get("name")==sys.argv[2]]
b=[d for i in v for d in i.get("disks",[]) if d.get("boot")]
print(b[0]["source"].rstrip("/").split("/")[-1]+" "+v[0]["zone"].rstrip("/").split("/")[-1] if len(v)==1 and b else "")' \
      "$inv/instances.json" "$VM_NAME" 2>/dev/null || true)"
    if [ -n "$disk" ]; then
      read -r disk_name disk_zone <<<"$disk"
      fetch boot-disk "${G[@]}" compute disks describe "$disk_name" --zone="$disk_zone" --format=json
    fi
    fetch network "${G[@]}" compute networks describe "$NETWORK" --format=json
    fetch subnet "${G[@]}" compute networks subnets describe "$SUBNET" --region="$REGION" --format=json
    fetch firewalls "${G[@]}" compute firewall-rules list --format=json
  fi
}

# check: provision_check.py over the inventory. Prints its findings; returns 1 on any PROBLEM.
check() {
  WATSON_PROJECT_ID="$PROJECT_ID" WATSON_REGION="$REGION" WATSON_ZONE="$ZONE" WATSON_FIRESTORE_LOCATION="$FIRESTORE_LOCATION" \
  WATSON_VM_NAME="$VM_NAME" WATSON_NETWORK="$NETWORK" WATSON_SUBNET="$SUBNET" WATSON_SUBNET_RANGE="$SUBNET_RANGE" \
  WATSON_SA_EMAIL="$SA_EMAIL" WATSON_ROLE_ID="$ROLE_ID" WATSON_PERMS="$PERMS" WATSON_APIS="$APIS" WATSON_HTTPS_TAG="${HTTPS_TAG:-casita-https}" \
  WATSON_RULES_FILE="$RULES_FILE" \
    python3 -I "$here/provision_check.py" "$inv"
}

# is RESOURCE STATE: what check found for a resource (present, absent or unknown).
is() { grep -qx "$1=$2" "$inv/state"; }

# run CMD...: print it; with --apply, run it.
run() {
  printf '  $'; printf ' %q' "$@"; echo
  if [ "$mode" = "--apply" ]; then "$@"; fi
}

echo "Project $PROJECT_ID, region $REGION, zone $ZONE, VM $VM_NAME ($( [ "$mode" = --apply ] && echo APPLY || echo 'DRY RUN: read-only' ))"
echo
echo "== What exists, checked against $conf"
inventory
if [ "$signed_in" = no ]; then
  echo "NOTE     No gcloud account is signed in: nothing was read. The plan below is for an empty project."
  [ "$mode" = "--apply" ] && { echo "--apply needs a signed-in account (gcloud auth login)." >&2; exit 2; }
  : > "$inv/services.txt"
fi
status=0; check || status=$?
if [ $status -ne 0 ]; then
  echo
  echo "STOPPED: fix every PROBLEM above deliberately (this script never changes an existing resource). Nothing was changed."
  exit 1
fi

if [ "$mode" = "--apply" ]; then
  missing="$(sed -n 's/^apis_missing=//p' "$inv/state")"
  if [ -n "$missing" ]; then
    echo; echo "== Enabling APIs: ${missing//,/ }"
    # shellcheck disable=SC2086
    run "${G[@]}" services enable ${missing//,/ }
    echo; echo "== Checking again, now that every API can be read"
    inventory
    status=0; check || status=$?
    [ $status -eq 0 ] || { echo; echo "STOPPED: fix every PROBLEM above deliberately. Only APIs were enabled."; exit 1; }
  fi
fi

echo
echo "== Plan: create what is missing (nothing that exists is changed)"
any=no
if is sa absent; then any=yes
  run "${G[@]}" iam service-accounts create "$SERVICE_ACCOUNT" --display-name="Casita runtime (watson)"
fi
if is role absent; then any=yes
  run "${G[@]}" iam roles create "$ROLE_ID" --title="Casita Firestore runtime (no delete)" \
    --description="Casita's security store: get, list, create, update. No delete." --permissions="$PERMS" --stage=GA
fi
if is binding absent; then any=yes
  run "${G[@]}" projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:$SA_EMAIL" --role="$ROLE_NAME" --condition=None
fi
if is firestore absent; then any=yes
  # Point-in-time recovery: 7 days of versions, billed by database size (no free tier; docs/FIRESTORE.md).
  run "${G[@]}" firestore databases create --database='(default)' --location="$FIRESTORE_LOCATION" --type=firestore-native \
    --delete-protection --enable-pitr
  # TTL policies (cleanup only; decided by nothing) and index exemptions for the byte fields.
  run "${G[@]}" firestore fields ttls update ttl --collection-group=invitations --enable-ttl --async
  run "${G[@]}" firestore fields ttls update expiresAt --collection-group=securityEvents --enable-ttl --async
  for f in publicKeyCose attestationObject attestationClientDataJson; do
    run "${G[@]}" firestore indexes fields update "$f" --collection-group=passkeys --disable-indexes --async
  done
fi
if is schema absent; then any=yes
  # Create-only (currentDocument.exists=false): if meta/schema exists, Firestore refuses, and nothing is overwritten.
  echo "  \$ curl -X PATCH '$DOC_URL?currentDocument.exists=false' -d '{\"fields\":{\"schemaVersion\":{\"integerValue\":\"1\"}}}'   (as you; token on stdin)"
  if [ "$mode" = "--apply" ]; then
    token_header | curl --fail-with-body -sS -X PATCH "$DOC_URL?currentDocument.exists=false" -H @- \
      -H "x-goog-user-project: $PROJECT_ID" -H 'Content-Type: application/json' \
      -d '{"fields":{"schemaVersion":{"integerValue":"1"}}}' > /dev/null
    echo "  meta/schema created"
  fi
fi
if is rules absent; then any=yes
  # Create-only: a new ruleset from firestore.rules, then the cloud.firestore release pointing at it. Only when the
  # project has no Firestore rules release at all; released rules that differ stop the run instead (check).
  echo "  \$ curl -X POST '$RULES_API/rulesets' (firestore.rules), then -X POST '$RULES_API/releases' {name: cloud.firestore}   (as you; token on stdin)"
  if [ "$mode" = "--apply" ]; then
    python3 -I -c 'import json,sys; print(json.dumps({"source":{"files":[{"name":"firestore.rules","content":open(sys.argv[1]).read()}]}}))' \
      "$RULES_FILE" > "$inv/ruleset-request.json"
    token_header | curl --fail-with-body -sS -X POST "$RULES_API/rulesets" -H @- -H "x-goog-user-project: $PROJECT_ID" \
      -H 'Content-Type: application/json' -d @"$inv/ruleset-request.json" > "$inv/ruleset-created.json"
    created="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["name"])' "$inv/ruleset-created.json")"
    case "$created" in "projects/$PROJECT_ID/rulesets/"*) ;; *) echo "unexpected ruleset name: $created" >&2; exit 1 ;; esac
    token_header | curl --fail-with-body -sS -X POST "$RULES_API/releases" -H @- -H "x-goog-user-project: $PROJECT_ID" \
      -H 'Content-Type: application/json' \
      -d "{\"name\":\"projects/$PROJECT_ID/releases/cloud.firestore\",\"rulesetName\":\"$created\"}" > /dev/null
    echo "  Firestore rules released ($created)"
  fi
fi
if is network absent; then any=yes
  run "${G[@]}" compute networks create "$NETWORK" --subnet-mode=custom
fi
if is subnet absent; then any=yes
  run "${G[@]}" compute networks subnets create "$SUBNET" --network="$NETWORK" --region="$REGION" --range="$SUBNET_RANGE" \
    --enable-private-ip-google-access
fi
if is firewall absent; then any=yes
  # The only inbound rule on the network. Everything else inbound is refused by the implied deny rule.
  run "${G[@]}" compute firewall-rules create "$FW_RULE" --network="$NETWORK" --direction=INGRESS \
    --action=allow --rules=tcp:22 --source-ranges="$IAP_RANGE" --description="SSH only through Identity-Aware Proxy"
fi
if is vm absent; then any=yes
  # Only on watson-net. The external IPv4 address is for outbound traffic (packages, Firestore) and, once
  # provision-public.sh has reserved it and opened tcp:80,443 to the HTTPS tag, for public HTTPS through Caddy
  # (docs/PUBLIC_HTTPS.md). Nothing else is admitted except IAP's SSH range.
  run "${G[@]}" compute instances create "$VM_NAME" --zone="$ZONE" --machine-type=e2-micro \
    --image-family=debian-12 --image-project=debian-cloud --boot-disk-size=30GB --boot-disk-type=pd-standard \
    --network="$NETWORK" --subnet="$SUBNET" \
    --service-account="$SA_EMAIL" --scopes=cloud-platform \
    --shielded-secure-boot --shielded-vtpm --shielded-integrity-monitoring \
    --metadata=enable-oslogin=TRUE,block-project-ssh-keys=TRUE \
    --labels=app=casita,env=staging
fi
[ $any = yes ] || echo "  (nothing to create)"

echo
if [ "$mode" = "--apply" ]; then
  echo "Done. Run the dry run again: every line should be OK. Then check-g3.sh on the VM, then bootstrap-vm.sh."
else
  echo "DRY RUN: nothing was changed. Add --apply to enable the missing APIs and create what's listed."
fi
