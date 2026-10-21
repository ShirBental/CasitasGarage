#!/usr/bin/env bash
# Build a Casita release from a clean, committed checkout and install it on watson. Run on your Mac.
#
#   deploy/watson/deploy-release.sh deploy/watson/watson.conf            # build and verify; shows what it would copy and run
#   deploy/watson/deploy-release.sh deploy/watson/watson.conf --apply    # also copies it to watson and installs it
#
# The build is the CI build (mvn verify, with the pinned Firestore emulator and the skip guard), so a release is
# never a jar that skipped its tests. The copy goes through Identity-Aware Proxy (gcloud compute scp/ssh
# --tunnel-through-iap); install-release.sh on the VM checks the jar's SHA-256, switches releases and rolls back
# by itself if the new one isn't healthy.
set -euo pipefail
conf="${1:?usage: deploy-release.sh <watson.conf> [--apply]}"; apply="${2:-}"
# shellcheck source=/dev/null
. "$conf"
repo="$(git rev-parse --show-toplevel)"; cd "$repo"
[ -z "$(git status --porcelain)" ] || { echo "the checkout has uncommitted changes: commit or stash them first" >&2; exit 1; }
commit="$(git rev-parse HEAD)"

jar_emulator="${FIRESTORE_EMULATOR_JAR:-$HOME/cloud-firestore-emulator-v1.22.0.jar}"
[ -f "$jar_emulator" ] || { echo "the Firestore emulator jar isn't at $jar_emulator (docs/FIRESTORE.md); set FIRESTORE_EMULATOR_JAR" >&2; exit 1; }
echo "9b6498b7f62714d67f48f59b3818883cd682dbcd46b9f59511de81c97bb5166c  $jar_emulator" | shasum -a 256 -c - >/dev/null

echo "== building $commit (mvn verify, as CI)"
(unset FIRESTORE_EMULATOR_HOST GOOGLE_APPLICATION_CREDENTIALS MEROSS_EMAIL MEROSS_PASSWORD CASITA_MEROSS_EMAIL CASITA_MEROSS_PASSWORD
 mvn -B -ntp clean verify -Dfirestore.emulator.jar="$jar_emulator" -Dfirestore.emulator.required=true)
python3 scripts/ci/check-test-report.py target/surefire-reports

out="target/watson-release/$commit"
rm -rf "$out"; mkdir -p "$out"
cp target/casitas-garage-0.1.0-SNAPSHOT.jar "$out/casita.jar"
cp bin/casita-access "$out/casita-access"
cp docs/WATSON_DEPLOYMENT_READINESS.md "$out/"
echo "$commit $(date -u +%Y-%m-%dT%H:%M:%SZ) $(git log -1 --format=%s)" > "$out/RELEASE"
sha="$(shasum -a 256 "$out/casita.jar" | cut -d' ' -f1)"
echo "== release $commit: casita.jar sha256 $sha ($(du -h "$out/casita.jar" | cut -f1))"

remote="casita-release-$commit"
G="gcloud --project=$PROJECT_ID compute"
copy=($G scp --zone="$ZONE" --tunnel-through-iap --recurse "$out" "$VM_NAME:$remote")
install=($G ssh "$VM_NAME" --zone="$ZONE" --tunnel-through-iap --command="sudo casita-install-release ~/$remote $sha && casita-health")
printf '  $'; printf ' %q' "${copy[@]}"; echo
printf '  $'; printf ' %q' "${install[@]}"; echo
if [ "$apply" = "--apply" ]; then
  "${copy[@]}"
  "${install[@]}"
else
  echo "Built and verified; nothing copied. Add --apply to install it on $VM_NAME."
fi
