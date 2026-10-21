#!/usr/bin/env bash
# Publish Casita on the tailnet only: https://watson.<tailnet>.ts.net -> http://127.0.0.1:8080. Run on watson as root.
#
#   sudo bash tailscale-serve.sh            # shows the current state and what it would do
#   sudo bash tailscale-serve.sh --apply    # configures Serve (persistent, in the background)
#
# Needs, in the tailnet's admin console: MagicDNS and HTTPS certificates enabled, and this node signed in as
# 'watson' (tailscale up --hostname=watson). Never Funnel: Casita refuses Funnel requests, and Funnel would put
# it on the public internet. docs/ACCESS.md has why Serve is the only thing that may send Casita requests.
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)" >&2; exit 1; }
name="$(tailscale status --json | jq -r '.Self.DNSName' | sed 's/\.$//')"
echo "This node's tailnet name: $name"
case "$name" in watson.*.ts.net) ;; *) echo "expected watson.<tailnet>.ts.net; run 'tailscale up --hostname=watson' first" >&2; exit 1 ;; esac
want="watson.${name#watson.}"
rp="$(sed -n 's/^CASITA_SECURITY_RPID=//p' /etc/casita/casita.env)"
[ "$rp" = "$want" ] || { echo "CASITA_SECURITY_RPID in /etc/casita/casita.env is '$rp', but this node is '$want'" >&2; exit 1; }

echo "Current Serve configuration:"; tailscale serve status || true
echo "Current Funnel configuration:"; tailscale funnel status || true
if [ "${1:-}" = "--apply" ]; then
  tailscale serve --bg --https=443 http://127.0.0.1:8080
  tailscale serve status
  echo "Open https://$want from a device on the tailnet."
else
  echo "Would run: tailscale serve --bg --https=443 http://127.0.0.1:8080   (add --apply)"
fi
