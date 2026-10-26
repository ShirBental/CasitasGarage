#!/usr/bin/env bash
# Point a DuckDNS name at this VM's external address (docs/PUBLIC_HTTPS.md). Run on the VM as root.
#
#   sudo bash duckdns-update.sh --set-token                              # asks for the token (not shown as you type)
#   sudo bash duckdns-update.sh --host watsonbental.duckdns.org           # shows what it would do
#   sudo bash duckdns-update.sh --host watsonbental.duckdns.org --apply   # updates DuckDNS, waits for DNS to agree
#   sudo bash duckdns-update.sh --host watsonbental.duckdns.org --install-timer   # re-asserts it daily and at boot
#
# The address sent is the VM's own external address from the metadata server, explicitly: never whatever address the
# request happens to come from (your phone, Cloud Shell). The token lives in /etc/casita/duckdns.token (root, 0600)
# and only there: it is never printed, never put on a command line (curl reads it from stdin), never logged.
set -euo pipefail
token_file="${DUCKDNS_TOKEN_FILE:-/etc/casita/duckdns.token}"
host=""; mode=show
while [ $# -gt 0 ]; do
  case "$1" in
    --host) host="${2:-}"; shift 2 ;;
    --apply) mode=apply; shift ;;
    --set-token) mode=set-token; shift ;;
    --install-timer) mode=timer; shift ;;
    *) echo "usage: duckdns-update.sh --set-token | --host <name>.duckdns.org [--apply | --install-timer]" >&2; exit 2 ;;
  esac
done
[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)" >&2; exit 2; }

if [ "$mode" = set-token ]; then
  [ -t 0 ] || { echo "--set-token reads the token from a terminal" >&2; exit 2; }
  printf 'DuckDNS token (from duckdns.org, shown at the top when signed in; not echoed): '
  IFS= read -rs token; echo
  [[ "$token" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || { echo "that doesn't look like a DuckDNS token (a UUID); nothing saved" >&2; exit 1; }
  [ -d "$(dirname "$token_file")" ] || mkdir -p -m 0750 "$(dirname "$token_file")"
  umask 077
  printf '%s\n' "$token" > "$token_file.new" && chmod 600 "$token_file.new" && chown root:root "$token_file.new" && mv -f "$token_file.new" "$token_file"
  unset token
  echo "saved to $token_file (root only, 0600)"
  exit 0
fi

[[ "$host" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)\.duckdns\.org$ ]] || { echo "--host must be <name>.duckdns.org, got '$host'" >&2; exit 2; }
domain="${BASH_REMATCH[1]}"

if [ "$mode" = timer ]; then
  install -m 0755 "$(readlink -f "$0")" /usr/local/sbin/casita-duckdns-update
  cat > /etc/systemd/system/casita-duckdns.service <<UNIT
[Unit]
Description=Point $host at this VM's external address (DuckDNS)
Wants=network-online.target
After=network-online.target

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/casita-duckdns-update --host $host --apply
UNIT
  cat > /etc/systemd/system/casita-duckdns.timer <<UNIT
[Unit]
Description=Re-assert $host -> this VM daily and at boot

[Timer]
OnBootSec=2min
OnUnitActiveSec=1d
RandomizedDelaySec=15min

[Install]
WantedBy=timers.target
UNIT
  systemctl daemon-reload
  systemctl enable --now casita-duckdns.timer
  systemctl list-timers casita-duckdns.timer --no-pager
  exit 0
fi

ext_ip="$(curl -fsS --max-time 3 --noproxy '*' -H 'Metadata-Flavor: Google' \
  http://169.254.169.254/computeMetadata/v1/instance/network-interfaces/0/access-configs/0/external-ip || true)"
[[ "$ext_ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "can't read this VM's external IPv4 address from the metadata server" >&2; exit 1; }
now="$({ getent ahostsv4 "$host" || true; } | awk '{print $1}' | sort -u | tr '\n' ' ' | sed 's/ $//')"
echo "This VM's external address: $ext_ip"
echo "$host resolves to:       ${now:-nothing}"

if [ "$mode" != apply ]; then
  [ -r "$token_file" ] && echo "token: present in $token_file" || echo "token: MISSING: run with --set-token first"
  echo "DRY RUN: would set $host -> $ext_ip at DuckDNS. Add --apply."
  exit 0
fi

[ -r "$token_file" ] || { echo "no token in $token_file: run 'sudo bash duckdns-update.sh --set-token' first" >&2; exit 1; }
[ "$(stat -c %a "$token_file")" = 600 ] || { echo "$token_file must be mode 600" >&2; exit 1; }
token="$(tr -d '[:space:]' < "$token_file")"
# The URL (with the token) goes to curl as a config file on stdin, so it never appears in ps or the journal.
answer="$(printf 'url = "https://www.duckdns.org/update?domains=%s&token=%s&ip=%s"\n' "$domain" "$token" "$ext_ip" \
  | curl -sS --max-time 15 --config - || true)"
unset token
case "$answer" in
  OK*) echo "DuckDNS: OK ($host -> $ext_ip)" ;;
  KO*) echo "DuckDNS answered KO: the token or the name is wrong (the name must belong to the token's account)" >&2; exit 1 ;;
  *) echo "DuckDNS didn't answer (network?)" >&2; exit 1 ;;
esac

deadline=$(( $(date +%s) + 300 ))
until [ "$({ getent ahostsv4 "$host" || true; } | awk '{print $1}' | sort -u | tr '\n' ' ' | sed 's/ $//')" = "$ext_ip" ]; do
  [ "$(date +%s)" -ge "$deadline" ] && { echo "note: DuckDNS took the update, but this VM's resolver doesn't show it yet (cached); try again in a few minutes" >&2; exit 0; }
  sleep 5
done
echo "ok: $host resolves to $ext_ip"
