#!/usr/bin/env bash
# Is Casita on watson healthy, and still a simulation-only staging? Run on the VM (casita-health). Read-only.
#
#   casita-health            # one check
#   casita-health --wait 90  # wait up to 90 s for /healthz (used after a restart)
#
# Exit 0 only when: the service is active, /healthz answers "ok" on 127.0.0.1:8080, this start's log has the
# staging guard's "ok" line with door=simulated and command_mode=off, and Funnel is off.
set -uo pipefail
wait=0; [ "${1:-}" = "--wait" ] && wait="${2:-90}"
fail=0
ok()  { echo "ok:   $*"; }
bad() { echo "FAIL: $*"; fail=1; }

deadline=$(( $(date +%s) + wait ))
until [ "$(curl -s --max-time 2 --noproxy '*' http://127.0.0.1:8080/healthz)" = "ok" ]; do
  [ "$(date +%s)" -ge "$deadline" ] && break
  sleep 2
done

systemctl is-active --quiet casita.service && ok "casita.service active" || bad "casita.service is $(systemctl is-active casita.service)"
[ "$(curl -s --max-time 2 --noproxy '*' http://127.0.0.1:8080/healthz)" = "ok" ] && ok "/healthz ok" || bad "/healthz not answering ok"

since="$(systemctl show -p ActiveEnterTimestamp --value casita.service)"
line="$(journalctl -u casita.service --since "${since:-today}" --no-pager -o cat 2>/dev/null | grep 'staging guard: ok' | tail -1)"
case "$line" in
  *"door=simulated command_mode=off meross_credentials=absent storage=firestore"*) ok "staging guard: ${line##*staging guard: }" ;;
  *) bad "no 'staging guard: ok' line with door=simulated and command_mode=off since the last start" ;;
esac

if command -v tailscale >/dev/null; then
  tailscale funnel status 2>/dev/null | grep -qi "funnel on" && bad "Tailscale Funnel is on: Casita must not be public" || ok "Funnel off"
  tailscale serve status 2>/dev/null | grep -q "127.0.0.1:8080" && ok "tailscale serve -> 127.0.0.1:8080" || echo "note: tailscale serve isn't proxying to 127.0.0.1:8080 yet"
fi

pid="$(systemctl show -p MainPID --value casita.service)"
[ -n "$pid" ] && [ "$pid" != 0 ] && echo "info: RSS $(awk '/VmRSS/ {printf "%d MiB", $2/1024}' /proc/$pid/status 2>/dev/null), release $(cat /opt/casita/current/RELEASE 2>/dev/null)"
free -m | awk 'NR==2 {print "info: memory used " $3 " / " $2 " MiB"} NR==3 {print "info: swap used " $3 " / " $2 " MiB"}'
exit $fail
