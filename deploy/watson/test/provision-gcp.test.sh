#!/usr/bin/env bash
# Tests for deploy/watson/provision-gcp.sh against a fake gcloud and curl: nothing here reaches Google.
#
#   bash deploy/watson/test/provision-gcp.test.sh
#
# The fakes answer reads from fixture files (one per gcloud command, like the real JSON) and log every call. A call
# that would change anything (create, enable, update, delete, IAM binding, a non-GET request) is logged as MUTATE.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../provision-gcp.sh"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
TOKEN="ya29.fake-token-must-never-be-printed"
P=bentals-casita
SA="casita-runtime@$P.iam.gserviceaccount.com"
C="https://www.googleapis.com/compute/v1/projects/$P"

# ---------- the fakes ----------
mkdir -p "$work/bin"
cat > "$work/bin/gcloud" <<'FAKE'
#!/usr/bin/env bash
pos=""; for a in "$@"; do case "$a" in -*) ;; *) pos="$pos${pos:+_}$a" ;; esac; done
for w in $(echo "$pos" | tr '_' ' '); do
  case "$w" in create|enable|disable|add-iam-policy-binding|remove-iam-policy-binding|set-iam-policy|update|delete|undelete|patch)
    echo "MUTATE gcloud $*" >> "$FAKE_LOG"; exit 0 ;;
  esac
done
echo "READ gcloud $*" >> "$FAKE_LOG"
case "$pos" in
  auth_list) cat "$FAKE_STATE/account" 2>/dev/null; exit 0 ;;
  auth_print-access-token) echo "$FAKE_TOKEN"; exit 0 ;;
esac
for ext in json txt; do [ -f "$FAKE_STATE/$pos.$ext" ] && { cat "$FAKE_STATE/$pos.$ext"; exit 0; }; done
[ -f "$FAKE_STATE/$pos.fail" ] && { cat "$FAKE_STATE/$pos.fail" >&2; exit 1; }
echo "ERROR: (gcloud) NOT_FOUND: The resource ($pos) was not found" >&2; exit 1
FAKE
cat > "$work/bin/curl" <<'FAKE'
#!/usr/bin/env bash
method=GET; out=/dev/stdout; w=""; url=""; stdin_hdr=no
while [ $# -gt 0 ]; do
  case "$1" in
    -X) method="$2"; shift 2 ;; -o) out="$2"; shift 2 ;; -w) w="$2"; shift 2 ;; -d) shift 2 ;;
    -H) [ "$2" = "@-" ] && stdin_hdr=yes; case "$2" in *Bearer*) echo "TOKEN-ON-COMMAND-LINE" >> "$FAKE_LOG" ;; esac; shift 2 ;;
    -*) shift ;; *) url="$1"; shift ;;
  esac
done
if [ $stdin_hdr = yes ]; then grep -q "Bearer $FAKE_TOKEN" || echo "BAD-AUTH-HEADER" >> "$FAKE_LOG"; fi
# answer FILE: 200 with the fixture, or 404 if there is none.
answer() { if [ -f "$FAKE_STATE/$1" ]; then cat "$FAKE_STATE/$1" > "$out"; code=200; else printf '{"error":{"code":404,"status":"NOT_FOUND"}}' > "$out"; code=404; fi; }
if [ "$method" != GET ]; then
  echo "MUTATE curl $method $url" >> "$FAKE_LOG"; code=200
  case "$url" in */rulesets) printf '{"name":"projects/bentals-casita/rulesets/new-1"}' > "$out" ;; *) printf '{}' > "$out" ;; esac
else
  echo "READ curl GET $url" >> "$FAKE_LOG"
  case "$url" in
    */releases/cloud.firestore) if [ -f "$FAKE_STATE/rules_release.fail" ]; then printf 'denied' > "$out"; code=403; else answer rules_release.json; fi ;;
    */rulesets/*) answer rules_ruleset.json ;;
    *) answer schema.doc ;;
  esac
fi
[ -n "$w" ] && printf '%s' "$code"
exit 0
FAKE
chmod +x "$work/bin/gcloud" "$work/bin/curl"

cat > "$work/watson.conf" <<CONF
PROJECT_ID="$P"
REGION=us-central1
ZONE=us-central1-a
FIRESTORE_LOCATION=us-central1
VM_NAME=watson
NETWORK=watson-net
SUBNET=watson-subnet
SUBNET_RANGE=10.80.0.0/28
SERVICE_ACCOUNT=casita-runtime
ROLE_ID=casitaFirestoreRuntime
CONF

# ---------- fixtures ----------
# fresh: the project as the read-only inventory found it (no Firestore API, default VPC only, no custom roles).
fresh() {
  st="$work/state"; rm -rf "$st"; mkdir -p "$st"
  echo "owner@example.com" > "$st/account"
  printf '%s\n' compute.googleapis.com iam.googleapis.com cloudresourcemanager.googleapis.com serviceusage.googleapis.com \
    oslogin.googleapis.com > "$st/services_list.txt"
  echo '[]' > "$st/compute_instances_list.json"
  cat > "$st/compute_firewall-rules_list.json" <<J
[{"name":"default-allow-ssh","network":"$C/global/networks/default","direction":"INGRESS","sourceRanges":["0.0.0.0/0"],"allowed":[{"IPProtocol":"tcp","ports":["22"]}]},
 {"name":"default-allow-rdp","network":"$C/global/networks/default","direction":"INGRESS","sourceRanges":["0.0.0.0/0"],"allowed":[{"IPProtocol":"tcp","ports":["3389"]}]},
 {"name":"default-allow-icmp","network":"$C/global/networks/default","direction":"INGRESS","sourceRanges":["0.0.0.0/0"],"allowed":[{"IPProtocol":"icmp"}]}]
J
  cat > "$st/projects_get-iam-policy_$P.json" <<J
{"bindings":[{"role":"roles/owner","members":["user:owner@example.com"]},
 {"role":"roles/editor","members":["serviceAccount:123-compute@developer.gserviceaccount.com"]}],"etag":"BwX"}
J
}
# compliant: everything watson needs, already there and as intended.
compliant() {
  fresh
  printf '%s\n' firestore.googleapis.com iap.googleapis.com firebaserules.googleapis.com >> "$st/services_list.txt"
  cat > "$st/compute_instances_list.json" <<J
[{"name":"watson","zone":"$C/zones/us-central1-a","machineType":"$C/zones/us-central1-a/machineTypes/e2-micro","status":"RUNNING",
  "networkInterfaces":[{"network":"$C/global/networks/watson-net","subnetwork":"$C/regions/us-central1/subnetworks/watson-subnet"}],
  "serviceAccounts":[{"email":"$SA","scopes":["https://www.googleapis.com/auth/cloud-platform"]}],
  "metadata":{"items":[{"key":"enable-oslogin","value":"TRUE"},{"key":"block-project-ssh-keys","value":"TRUE"}]},
  "disks":[{"boot":true,"source":"$C/zones/us-central1-a/disks/watson"}],"shieldedInstanceConfig":{"enableSecureBoot":true}}]
J
  echo "{\"name\":\"watson\",\"type\":\"$C/zones/us-central1-a/diskTypes/pd-standard\",\"sizeGb\":\"30\"}" > "$st/compute_disks_describe_watson.json"
  echo '{"name":"watson-net","autoCreateSubnetworks":false}' > "$st/compute_networks_describe_watson-net.json"
  echo "{\"name\":\"watson-subnet\",\"network\":\"$C/global/networks/watson-net\",\"ipCidrRange\":\"10.80.0.0/28\",\"region\":\"$C/regions/us-central1\",\"privateIpGoogleAccess\":true}" \
    > "$st/compute_networks_subnets_describe_watson-subnet.json"
  python3 -I - "$st/compute_firewall-rules_list.json" "$C" <<'PY'
import json, sys
p, c = sys.argv[1], sys.argv[2]
r = json.load(open(p))
r.append({"name": "watson-net-allow-iap-ssh", "network": c + "/global/networks/watson-net", "direction": "INGRESS",
          "sourceRanges": ["35.235.240.0/20"], "allowed": [{"IPProtocol": "tcp", "ports": ["22"]}]})
json.dump(r, open(p, "w"))
PY
  echo "{\"email\":\"$SA\",\"disabled\":false}" > "$st/iam_service-accounts_describe_$SA.json"
  echo '[]' > "$st/iam_service-accounts_keys_list.json"
  echo '{"etag":"ACAB"}' > "$st/iam_service-accounts_get-iam-policy_$SA.json"
  echo "{\"name\":\"projects/$P/roles/casitaFirestoreRuntime\",\"stage\":\"GA\",\"includedPermissions\":[\"datastore.databases.getMetadata\",\"datastore.entities.create\",\"datastore.entities.get\",\"datastore.entities.list\",\"datastore.entities.update\"]}" \
    > "$st/iam_roles_describe_casitaFirestoreRuntime.json"
  edit "projects_get-iam-policy_$P.json" "d['bindings'].append({'role':'projects/$P/roles/casitaFirestoreRuntime','members':['serviceAccount:$SA']})"
  echo "{\"name\":\"projects/$P/databases/(default)\",\"locationId\":\"us-central1\",\"type\":\"FIRESTORE_NATIVE\",\"deleteProtectionState\":\"DELETE_PROTECTION_ENABLED\",\"pointInTimeRecoveryEnablement\":\"POINT_IN_TIME_RECOVERY_ENABLED\"}" \
    > "$st/firestore_databases_describe.json"
  echo '{"fields":{"schemaVersion":{"integerValue":"1"}}}' > "$st/schema.doc"
  echo "{\"name\":\"projects/$P/releases/cloud.firestore\",\"rulesetName\":\"projects/$P/rulesets/abc-1\"}" > "$st/rules_release.json"
  rules_as "$(cat "$here/../firestore.rules")"
}
# rules_as TEXT: the released ruleset's content.
rules_as() { python3 -I -c 'import json,sys; json.dump({"name":"projects/x/rulesets/abc-1","source":{"files":[{"name":"firestore.rules","content":sys.argv[1]}]}}, open(sys.argv[2],"w"))' "$1" "$st/rules_ruleset.json"; }
# edit FILE PYTHON: change one fixture's JSON (bound to d).
edit() { python3 -I -c "import json,sys; p=sys.argv[1]; d=json.load(open(p)); $2; json.dump(d, open(p,'w'))" "$st/$1"; }

# ---------- running and asserting ----------
fails=0; passes=0; name=""
run_script() {  # run_script ARGS...: sets $out, $code; $log holds the fake calls
  log="$work/calls.log"; : > "$log"
  out="$(PATH="$work/bin:$PATH" FAKE_STATE="$st" FAKE_LOG="$log" FAKE_TOKEN="$TOKEN" bash "$script" "$@" 2>&1)"; code=$?
  if grep -qF "$TOKEN" <<<"$out"; then bad "the access token was printed"; fi
  if grep -qE "TOKEN-ON-COMMAND-LINE|BAD-AUTH-HEADER" "$log"; then bad "the token went on a command line, or not on stdin"; fi
}
bad() { echo "  FAIL [$name]: $*"; fails=$((fails + 1)); FAILED=yes; }
expect_code() { [ "$code" = "$1" ] || bad "exit $code, expected $1"; }
expect_out() { grep -qF -- "$1" <<<"$out" || bad "output lacks: $1"; }
expect_no_out() { ! grep -qF -- "$1" <<<"$out" || bad "output has: $1"; }
mutations() { grep '^MUTATE' "$log" || true; }
expect_no_mutation() { [ -z "$(mutations)" ] || bad "it changed something: $(mutations | head -3 | tr '\n' ';')"; }
expect_mutations() {  # the exact sequence of changes, as "tool words..." prefixes
  local got want
  got="$(mutations | sed -E 's/^MUTATE //; s/ --[^ ]+//g' | awk '{ if ($1=="curl") print $1" "$2; else { s=$1; for (i=2;i<=NF;i++) { if ($i ~ /^(create|enable|update|add-iam-policy-binding)$/) { s=s" "$i; break } s=s" "$i } print s } }')"
  want="$(printf '%s\n' "$@")"
  [ "$got" = "$want" ] || { bad "changes differ"; diff <(echo "$want") <(echo "$got") | sed 's/^/      /'; }
}
scenario() { name="$1"; FAILED=no; }
done_scenario() { [ "$FAILED" = no ] && { passes=$((passes + 1)); echo "  ok   $name"; }; }

echo "provision-gcp.sh, against fakes"

scenario "fresh project, dry run: plan only, no changes, no write requests"
fresh; run_script "$work/watson.conf"
expect_code 0; expect_no_mutation
expect_out "DRY RUN: read-only"; expect_out "CREATE   API firestore.googleapis.com"; expect_out "CREATE   VM watson in us-central1-a"
expect_out "NOTE     Network default: default-allow-ssh admits tcp:22 from anywhere (watson isn't on default)."
expect_out "gcloud --project=bentals-casita --quiet compute instances create watson --zone=us-central1-a --machine-type=e2-micro"
expect_out "--network=watson-net --subnet=watson-subnet --service-account=$SA"
expect_out "--source-ranges=35.235.240.0/20"
expect_no_out "services enable compute"   # compute is enabled already: only the missing APIs would be enabled
grep -q "firestore databases describe" "$log" && bad "read Firestore although its API is off"
done_scenario

scenario "only Service Usage enabled: the dry run reads no other API and plans everything"
fresh; echo serviceusage.googleapis.com > "$st/services_list.txt"; run_script "$work/watson.conf"
expect_code 0; expect_no_mutation; expect_out "Compute Engine API not enabled"; expect_out "IAM API not enabled"
[ "$(grep '^READ' "$log" | grep -vcE 'auth list|services list')" = 0 ] || bad "read through a disabled API: $(grep '^READ' "$log" | grep -vE 'auth list|services list' | head -2)"
done_scenario

scenario "the API list can't be read: stops, changes nothing"
fresh; rm "$st/services_list.txt"; echo "ERROR: PERMISSION_DENIED: serviceusage.services.list" > "$st/services_list.fail"
run_script "$work/watson.conf"; expect_code 1; expect_no_mutation; expect_out "Couldn't list the project's enabled APIs"
run_script "$work/watson.conf" --apply; expect_code 1; expect_no_mutation
done_scenario

scenario "fresh project, --apply: creates everything once, in order, and nothing else"
fresh; run_script "$work/watson.conf" --apply
expect_code 0
expect_mutations \
  "gcloud services enable" \
  "gcloud iam service-accounts create" "gcloud iam roles create" "gcloud projects add-iam-policy-binding" \
  "gcloud firestore databases create" "gcloud firestore fields ttls update" "gcloud firestore fields ttls update" \
  "gcloud firestore indexes fields update" "gcloud firestore indexes fields update" "gcloud firestore indexes fields update" \
  "curl PATCH" "curl POST" "curl POST" \
  "gcloud compute networks create" "gcloud compute networks subnets create" "gcloud compute firewall-rules create" \
  "gcloud compute instances create"
grep -q '^MUTATE gcloud .*services enable firestore.googleapis.com iap.googleapis.com firebaserules.googleapis.com$' "$log" || bad "enabled more than the missing APIs"
grep -q '^MUTATE gcloud .*firestore databases create .*--delete-protection --enable-pitr' "$log" || bad "database created without delete protection and PITR"
[ "$(grep '^MUTATE curl POST' "$log" | sed 's/.*projects\/bentals-casita\///')" = "$(printf 'rulesets\nreleases')" ] || bad "rules: not a ruleset, then its release"
grep -q '^MUTATE curl PATCH .*meta/schema?currentDocument.exists=false$' "$log" || bad "schema write isn't create-only"
# Subcommands only (flags such as --delete-protection removed): nothing deleted, no IAM policy replaced, no key made.
mutations | sed -E 's/ --[^ ]+//g' | grep -qE ' (delete|undelete|remove-iam-policy-binding|set-iam-policy)( |$)| keys create' &&
  bad "deleted, replaced IAM or made a key"
done_scenario

scenario "not signed in: dry run plans an empty project; --apply refuses"
fresh; : > "$st/account"; run_script "$work/watson.conf"
expect_code 0; expect_no_mutation; expect_out "No gcloud account is signed in"; expect_out "CREATE   VM watson"
[ "$(grep -vc '^READ gcloud auth list' "$log")" = 0 ] || bad "read more than the account list"
run_script "$work/watson.conf" --apply
expect_code 2; expect_no_mutation
done_scenario

scenario "a VM named watson in another zone: refused in both modes, nothing changed"
fresh
echo "[{\"name\":\"watson\",\"zone\":\"$C/zones/us-east1-b\",\"machineType\":\"$C/zones/us-east1-b/machineTypes/e2-micro\",\"disks\":[]}]" \
  > "$st/compute_instances_list.json"
run_script "$work/watson.conf"
expect_code 1; expect_no_mutation; expect_out "A VM named watson already exists in us-east1-b"; expect_no_out "instances create"
run_script "$work/watson.conf" --apply
expect_code 1; expect_no_mutation; expect_out "STOPPED"
done_scenario

scenario "VMs named watson in two zones: refused"
compliant
edit compute_instances_list.json "d.append(dict(d[0], zone='$C/zones/us-west1-b'))"
run_script "$work/watson.conf" --apply
expect_code 1; expect_no_mutation; expect_out "already exists in us-central1-a, us-west1-b"
done_scenario

scenario "everything already there and as intended: reused, nothing created or changed"
compliant; run_script "$work/watson.conf"
expect_code 0; expect_no_mutation; expect_out "(nothing to create)"; expect_no_out "PROBLEM"; expect_no_out "CREATE "
expect_out "OK       VM watson in us-central1-a"; expect_out "OK       Custom role projects/$P/roles/casitaFirestoreRuntime: exactly"
expect_out "OK       Firestore (default): Native mode, us-central1, delete protection on, point-in-time recovery on"
expect_out "OK       Firestore Security Rules: deny all, as firestore.rules"
expect_out "OK       Firewall watson-net-allow-iap-ssh: tcp:22 from 35.235.240.0/20 only"
run_script "$work/watson.conf" --apply
expect_code 0; expect_no_mutation
done_scenario

scenario "network, subnet and firewall exist: only the rest is created"
compliant
rm "$st/iam_service-accounts_describe_$SA.json" "$st/iam_roles_describe_casitaFirestoreRuntime.json" "$st/firestore_databases_describe.json" "$st/schema.doc"
echo '[]' > "$st/compute_instances_list.json"
edit "projects_get-iam-policy_$P.json" "d['bindings']=[b for b in d['bindings'] if 'casitaFirestoreRuntime' not in b['role']]"
run_script "$work/watson.conf" --apply
expect_code 0
expect_mutations "gcloud iam service-accounts create" "gcloud iam roles create" "gcloud projects add-iam-policy-binding" \
  "gcloud firestore databases create" "gcloud firestore fields ttls update" "gcloud firestore fields ttls update" \
  "gcloud firestore indexes fields update" "gcloud firestore indexes fields update" "gcloud firestore indexes fields update" \
  "curl PATCH" "gcloud compute instances create"
done_scenario

# drift DESCRIPTION EXPECTED-TEXT FIXTURE-CHANGE...: on a compliant project, the change must stop --apply before any change.
drift() {
  local what="$1" text="$2"; shift 2
  scenario "drift: $what"
  compliant
  "$@"
  run_script "$work/watson.conf" --apply
  expect_code 1; expect_no_mutation; expect_out "PROBLEM"; expect_out "$text"; expect_out "STOPPED"
  done_scenario
}
vm() { edit compute_instances_list.json "v=d[0]; $1"; }
drift "VM on the default network" "network interfaces" vm "v['networkInterfaces'][0]['network']='$C/global/networks/default'"
drift "VM with a second network interface" "network interfaces" vm "v['networkInterfaces'].append(v['networkInterfaces'][0])"
drift "VM as the default compute service account" "service account ['123-compute@developer.gserviceaccount.com']" \
  vm "v['serviceAccounts']=[{'email':'123-compute@developer.gserviceaccount.com'}]"
drift "VM without OS Login" "metadata enable-oslogin is unset" vm "v['metadata']['items']=v['metadata']['items'][1:]"
drift "VM allowing project SSH keys" "metadata block-project-ssh-keys is FALSE" vm "v['metadata']['items'][1]['value']='FALSE'"
drift "VM machine type" "machine type e2-small" vm "v['machineType']='$C/zones/us-central1-a/machineTypes/e2-small'"
drift "boot disk balanced" "boot disk type pd-balanced" edit compute_disks_describe_watson.json "d['type']='x/pd-balanced'"
drift "boot disk over 30 GB" "boot disk 50 GB" edit compute_disks_describe_watson.json "d['sizeGb']='50'"
drift "IAP rule opened to the internet" "isn't tcp:22 from 35.235.240.0/20 only" \
  edit compute_firewall-rules_list.json "[r.update(sourceRanges=['0.0.0.0/0']) for r in d if r['name']=='watson-net-allow-iap-ssh']"
drift "IAP rule widened to more ports" "isn't tcp:22 from 35.235.240.0/20 only" \
  edit compute_firewall-rules_list.json "[r.update(allowed=[{'IPProtocol':'tcp','ports':['22','8080']}]) for r in d if r['name']=='watson-net-allow-iap-ssh']"
drift "extra inbound rule on watson-net" "Unexpected inbound rule watson-net-allow-app" \
  edit compute_firewall-rules_list.json "d.append({'name':'watson-net-allow-app','network':'$C/global/networks/watson-net','direction':'INGRESS','sourceRanges':['0.0.0.0/0'],'allowed':[{'IPProtocol':'tcp','ports':['8080']}]})"
HTTPS_RULE="{'name':'watson-net-allow-https','network':'$C/global/networks/watson-net','direction':'INGRESS','sourceRanges':['0.0.0.0/0'],'allowed':[{'IPProtocol':'tcp','ports':['80']},{'IPProtocol':'tcp','ports':['443']}],'targetTags':['casita-https']}"
drift "public HTTPS rule widened to 8080" "isn't tcp:80,443 to tag casita-https only" \
  edit compute_firewall-rules_list.json "d.append($HTTPS_RULE); d[-1]['allowed'].append({'IPProtocol':'tcp','ports':['8080']})"
drift "public HTTPS rule for every instance" "isn't tcp:80,443 to tag casita-https only" \
  edit compute_firewall-rules_list.json "d.append($HTTPS_RULE); d[-1].pop('targetTags')"
drift "IAP rule name on another network" "exists on network default" \
  edit compute_firewall-rules_list.json "[r.update(network='$C/global/networks/default') for r in d if r['name']=='watson-net-allow-iap-ssh']"
drift "network in auto mode" "isn't a custom-mode VPC" edit compute_networks_describe_watson-net.json "d['autoCreateSubnetworks']=True"
drift "subnet range" "range 10.0.0.0/8" edit compute_networks_subnets_describe_watson-subnet.json "d['ipCidrRange']='10.0.0.0/8'"
drift "role can delete documents" "Extra: datastore.entities.delete" \
  edit iam_roles_describe_casitaFirestoreRuntime.json "d['includedPermissions'].append('datastore.entities.delete')"
drift "role deleted" "is deleted" edit iam_roles_describe_casitaFirestoreRuntime.json "d['deleted']=True"
drift "service account also Editor" "also holds roles/editor" \
  edit "projects_get-iam-policy_$P.json" "d['bindings'][1]['members'].append('serviceAccount:$SA')"
drift "service account also datastore.user (which can delete)" "also holds roles/datastore.user" \
  edit "projects_get-iam-policy_$P.json" "d['bindings'].append({'role':'roles/datastore.user','members':['serviceAccount:$SA']})"
drift "project public to allUsers" "grants roles/viewer to allUsers" \
  edit "projects_get-iam-policy_$P.json" "d['bindings'].append({'role':'roles/viewer','members':['allUsers']})"
drift "a service-account key" "has 1 user-managed key" edit iam_service-accounts_keys_list.json "d.append({'name':'k1','keyType':'USER_MANAGED'})"
drift "Firestore in another location" "location us-east1, expected us-central1" edit firestore_databases_describe.json "d['locationId']='us-east1'"
drift "Firestore without delete protection" "delete protection DELETE_PROTECTION_DISABLED" \
  edit firestore_databases_describe.json "d['deleteProtectionState']='DELETE_PROTECTION_DISABLED'"
drift "Firestore in Datastore mode" "mode DATASTORE_MODE" edit firestore_databases_describe.json "d['type']='DATASTORE_MODE'"
drift "Firestore without point-in-time recovery" "point-in-time recovery POINT_IN_TIME_RECOVERY_DISABLED" \
  edit firestore_databases_describe.json "d['pointInTimeRecoveryEnablement']='POINT_IN_TIME_RECOVERY_DISABLED'"
drift "Firestore with PITR unset" "point-in-time recovery unset" edit firestore_databases_describe.json "d.pop('pointInTimeRecoveryEnablement')"
open_rules() { rules_as "rules_version = '2'; service cloud.firestore { match /databases/{database}/documents { match /{document=**} { allow read, write: if true; } } }"; }
drift "Security Rules that allow everything" "differ from firestore.rules" open_rules
expiring() { rules_as "rules_version = '2'; service cloud.firestore { match /databases/{database}/documents { match /{document=**} { allow read, write: if request.time < timestamp.date(2026, 11, 1); } } }"; }
drift "Firebase test-mode rules" "differ from firestore.rules" expiring
rules_denied() { rm "$st/rules_release.json"; touch "$st/rules_release.fail"; }
drift "rules release unreadable" "Couldn't read the Firestore rules release: HTTP 403" rules_denied
foreign() { edit rules_release.json "d['rulesetName']='projects/other/rulesets/x'"; }
drift "rules release pointing at another project" "Couldn't read the released Firestore ruleset" foreign
schema2() { echo '{"fields":{"schemaVersion":{"integerValue":"2"}}}' > "$st/schema.doc"; }
drift "schema marker another version" "schemaVersion 2, expected 1" schema2
denied() { rm "$st/compute_instances_list.json"; echo "ERROR: (gcloud.compute.instances.list) PERMISSION_DENIED: Required 'compute.instances.list'" > "$st/compute_instances_list.fail"; }
drift "a read refused (permission)" "Couldn't read VM instances: ERROR: (gcloud.compute.instances.list) PERMISSION_DENIED" denied

scenario "no Firestore rules released yet: only the rules are created, ruleset then release"
compliant; rm "$st/rules_release.json" "$st/rules_ruleset.json"
run_script "$work/watson.conf"
expect_code 0; expect_no_mutation; expect_out "CREATE   Firestore Security Rules: deny all (firestore.rules)"
run_script "$work/watson.conf" --apply
expect_code 0; expect_mutations "curl POST" "curl POST"; expect_out "Firestore rules released (projects/bentals-casita/rulesets/new-1)"
grep -q '^MUTATE curl POST .*/v1/projects/bentals-casita/rulesets$' "$log" || bad "ruleset not created first"
grep -q '^MUTATE curl POST .*/v1/projects/bentals-casita/releases$' "$log" || bad "release not created"
done_scenario

scenario "released rules equal to firestore.rules but formatted differently: accepted"
compliant; rules_as "rules_version = '2';
service cloud.firestore { match /databases/{database}/documents { match /{document=**} {
    // reformatted
    allow read, write: if false;
} } }"
run_script "$work/watson.conf" --apply
expect_code 0; expect_no_mutation; expect_out "OK       Firestore Security Rules: deny all"
done_scenario

scenario "public HTTPS rule exactly as provision-public.sh makes it: accepted"
compliant
edit compute_firewall-rules_list.json "d.append($HTTPS_RULE)"
run_script "$work/watson.conf" --apply
expect_code 0; expect_no_mutation; expect_no_out "PROBLEM"
expect_out "OK       Firewall watson-net-allow-https: tcp:80,443 from anywhere, to tag casita-https only (public HTTPS)"
done_scenario

scenario "bad usage and settings: refused before any read"
fresh; run_script "$work/watson.conf" --aply
expect_code 2; [ -s "$log" ] && bad "read something"
sed 's/^ZONE=.*/ZONE=us-east1-b/' "$work/watson.conf" > "$work/bad.conf"; run_script "$work/bad.conf"
expect_code 2; expect_out "isn't in REGION"
sed 's/^PROJECT_ID=.*/PROJECT_ID="<PROJECT_ID>"/' "$work/watson.conf" > "$work/bad.conf"; run_script "$work/bad.conf"
expect_code 2; expect_out "still a placeholder"
done_scenario

echo
echo "$passes passed, $fails failed"
[ $fails -eq 0 ]
