#!/usr/bin/env bash
# The last step of the move to public HTTPS (docs/PUBLIC_HTTPS.md, step 7): take Tailscale off watson. Run on the VM
# as root, in an IAP SSH session (gcloud compute ssh --tunnel-through-iap), never one that comes over the tailnet.
#
#   sudo bash remove-tailscale.sh            # checks, changes nothing
#   sudo bash remove-tailscale.sh --apply
#
# Refuses (nothing changed) unless: the cutover is done and casita.service is healthy on the public name; Casita's
# settings have no Tailscale left and reach the doors through Meross's cloud (a local transport needs the tailnet's
# subnet route); the preview is stopped; and this SSH session comes through IAP (35.235.240.0/20), so removing
# Tailscale can't cut off the session doing it. Administration stays where it is: IAP SSH and OS Login.
#
# With --apply: resets Tailscale Serve and Funnel, logs the node out of the tailnet, stops and purges tailscaled and
# its apt repository, and removes the preview's unit and settings. Afterwards rollback-public.sh no longer applies;
# going back to Tailscale would mean reinstalling it (bootstrap-vm.sh from release/watson-staging-rc).
set -euo pipefail
apply=no; [ "${1:-}" = "--apply" ] && apply=yes
[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)" >&2; exit 2; }
state=/etc/casita/cutover; env_file=/etc/casita/casita.env
fail=0
ok()  { echo "ok:   $*"; }
bad() { echo "FAIL: $*"; fail=1; }

[ -r "$state/last" ] || { echo "no cutover recorded in $state: run cutover-public.sh first" >&2; exit 1; }
# shellcheck source=/dev/null
. "$state/last"
ls "$state"/cutover-*.done >/dev/null 2>&1 && ok "cutover done ($stamp)" || bad "the cutover didn't finish"
newest_done="$(ls -t "$state"/cutover-*.done "$state"/rollback-*.done 2>/dev/null | head -1 || true)"
case "$newest_done" in */rollback-*) bad "the last thing done was a rollback: cut over again first" ;; esac
grep -q "^CASITA_SECURITY_RPID=$host$" "$env_file" && ok "casita.service signs in on $host" || bad "$env_file isn't on $host"
grep -qE '^CASITA_SECURITY_(DEVICEIDENTITY=tailscale|TAILSCALECLI=)' "$env_file" && bad "$env_file still mentions Tailscale" || ok "no Tailscale in $env_file"
transport="$(sed -n 's/^CASITA_MEROSS_TRANSPORT=//p' "$env_file")"
[ "${transport:-cloud}" = cloud ] && ok "doors through Meross's cloud" || bad "CASITA_MEROSS_TRANSPORT=$transport needs the tailnet's subnet route"
/usr/local/sbin/casita-health >/dev/null 2>&1 && ok "casita-health passes" || bad "casita-health fails"
[ "$(curl -s --max-time 5 --noproxy '*' --resolve "$host:443:127.0.0.1" "https://$host/healthz" || true)" = ok ] && ok "https://$host/healthz ok" || bad "https://$host/healthz isn't ok"
systemctl is-active --quiet casita-public.service && bad "the preview is still running (sudo bash public-preview.sh --stop)" || ok "preview stopped"
client="${SSH_CONNECTION%% *}"
if [ -z "$client" ]; then
  bad "not in an SSH session I can check: run this through 'gcloud compute ssh watson --tunnel-through-iap'"
elif python3 -I -c 'import ipaddress,sys; sys.exit(0 if ipaddress.ip_address(sys.argv[1]) in ipaddress.ip_network("35.235.240.0/20") else 1)' "$client"; then
  ok "this session comes through IAP ($client)"
else
  bad "this session comes from $client, not IAP (35.235.240.0/20): reconnect with --tunnel-through-iap first"
fi
if command -v tailscale >/dev/null; then
  echo "info: $(tailscale version 2>/dev/null | head -1); serve: $(tailscale serve status 2>&1 | tr '\n' ' ' | cut -c1-160)"
else
  echo "info: tailscale isn't installed"
fi
[ $fail -eq 0 ] || { echo "STOPPED: nothing changed."; exit 1; }
if [ "$apply" != yes ]; then echo "DRY RUN: would reset Serve/Funnel, log out, purge tailscale and its apt source, remove the preview's unit. Add --apply."; exit 0; fi

if command -v tailscale >/dev/null; then
  tailscale serve reset || true
  tailscale funnel reset 2>/dev/null || true
  tailscale logout || true
fi
systemctl disable --now tailscaled 2>/dev/null || true
DEBIAN_FRONTEND=noninteractive apt-get -y purge tailscale 2>/dev/null || true
rm -f /etc/apt/sources.list.d/tailscale.list /usr/share/keyrings/tailscale-archive-keyring.gpg
rm -rf /var/lib/tailscale
apt-get update -qq || true
systemctl disable --now casita-public.service 2>/dev/null || true
rm -f /etc/systemd/system/casita-public.service /usr/local/bin/casita-access-public
rm -rf /etc/casita-public
systemctl daemon-reload
date -u +%Y-%m-%dT%H:%M:%SZ > "$state/tailscale-removed"

echo "== what listens now"
ss -ltnH | awk '{print "  " $4}' | sort -u
command -v tailscale >/dev/null && echo "WARNING: a tailscale command is still on the PATH" || ok "tailscale is gone"
echo "Done. If the node still shows in the Tailscale admin console, delete it there (Machines -> watson -> Remove)."
