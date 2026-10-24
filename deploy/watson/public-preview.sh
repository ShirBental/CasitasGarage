#!/usr/bin/env bash
# The public preview: the new release as a second, temporary Casita on watson (docs/PUBLIC_HTTPS.md, step 4). Run on
# the VM as root. casita.service and its access path are not touched.
#
#   sudo bash public-preview.sh <release dir> <sha256 of casita.jar> --host watsonbental.duckdns.org           # dry run
#   sudo bash public-preview.sh <release dir> <sha256 of casita.jar> --host watsonbental.duckdns.org --apply
#   sudo bash public-preview.sh --stop                     # stops and disables it (cutover-public.sh does this too)
#
# The preview runs as staging, so the guard holds it to simulated doors, commands off and no Meross credentials. It
# listens on 127.0.0.1:8081 (Caddy forwards the public name there until cutover), signs in with passkeys bound to the
# public name and browser keys, and shares casita.service's data directory and Firestore database: members and
# permissions are the same, and every enrollment made here is still there after cutover. Its settings come from
# /etc/casita/casita.env (family, project, JAVA_OPTS); nothing secret is copied (there is nothing secret there).
#
# Invites for the public name: casita-access-public invite <member> "<device label>" (installed by --apply).
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)" >&2; exit 2; }
unit=casita-public.service
port=8081

if [ "${1:-}" = "--stop" ]; then
  systemctl disable --now "$unit" 2>/dev/null || true
  echo "$unit stopped and disabled (its files stay in /opt/casita-public and /etc/casita-public)"
  exit 0
fi

[ $# -ge 4 ] || { echo "usage: public-preview.sh <release dir> <sha256> --host <name> [--apply] | --stop" >&2; exit 2; }
src="$1"; want="$2"; shift 2
host=""; apply=no
while [ $# -gt 0 ]; do
  case "$1" in --host) host="${2:-}"; shift 2 ;; --apply) apply=yes; shift ;; *) echo "unknown argument $1" >&2; exit 2 ;; esac
done
[[ "$host" =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$ ]] || { echo "--host must be a lower-case DNS name" >&2; exit 2; }
for f in casita.jar casita-access RELEASE deploy/casita-public.service; do [ -f "$src/$f" ] || { echo "$src/$f is missing" >&2; exit 2; }; done
got="$(sha256sum "$src/casita.jar" | cut -d' ' -f1)"
[ "$got" = "$want" ] || { echo "casita.jar sha256 is $got, expected $want: nothing changed" >&2; exit 2; }
id="$(cut -d' ' -f1 < "$src/RELEASE")"
case "$id" in *[!0-9a-f]*|"") echo "RELEASE must start with the commit id" >&2; exit 2 ;; esac

main_env=/etc/casita/casita.env
[ -r "$main_env" ] || { echo "$main_env is missing: the preview takes the family and project from it" >&2; exit 2; }
value() { sed -n "s/^$1=//p" "$main_env" | tail -1; }
family="$(value CASITA_SECURITY_FAMILY)"; project="$(value CASITA_STORAGE_FIRESTORE_PROJECTID)"; java_opts="$(value JAVA_OPTS)"
[ -n "$family" ] && [ -n "$project" ] || { echo "$main_env lacks CASITA_SECURITY_FAMILY or CASITA_STORAGE_FIRESTORE_PROJECTID" >&2; exit 2; }
if ss -ltnH "sport = :$port" | grep -q . && ! systemctl is-active --quiet "$unit"; then
  echo "something else already listens on port $port: nothing changed" >&2; exit 2
fi

env_text="# /etc/casita-public/casita.env: the public preview (public-preview.sh). Temporary; removed after the move.
CASITA_DEPLOYMENT=staging
CASITA_DOOR=simulated
CASITA_MEROSS_COMMANDMODE=off
CASITA_SECURITY_RPID=$host
CASITA_SECURITY_ALLOWEDORIGINS=https://$host
CASITA_SECURITY_FAMILY=$family
CASITA_SECURITY_DEVICEIDENTITY=browser
CASITA_SECURITY_DATADIR=/var/lib/casita
CASITA_STORAGE_TYPE=firestore
CASITA_STORAGE_FIRESTORE_PROJECTID=$project
SERVER_SERVLET_SESSION_COOKIE_SECURE=true
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=$port
JAVA_OPTS=${java_opts:--Xmx128m -XX:+UseSerialGC}"

echo "Release $id ($(cut -d' ' -f2- < "$src/RELEASE"))"
echo "Would write /etc/casita-public/casita.env:"; sed 's/^/    /' <<<"$env_text"
avail="$(free -m | awk 'NR==2 {print $7}')"
echo "Memory available now: ${avail} MiB (the preview needs about 200 MiB while it runs next to casita.service)"
if [ "$apply" != yes ]; then echo "DRY RUN: nothing changed. Add --apply."; exit 0; fi
[ "${avail:-0}" -ge 220 ] || { echo "less than 220 MiB available: not starting a second Casita" >&2; exit 1; }

dest="/opt/casita-public/releases/$id"
install -d -m 0755 /opt/casita-public /opt/casita-public/releases "$dest"
install -m 0644 "$src/casita.jar" "$dest/casita.jar"
install -m 0755 "$src/casita-access" "$dest/casita-access"
install -m 0644 "$src/RELEASE" "$dest/RELEASE"
[ -f "$src/PUBLIC_HTTPS.md" ] && install -m 0644 "$src/PUBLIC_HTTPS.md" "$dest/PUBLIC_HTTPS.md"
echo "$want  casita.jar" > "$dest/casita.jar.sha256"
ln -sfn "$dest" /opt/casita-public/current.new && mv -T /opt/casita-public/current.new /opt/casita-public/current
install -d -o root -g casita -m 0750 /etc/casita-public
printf '%s\n' "$env_text" > /etc/casita-public/casita.env.new
chown root:casita /etc/casita-public/casita.env.new && chmod 0640 /etc/casita-public/casita.env.new
mv -f /etc/casita-public/casita.env.new /etc/casita-public/casita.env
install -m 0644 "$src/deploy/casita-public.service" /etc/systemd/system/$unit
cat > /usr/local/bin/casita-access-public <<'SH'
#!/bin/sh
# casita-access for the public preview (127.0.0.1:8081): invites from here are links on the public name.
set -eu
exec sudo -u casita env CASITA_DATA=/var/lib/casita CASITA_URL=http://127.0.0.1:8081 /bin/sh /opt/casita-public/current/casita-access "$@"
SH
chmod 0755 /usr/local/bin/casita-access-public
systemctl daemon-reload
systemctl reset-failed "$unit" 2>/dev/null || true
systemctl enable "$unit" >/dev/null
systemctl restart "$unit"

echo "== waiting for the preview on 127.0.0.1:$port"
deadline=$(( $(date +%s) + 120 ))
until [ "$(curl -s --max-time 2 --noproxy '*' "http://127.0.0.1:$port/healthz")" = "ok" ]; do
  if [ "$(date +%s)" -ge "$deadline" ] || ! systemctl is-active --quiet "$unit"; then
    echo "FAIL: the preview didn't come up; stopping it. journalctl -u $unit -n 80 says why." >&2
    systemctl disable --now "$unit" >/dev/null 2>&1 || true
    exit 1
  fi
  sleep 2
done
since="$(systemctl show -p ActiveEnterTimestamp --value "$unit")"
guard="$(journalctl -u "$unit" --since "${since:-today}" --no-pager -o cat | grep 'staging guard: ok' | tail -1 || true)"
case "$guard" in
  *"door=simulated command_mode=off meross_credentials=absent storage=firestore rp_id=$host"*) echo "ok: ${guard##*staging guard: }" ;;
  *) echo "FAIL: no matching 'staging guard: ok' line for rp_id=$host; stopping the preview" >&2; systemctl disable --now "$unit" >/dev/null 2>&1; exit 1 ;;
esac
ss -ltnH "sport = :$port" | awk '{print "ok: listening on " $4}'
echo "Preview up. Point Caddy at it: sudo bash public-https.sh --host $host --upstream 127.0.0.1:$port --apply"
