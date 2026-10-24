#!/usr/bin/env bash
# Is Casita on watson healthy, and reachable only as it should be? Run on the VM (casita-health). Read-only.
#
#   casita-health            # one check
#   casita-health --wait 90  # wait up to 90 s for /healthz (used after a restart)
#
# Exit 0 only when: the service is active, /healthz answers "ok" on 127.0.0.1:8080, this start's log has the
# staging or production guard's "ok" line, port 8080 is open on loopback only, and, once Caddy forwards the public
# name to casita.service, https://<public name>/healthz answers "ok" with a trusted certificate.
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
pline="$(journalctl -u casita.service --since "${since:-today}" --no-pager -o cat 2>/dev/null | grep 'production guard: ok' | tail -1)"
case "$line$pline" in
  *"door=simulated command_mode=off meross_credentials=absent storage=firestore"*) ok "staging guard: ${line##*staging guard: }" ;;
  *"production guard: ok deployment=production"*"door=meross command_mode="*) ok "production guard: ${pline##*production guard: }" ;;
  *) bad "no 'staging guard: ok' or 'production guard: ok' line since the last start" ;;
esac

# Casita itself listens on loopback only: Caddy is the only way in from outside.
listeners="$(ss -ltnH 'sport = :8080' 2>/dev/null | awk '{print $4}' | sort -u | tr '\n' ' ')"
case "$listeners" in
  "") ;;  # not up: already reported above
  *) only_loopback=yes
     for l in $listeners; do case "$l" in 127.0.0.1:8080|"[::1]:8080") ;; *) only_loopback=no ;; esac; done
     [ "$only_loopback" = yes ] && ok "port 8080 on loopback only" || bad "port 8080 is open on $listeners, not loopback only" ;;
esac
if systemctl list-unit-files caddy.service >/dev/null 2>&1 && [ -f /etc/caddy/Caddyfile ]; then
  systemctl is-active --quiet caddy && ok "caddy active" || bad "caddy is $(systemctl is-active caddy)"
  host="$(sed -n 's/^CASITA_SECURITY_RPID=//p' /etc/casita/casita.env 2>/dev/null)"
  site="$(awk '/^[a-z0-9.-]+ [{]$/ {print $1; exit}' /etc/caddy/Caddyfile)"
  upstream="$(awk '$1 == "reverse_proxy" {print $2; exit}' /etc/caddy/Caddyfile)"
  echo "info: caddy forwards ${site:-?} to ${upstream:-?}"
  # Only when Caddy serves casita.service's own sign-in name (after cutover): it must work end to end.
  if [ -n "$host" ] && [ "$site" = "$host" ] && [ "$upstream" = "127.0.0.1:8080" ]; then
    [ "$(curl -s --max-time 5 --noproxy '*' --resolve "$host:443:127.0.0.1" "https://$host/healthz")" = "ok" ] \
      && ok "https://$host/healthz ok (trusted certificate)" || bad "https://$host/healthz isn't ok over trusted HTTPS"
  fi
fi

pid="$(systemctl show -p MainPID --value casita.service)"
[ -n "$pid" ] && [ "$pid" != 0 ] && echo "info: RSS $(awk '/VmRSS/ {printf "%d MiB", $2/1024}' /proc/$pid/status 2>/dev/null), release $(cat /opt/casita/current/RELEASE 2>/dev/null)"
free -m | awk 'NR==2 {print "info: memory used " $3 " / " $2 " MiB"} NR==3 {print "info: swap used " $3 " / " $2 " MiB"}'
exit $fail
