#!/usr/bin/env bash
# Gate G6: real Firestore latency from watson. Read-only. Run on the VM.
#
#   bash check-g6.sh <PROJECT_ID> [reads]
#
# 1. Times <reads> (default 100) reads of meta/schema over Firestore's REST API with the VM's own service account,
#    on one reused HTTPS connection: the network and Firestore's read path from this VM, without Casita.
# 2. If Tomcat's access log is on (see the runbook: SERVER_TOMCAT_ACCESSLOG_ENABLED=true for the measuring session),
#    summarizes the server time of Casita's own requests: every signed-in /api/doors poll includes the access check,
#    one strongly consistent BatchGet of three documents.
# The emulator numbers in docs/firestore-benchmark/RESULTS.md are not evidence for this gate.
set -uo pipefail
project="${1:?project id}"; n="${2:-100}"
md=http://metadata.google.internal/computeMetadata/v1
token="$(curl -s -H 'Metadata-Flavor: Google' "$md/instance/service-accounts/default/token" | jq -r .access_token)"
url="https://firestore.googleapis.com/v1/projects/$project/databases/(default)/documents/meta/schema"
args=(); for _ in $(seq 1 "$n"); do args+=("$url"); done

pct() { sort -n | awk '{a[NR]=$1} END {if (NR==0) {print "no samples"; exit} printf "n=%d p50=%.1f p95=%.1f p99=%.1f max=%.1f ms\n", NR, a[int(NR*0.50+0.5)], a[int(NR*0.95+0.5)], a[int(NR*0.99+0.5)], a[NR]}'; }

echo "== REST reads of meta/schema from $(hostname) ($(curl -s -H 'Metadata-Flavor: Google' "$md/instance/zone" | sed 's#.*/##'))"
curl -s -o /dev/null -H "Authorization: Bearer $token" -w '%{time_total}\n' "${args[@]}" |
  tail -n +2 | awk '{printf "%.1f\n", $1*1000}' | pct   # the first includes the TLS handshake: left out

log="$(ls -t /var/lib/casita/tomcat/logs/access_log* 2>/dev/null | head -1)"
if [ -n "$log" ]; then
  echo "== Casita's server time from $log (pattern %h %t %m %U %s %{ms}T: last field is milliseconds)"
  for path in "/api/doors" "/api/activity" "/login/webauthn"; do
    printf '%-18s ' "$path"; grep -F " $path " "$log" | awk '{print $NF}' | pct
  done
else
  echo "(no Tomcat access log: see the runbook to turn it on for the measuring session)"
fi
echo
echo "Proposed G6 pass: REST read p95 <= 100 ms, /api/doors p95 <= 250 ms, passkey sign-in p95 <= 1000 ms."
echo "Record the numbers, the date and the release in docs/WATSON_DEPLOYMENT_READINESS.md."
