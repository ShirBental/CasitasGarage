#!/usr/bin/env bash
# Public HTTPS on watson with Caddy (docs/PUBLIC_HTTPS.md). Run on the VM as root.
#
#   sudo bash public-https.sh --host watsonbental.duckdns.org                          # shows what it would do
#   sudo bash public-https.sh --host watsonbental.duckdns.org --upstream 127.0.0.1:8081 --apply
#
# --upstream is where Caddy forwards: Casita's loopback port (8080 for casita.service, 8081 for the public preview
# while it runs; cutover-public.sh switches it). Only 127.0.0.1:<port> is accepted.
#
# With --apply: installs Caddy from its signed apt repository if it isn't installed, renders deploy/watson/Caddyfile to
# /etc/caddy/Caddyfile (the old one kept as Caddyfile.bak-<time>), validates it before switching, reloads Caddy and
# waits until https://<host>/healthz answers "ok" with a certificate this VM's own CA store trusts.
#
# Refuses (nothing changed) unless <host> resolves to this VM's external address: Let's Encrypt would fail, and
# repeated failures are rate limited. Point DuckDNS at the VM first (duckdns-update.sh).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
host=""; upstream="127.0.0.1:8080"; apply=no
while [ $# -gt 0 ]; do
  case "$1" in
    --host) host="${2:-}"; shift 2 ;;
    --upstream) upstream="${2:-}"; shift 2 ;;
    --apply) apply=yes; shift ;;
    *) echo "usage: public-https.sh --host <name> [--upstream 127.0.0.1:<port>] [--apply]" >&2; exit 2 ;;
  esac
done
[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)" >&2; exit 2; }
[[ "$host" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]] || { echo "--host must be a lower-case DNS name, got '$host'" >&2; exit 2; }
[[ "$upstream" =~ ^127\.0\.0\.1:[0-9]{2,5}$ ]] || { echo "--upstream must be 127.0.0.1:<port>, got '$upstream'" >&2; exit 2; }
[ -f "$here/Caddyfile" ] || { echo "$here/Caddyfile is missing" >&2; exit 2; }

md() { curl -fsS --max-time 3 --noproxy '*' -H 'Metadata-Flavor: Google' "http://169.254.169.254/computeMetadata/v1/$1"; }
ext_ip="$(md instance/network-interfaces/0/access-configs/0/external-ip || true)"
int_ip="$(md instance/network-interfaces/0/ip || true)"
[ -n "$ext_ip" ] || { echo "can't read this VM's external address from the metadata server: not on Compute Engine, or no external address" >&2; exit 1; }
resolved="$({ getent ahostsv4 "$host" || true; } | awk '{print $1}' | sort -u | tr '\n' ' ' | sed 's/ $//')"
[[ "$int_ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "can't read this VM's internal address from the metadata server" >&2; exit 1; }
echo "This VM's external address: $ext_ip (Caddy listens on $int_ip and 127.0.0.1)"
echo "$host resolves to:       ${resolved:-nothing}"
if [ "$resolved" != "$ext_ip" ]; then
  echo "REFUSED: $host must resolve to $ext_ip (and only to it) before Caddy asks for a certificate. Run duckdns-update.sh first." >&2
  exit 1
fi

# The Casita upstream must be listening on loopback only (Java reports a 127.0.0.1 bind as [::ffff:127.0.0.1]).
port="${upstream##*:}"
listeners="$(ss -ltnH "sport = :$port" | awk '{print $4}' | sort -u | tr '\n' ' ')"
echo "Listening on port $port:  ${listeners:-nothing}"
case "$listeners" in
  "") echo "note: nothing listens on $upstream yet; Caddy will answer 502 until Casita is up there" ;;
  *) for l in $listeners; do case "$l" in 127.0.0.1:"$port"|"[::1]:$port"|"[::ffff:127.0.0.1]:$port") ;; *) echo "REFUSED: port $port is open on $l, not loopback only" >&2; exit 1 ;; esac; done ;;
esac

rendered="$(mktemp)"; trap 'rm -f "$rendered"' EXIT
sed -e "s/{{HOST}}/$host/g" -e "s/{{UPSTREAM}}/$upstream/g" -e "s/{{BIND}}/$int_ip 127.0.0.1/g" "$here/Caddyfile" > "$rendered"
if grep -q '{{' "$rendered"; then echo "the rendered Caddyfile still has a placeholder" >&2; exit 1; fi

if [ "$apply" != yes ]; then
  echo
  command -v caddy >/dev/null && echo "Caddy installed: $(caddy version)" || echo "Would install Caddy from https://dl.cloudsmith.io/public/caddy/stable (signed apt repository)"
  echo "Would write /etc/caddy/Caddyfile for $host -> $upstream, validate it, and reload Caddy:"
  diff -u /etc/caddy/Caddyfile "$rendered" 2>/dev/null || true
  echo "DRY RUN: nothing changed. Add --apply."
  exit 0
fi

if ! command -v caddy >/dev/null; then
  echo "== Caddy from its signed apt repository"
  DEBIAN_FRONTEND=noninteractive apt-get -y install debian-keyring debian-archive-keyring apt-transport-https curl gnupg
  curl -fsSL https://dl.cloudsmith.io/public/caddy/stable/gpg.key | gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -fsSL https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt -o /etc/apt/sources.list.d/caddy-stable.list
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get -y install caddy
fi
caddy version

echo "== /etc/caddy/Caddyfile: $host -> $upstream"
install -d -m 0755 /etc/caddy
install -m 0644 "$rendered" /etc/caddy/Caddyfile.new
# Caddy runs as caddy and must be able to open its access log; validating (as root) would otherwise create it root-owned.
install -d -o caddy -g caddy -m 0750 /var/log/caddy
caddy validate --config /etc/caddy/Caddyfile.new --adapter caddyfile >/dev/null
chown -R caddy:caddy /var/log/caddy
if [ -f /etc/caddy/Caddyfile ]; then cp -p /etc/caddy/Caddyfile "/etc/caddy/Caddyfile.bak-$(date -u +%Y%m%dT%H%M%SZ)"; fi
mv -f /etc/caddy/Caddyfile.new /etc/caddy/Caddyfile
systemctl enable caddy >/dev/null
if systemctl is-active --quiet caddy; then systemctl reload caddy; else systemctl restart caddy; fi

echo "== waiting for https://$host/healthz (certificate issuance can take a minute)"
deadline=$(( $(date +%s) + 180 ))
until [ "$(curl -s --max-time 5 --noproxy '*' --resolve "$host:443:127.0.0.1" "https://$host/healthz" || true)" = "ok" ]; do
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "FAIL: https://$host/healthz isn't answering ok with a trusted certificate. journalctl -u caddy -n 50 says why." >&2
    exit 1
  fi
  sleep 3
done
echo "ok: https://$host/healthz answers ok, certificate trusted:"
echo | openssl s_client -connect 127.0.0.1:443 -servername "$host" 2>/dev/null | openssl x509 -noout -issuer -subject -enddate
