#!/usr/bin/env bash
# Provision watson's GCP resources: APIs, Casita's service account and no-delete role, the (default) Firestore
# database with its schema marker, a private network, and the e2-micro VM.
#
#   deploy/watson/provision-gcp.sh deploy/watson/watson.conf            # dry run: prints every command, runs nothing
#   deploy/watson/provision-gcp.sh deploy/watson/watson.conf --apply    # runs them, one at a time, stopping at the first error
#
# Run from your Mac with gcloud signed in as the project owner (gcloud auth login). Each step checks whether its
# resource already exists, so a re-run continues where an earlier one stopped. It never deletes anything, never
# creates a service-account key, and never touches billing (set the budget alert in the console: see the runbook).
set -euo pipefail

conf="${1:?usage: provision-gcp.sh <watson.conf> [--apply]}"
apply="${2:-}"
# shellcheck source=/dev/null
. "$conf"
for v in PROJECT_ID REGION ZONE FIRESTORE_LOCATION VM_NAME NETWORK SUBNET SUBNET_RANGE SERVICE_ACCOUNT ROLE_ID; do
  val="${!v:-}"
  [ -n "$val" ] || { echo "$v is not set in $conf" >&2; exit 2; }
  case "$val" in *"<"*|*">"*) echo "$v is still a placeholder ($val) in $conf" >&2; exit 2 ;; esac
done
SA_EMAIL="$SERVICE_ACCOUNT@$PROJECT_ID.iam.gserviceaccount.com"
G="gcloud --project=$PROJECT_ID --quiet"

step() { echo; echo "== $*"; }
# run CMD...: print it; with --apply, run it.
run() {
  printf '  $'; printf ' %q' "$@"; echo
  if [ "$apply" = "--apply" ]; then "$@"; fi
}
# exists CMD...: true when the describe command succeeds (only with --apply; a dry run assumes nothing exists).
exists() { [ "$apply" = "--apply" ] && "$@" >/dev/null 2>&1; }

[ "$apply" = "--apply" ] || echo "DRY RUN: nothing will be changed. Add --apply to run these commands."
echo "Project $PROJECT_ID, region $REGION, zone $ZONE, VM $VM_NAME"

step "1. APIs: Compute Engine, Firestore, Identity-Aware Proxy (for SSH during setup)"
run $G services enable compute.googleapis.com firestore.googleapis.com iap.googleapis.com

step "2. Casita's service account (no keys, ever: the VM uses it through the metadata server)"
exists $G iam service-accounts describe "$SA_EMAIL" ||
  run $G iam service-accounts create "$SERVICE_ACCOUNT" --display-name="Casita runtime (watson)"

step "3. A custom role: read and write Firestore documents, never delete (gate G3, docs/FIRESTORE.md)"
PERMS=datastore.entities.get,datastore.entities.list,datastore.entities.create,datastore.entities.update,datastore.databases.getMetadata
exists $G iam roles describe "$ROLE_ID" ||
  run $G iam roles create "$ROLE_ID" --title="Casita Firestore runtime (no delete)" \
    --description="Casita's security store: get, list, create, update. No delete." --permissions="$PERMS" --stage=GA
run $G projects add-iam-policy-binding "$PROJECT_ID" --member="serviceAccount:$SA_EMAIL" \
  --role="projects/$PROJECT_ID/roles/$ROLE_ID" --condition=None

step "4. Firestore: the (default) database, Native mode, $FIRESTORE_LOCATION, protected from deletion"
exists $G firestore databases describe --database='(default)' ||
  run $G firestore databases create --database='(default)' --location="$FIRESTORE_LOCATION" --type=firestore-native \
    --delete-protection

step "5. Firestore TTL policies (cleanup only; decided by nothing) and index exemptions for byte fields"
run $G firestore fields ttls update ttl --collection-group=invitations --enable-ttl --async
run $G firestore fields ttls update expiresAt --collection-group=securityEvents --enable-ttl --async
for f in publicKeyCose attestationObject attestationClientDataJson; do
  run $G firestore indexes fields update "$f" --collection-group=passkeys --disable-indexes --async
done

step "6. The schema marker meta/schema {schemaVersion: 1}: Casita checks it at start and never writes it"
url="https://firestore.googleapis.com/v1/projects/$PROJECT_ID/databases/(default)/documents/meta/schema"
echo "  \$ curl -X PATCH '$url' (as you, with gcloud's access token) -d '{\"fields\":{\"schemaVersion\":{\"integerValue\":\"1\"}}}'"
if [ "$apply" = "--apply" ]; then
  curl --fail-with-body -sS -X PATCH "$url" -H "Authorization: Bearer $(gcloud auth print-access-token)" \
    -H "x-goog-user-project: $PROJECT_ID" -H 'Content-Type: application/json' \
    -d '{"fields":{"schemaVersion":{"integerValue":"1"}}}'
  echo
fi

step "7. A private network: no inbound traffic except SSH from Identity-Aware Proxy's range"
exists $G compute networks describe "$NETWORK" ||
  run $G compute networks create "$NETWORK" --subnet-mode=custom
exists $G compute networks subnets describe "$SUBNET" --region="$REGION" ||
  run $G compute networks subnets create "$SUBNET" --network="$NETWORK" --region="$REGION" --range="$SUBNET_RANGE" \
    --enable-private-ip-google-access
exists $G compute firewall-rules describe "$NETWORK-allow-iap-ssh" ||
  run $G compute firewall-rules create "$NETWORK-allow-iap-ssh" --network="$NETWORK" --direction=INGRESS \
    --action=allow --rules=tcp:22 --source-ranges=35.235.240.0/20 --description="SSH only through Identity-Aware Proxy"
# Everything else inbound is refused by the network's implied deny rule. Outbound is open: Tailscale, Debian,
# Adoptium and Firestore are all reached outbound.

step "8. The VM: e2-micro, 30 GB standard disk (Always Free), Debian 12, Shielded VM, OS Login, Casita's service account"
exists $G compute instances describe "$VM_NAME" --zone="$ZONE" ||
  run $G compute instances create "$VM_NAME" --zone="$ZONE" --machine-type=e2-micro \
    --image-family=debian-12 --image-project=debian-cloud --boot-disk-size=30GB --boot-disk-type=pd-standard \
    --network="$NETWORK" --subnet="$SUBNET" \
    --service-account="$SA_EMAIL" --scopes=cloud-platform \
    --shielded-secure-boot --shielded-vtpm --shielded-integrity-monitoring \
    --metadata=enable-oslogin=TRUE,block-project-ssh-keys=TRUE \
    --labels=app=casita,env=staging
# The VM keeps an ephemeral external IPv4 address for outbound traffic only (no firewall rule admits inbound).
# Without one, outbound would need Cloud NAT, which costs more than the address. See the cost section of the runbook.

echo
echo "Next: deploy/watson/check-g3.sh on the VM (no-delete check), then deploy/watson/bootstrap-vm.sh."
