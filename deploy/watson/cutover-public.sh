#!/usr/bin/env bash
# Cutover: casita.service moves to the new release, the public name and browser keys; Caddy forwards to it; the
# preview stops (docs/PUBLIC_HTTPS.md, step 6). Run on the VM as root, from the release directory's deploy/ folder.
#
#   sudo bash cutover-public.sh --host watsonbental.duckdns.org            # checks everything, changes nothing
#   sudo bash cutover-public.sh --host watsonbental.duckdns.org --apply
#
# Refuses (nothing changed) unless: Caddy serves https://<host> with a trusted certificate; the preview is running the
# release to install; and at least one family member already has an active browser-key device, i.e. someone has set
# up a passkey on the public name and can sign in the moment the old path goes away.
#
# Before changing anything it saves, in /etc/casita/cutover/ (root only): casita.env, the casita.service unit, the
# Caddyfile and which release was current. rollback-public.sh puts all of it back. Tailscale itself is not touched
# here: until remove-tailscale.sh runs, Tailscale Serve is still configured, so a rollback brings the old path back.
#
# If the new casita.service doesn't come up healthy, this rolls back by itself (settings, unit, release) and says so.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
release="$(cd "$here/.." && pwd)"
host=""; apply=no
while [ $# -gt 0 ]; do
  case "$1" in --host) host="${2:-}"; shift 2 ;; --apply) apply=yes; shift ;; *) echo "usage: cutover-public.sh --host <name> [--apply]" >&2; exit 2 ;; esac
done
[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)" >&2; exit 2; }
[[ "$host" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]] || { echo "--host must be a lower-case DNS name" >&2; exit 2; }
env_file=/etc/casita/casita.env; state=/etc/casita/cutover
fail=0
ok()  { echo "ok:   $*"; }
bad() { echo "FAIL: $*"; fail=1; }

# ---------- checks ----------
for f in casita.jar casita-access RELEASE deploy/casita.service deploy/public-https.sh deploy/health.sh deploy/Caddyfile; do
  [ -f "$release/$f" ] || bad "$release/$f is missing (run this from the release directory deploy-release.sh copied)"
done
[ $fail -eq 0 ] || exit 2
sha="$(sha256sum "$release/casita.jar" | cut -d' ' -f1)"
preview_jar="$(readlink -f /opt/casita-public/current/casita.jar 2>/dev/null || true)"
if [ -n "$preview_jar" ] && [ "$(sha256sum "$preview_jar" | cut -d' ' -f1)" = "$sha" ]; then ok "the preview runs this release ($(cut -d' ' -f1 < "$release/RELEASE"))"
else bad "the preview isn't running this release's casita.jar: test the release in the preview first"; fi
systemctl is-active --quiet casita-public.service && ok "casita-public.service active" || bad "casita-public.service isn't running"
systemctl is-active --quiet caddy && ok "caddy active" || bad "caddy isn't running (public-https.sh)"
[ "$(curl -s --max-time 5 --noproxy '*' --resolve "$host:443:127.0.0.1" "https://$host/healthz" || true)" = ok ] \
  && ok "https://$host/healthz answers ok with a trusted certificate" || bad "https://$host/healthz doesn't answer ok over trusted HTTPS"
[ -r "$env_file" ] || bad "$env_file is missing"
[ -r /var/lib/casita/admin.secret ] || bad "/var/lib/casita/admin.secret is missing"
enrolled=""
if [ $fail -eq 0 ]; then
  access="$(printf 'X-Casita-Admin: %s\n' "$(cat /var/lib/casita/admin.secret)" | curl -s --fail --max-time 10 --noproxy '*' -H @- http://127.0.0.1:8081/admin/access || true)"
  enrolled="$(python3 -I -c '
import json, sys
try:
    d = json.loads(sys.stdin.read())
except Exception:
    sys.exit(0)
for m in d.get("members", []):
    n = sum(1 for x in m.get("devices", []) if x.get("status") == "ACTIVE" and str(x.get("nodeId", "")).startswith("b-"))
    if m.get("status") == "ACTIVE" and n:
        print("%s:%d" % (m["name"], n))
' <<<"$access")"
  if [ -n "$enrolled" ]; then ok "enrolled on $host (member:browsers): $(echo $enrolled)"
  else bad "nobody has set up a passkey on $host yet: enroll first (casita-access-public invite ...), then come back"; fi
fi
deployment="$(sed -n 's/^CASITA_DEPLOYMENT=//p' "$env_file")"
transport="$(sed -n 's/^CASITA_MEROSS_TRANSPORT=//p' "$env_file")"
echo "info: casita.service settings: deployment=${deployment:-?} transport=${transport:-cloud}"
[ "${transport:-cloud}" = cloud ] || echo "note: transport is '$transport'; it depends on a private route to home. remove-tailscale.sh will refuse while it does."
[ $fail -eq 0 ] || { echo "STOPPED: nothing changed."; exit 1; }

# The new settings: the old ones with sign-in moved to the public name and browser keys; everything else as it was.
new_env="$(mktemp)"; trap 'rm -f "$new_env"' EXIT
grep -vE '^(CASITA_SECURITY_RPID|CASITA_SECURITY_ALLOWEDORIGINS|CASITA_SECURITY_DEVICEIDENTITY|CASITA_SECURITY_TAILSCALECLI|SERVER_ADDRESS)=' "$env_file" > "$new_env"
cat >> "$new_env" <<EOF
# Public HTTPS (cutover-public.sh, $(date -u +%Y-%m-%dT%H:%M:%SZ)): passkeys on the public name, browser keys, loopback only.
CASITA_SECURITY_RPID=$host
CASITA_SECURITY_ALLOWEDORIGINS=https://$host
CASITA_SECURITY_DEVICEIDENTITY=browser
SERVER_ADDRESS=127.0.0.1
EOF
echo "== /etc/casita/casita.env would change like this:"
diff -u "$env_file" "$new_env" | sed 's/^/    /' || true
if [ "$apply" != yes ]; then echo "DRY RUN: nothing changed. Add --apply."; exit 0; fi

# ---------- save what rollback needs ----------
install -d -m 0700 "$state"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
cp -p "$env_file" "$state/casita.env.before-$stamp"
cp -p /etc/systemd/system/casita.service "$state/casita.service.before-$stamp"
[ -f /etc/caddy/Caddyfile ] && cp -p /etc/caddy/Caddyfile "$state/Caddyfile.before-$stamp"
old_current="$(readlink -f /opt/casita/current)"
printf 'stamp=%s\nold_current=%s\nhost=%s\n' "$stamp" "$old_current" "$host" > "$state/last"
echo "saved: $state (stamp $stamp, release before: $old_current)"

restore() {
  echo "== ROLLING BACK: $*" >&2
  cp -p "$state/casita.env.before-$stamp" "$env_file"
  cp -p "$state/casita.service.before-$stamp" /etc/systemd/system/casita.service
  systemctl daemon-reload
  ln -sfn "$old_current" /opt/casita/current.new && mv -T /opt/casita/current.new /opt/casita/current
  systemctl reset-failed casita.service || true
  systemctl enable casita.service >/dev/null 2>&1 || true
  systemctl restart casita.service || true
  /usr/local/sbin/casita-health --wait 90 || echo "WARNING: the old release isn't healthy either: check journalctl -u casita" >&2
  echo "Rolled back to $old_current with the old settings. Caddy still forwards $host to the preview." >&2
  exit 1
}

# ---------- switch ----------
install -m 0640 -o root -g casita "$new_env" "$env_file"
install -m 0644 "$release/deploy/casita.service" /etc/systemd/system/casita.service
systemctl daemon-reload
for s in health:casita-health install-release:casita-install-release rollback:casita-rollback; do
  [ -f "$release/deploy/${s%%:*}.sh" ] && install -m 0755 "$release/deploy/${s%%:*}.sh" "/usr/local/sbin/${s##*:}"
done
install -m 0755 "$release/deploy/casita-access" /usr/local/bin/casita-access
if ! /usr/local/sbin/casita-install-release "$release" "$sha"; then restore "casita-install-release failed"; fi
since="$(systemctl show -p ActiveEnterTimestamp --value casita.service)"
journalctl -u casita.service --since "${since:-today}" --no-pager -o cat | grep -E '(staging|production) guard: ok' | tail -1 | grep -q "rp_id=$host" \
  || restore "casita.service started without the guard's ok line for rp_id=$host"

echo "== Caddy: $host -> 127.0.0.1:8080"
if ! bash "$release/deploy/public-https.sh" --host "$host" --upstream 127.0.0.1:8080 --apply; then
  echo "Caddy didn't switch; pointing it back at the preview" >&2
  bash "$release/deploy/public-https.sh" --host "$host" --upstream 127.0.0.1:8081 --apply || true
  restore "Caddy couldn't reach the new casita.service"
fi
systemctl disable --now casita-public.service >/dev/null 2>&1 || true
echo "done" > "$state/cutover-$stamp.done"
echo
echo "Cutover done: https://$host is casita.service ($(cut -d' ' -f1 < "$release/RELEASE"), deployment=$deployment); the preview is stopped."
echo "The old Tailscale address no longer signs anyone in (its passkeys are for the old name). Tailscale Serve is still"
echo "configured, so 'sudo bash rollback-public.sh' brings everything back. Once you're happy: remove-tailscale.sh."
