#!/usr/bin/env bash
# Undo cutover-public.sh: casita.service back on the release, settings and unit it had before (the Tailscale access
# path), and Caddy back on the preview (docs/PUBLIC_HTTPS.md, "Rollback"). Run on the VM as root.
#
#   sudo bash rollback-public.sh            # shows what it would restore
#   sudo bash rollback-public.sh --apply
#   sudo bash rollback-public.sh --apply --stop-caddy   # also take the public name offline instead of serving the preview
#
# Works only until remove-tailscale.sh has run (after that the old path has nothing to come back to). Passkeys,
# members and devices are untouched either way: they live in Firestore, which neither release changes the shape of.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
apply=no; stop_caddy=no
for a in "$@"; do case "$a" in --apply) apply=yes ;; --stop-caddy) stop_caddy=yes ;; *) echo "usage: rollback-public.sh [--apply] [--stop-caddy]" >&2; exit 2 ;; esac; done
[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)" >&2; exit 2; }
state=/etc/casita/cutover
[ -r "$state/last" ] || { echo "no cutover recorded in $state: nothing to roll back" >&2; exit 1; }
# shellcheck source=/dev/null
. "$state/last"
[ -f "$state/tailscale-removed" ] && { echo "Tailscale was removed ($state/tailscale-removed): the old access path can't come back this way." >&2; exit 1; }
for f in "casita.env.before-$stamp" "casita.service.before-$stamp"; do [ -f "$state/$f" ] || { echo "$state/$f is missing" >&2; exit 1; }; done
[ -f "$old_current/casita.jar" ] || { echo "the release before cutover ($old_current) is gone" >&2; exit 1; }
command -v tailscale >/dev/null && echo "Tailscale Serve now: $(tailscale serve status 2>&1 | tr '\n' ' ' | cut -c1-200)"

echo "Would restore: /etc/casita/casita.env and casita.service from $stamp; /opt/casita/current -> $old_current"
diff -u /etc/casita/casita.env "$state/casita.env.before-$stamp" | sed 's/^/    /' || true
if [ "$stop_caddy" = yes ]; then echo "Would stop Caddy ($host goes offline)"; else echo "Would start the preview again and point Caddy for $host at it"; fi
[ "$apply" = yes ] || { echo "DRY RUN: nothing changed. Add --apply."; exit 0; }

cur="$(readlink -f /opt/casita/current)"
install -m 0640 -o root -g casita "$state/casita.env.before-$stamp" /etc/casita/casita.env
install -m 0644 "$state/casita.service.before-$stamp" /etc/systemd/system/casita.service
systemctl daemon-reload
ln -sfn "$old_current" /opt/casita/current.new && mv -T /opt/casita/current.new /opt/casita/current
ln -sfn "$cur" /opt/casita/previous
systemctl reset-failed casita.service || true
systemctl enable casita.service >/dev/null
systemctl restart casita.service
/usr/local/sbin/casita-health --wait 90 || echo "WARNING: casita.service isn't healthy on the old release: journalctl -u casita -n 80" >&2

if [ "$stop_caddy" = yes ]; then
  systemctl disable --now caddy
  echo "Caddy stopped: $host is offline."
elif [ -f /etc/systemd/system/casita-public.service ]; then
  systemctl enable --now casita-public.service
  bash "$here/public-https.sh" --host "$host" --upstream 127.0.0.1:8081 --apply || echo "WARNING: Caddy didn't switch to the preview" >&2
fi
echo "rolled back $(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$state/rollback-$stamp.done"
echo "Rolled back: casita.service runs $old_current with the settings from before the cutover (Tailscale access path)."
