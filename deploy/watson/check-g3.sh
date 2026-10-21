#!/usr/bin/env bash
# Gate G3: Casita's service account can read and write Firestore documents but can't delete one.
# Run on watson (it uses the VM's own service account through the metadata server). Changes nothing:
# the read is of meta/schema, and the delete targets a document that doesn't exist, so even a wrongly
# permitted delete removes nothing (IAM is checked before existence: a permitted delete answers 200, a
# forbidden one 403).
#
#   bash check-g3.sh <PROJECT_ID>
set -uo pipefail
project="${1:?project id}"
md=http://metadata.google.internal/computeMetadata/v1
token="$(curl -s -H 'Metadata-Flavor: Google' "$md/instance/service-accounts/default/token" | jq -r .access_token)"
email="$(curl -s -H 'Metadata-Flavor: Google' "$md/instance/service-accounts/default/email")"
[ -n "$token" ] && [ "$token" != null ] || { echo "no token from the metadata server" >&2; exit 1; }
echo "service account: $email"
base="https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents"
fail=0

code=$(curl -s -o /tmp/g3-read.json -w '%{http_code}' -H "Authorization: Bearer $token" "$base/meta/schema")
if [ "$code" = 200 ] && grep -q '"integerValue": *"1"' /tmp/g3-read.json; then echo "ok:   read meta/schema (schemaVersion 1)"
else echo "FAIL: read meta/schema answered $code"; fail=1; fi

code=$(curl -s -o /tmp/g3-delete.json -w '%{http_code}' -X DELETE -H "Authorization: Bearer $token" \
  "$base/meta/g3-delete-probe-that-does-not-exist")
if [ "$code" = 403 ]; then echo "ok:   delete refused (403): no delete permission"
else echo "FAIL: delete answered $code (expected 403): the runtime role allows deletes"; fail=1; fi

code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $token" \
  "https://cloudresourcemanager.googleapis.com/v1/projects/$project:getIamPolicy" -X POST)
[ "$code" = 403 ] && echo "ok:   can't read the project's IAM policy (403)" || echo "note: getIamPolicy answered $code"
rm -f /tmp/g3-read.json /tmp/g3-delete.json
[ $fail -eq 0 ] && echo "G3: PASS" || echo "G3: FAIL"
exit $fail
