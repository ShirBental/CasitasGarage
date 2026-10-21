#!/usr/bin/env bash
# Install a Casita release on watson and switch to it; if it doesn't come up healthy, go back. Run on the VM as root
# (installed as casita-install-release by bootstrap-vm.sh):
#
#   sudo casita-install-release <dir with casita.jar, casita-access, RELEASE> <expected sha256 of casita.jar>
#
# The release directory comes from deploy-release.sh. Releases live in /opt/casita/releases/<id>; /opt/casita/current
# points at the running one and /opt/casita/previous at the one before. Nothing in /var/lib/casita is touched, and no
# release directory is ever deleted.
#
# Before anything changes: the jar's SHA-256 must match, and /etc/casita/casita.env must set, each exactly once and
# exactly so, either staging (CASITA_DEPLOYMENT=staging, CASITA_DOOR=simulated, CASITA_MEROSS_COMMANDMODE=off) or
# production (CASITA_DEPLOYMENT=production, CASITA_DOOR=meross, CASITA_MEROSS_COMMANDMODE=off|dry-run|live, with the
# Meross account in /etc/casita/meross.env, root-only 0600).
# Then the release is installed, current is switched, and casita.service restarted. A failed restart counts as a
# failed health check. On failure:
#   - with an earlier release to go back to: current points at it again, previous stays as it was, and Casita is
#     restarted on it;
#   - on the first deployment (nothing to go back to), or if going back fails too: casita.service is stopped and
#     disabled (so nothing starts it at boot) and the current link to the failed release is removed.
#
# Exit status: 0 = installed and healthy; 1 = failed (and rolled back, or stopped); 2 = refused (nothing changed).
set -euo pipefail

# Tests only: CASITA_TEST_ROOT puts every path under a scratch directory. sudo's env_reset never passes it through,
# so `sudo casita-install-release` always works on the real paths.
root="${CASITA_TEST_ROOT:-}"
opt="$root/opt/casita"
env_file="$root/etc/casita/casita.env"
meross_env="$root/etc/casita/meross.env"
health="$root/usr/local/sbin/casita-health"
unit=casita.service

refuse() { echo "casita-install-release: $*: nothing was changed" >&2; exit 2; }
[ $# -eq 2 ] || refuse "usage: casita-install-release <release dir> <sha256 of casita.jar>"
src="$1"; want="$2"
[ -n "$root" ] || [ "$(id -u)" -eq 0 ] || refuse "run as root (sudo)"

# ---------- checks: nothing has changed yet ----------
for f in casita.jar casita-access RELEASE; do [ -f "$src/$f" ] || refuse "$src/$f is missing"; done
got="$(sha256sum "$src/casita.jar" | cut -d' ' -f1)"
[ "$got" = "$want" ] || refuse "casita.jar sha256 is $got, expected $want"
id="$(cut -d' ' -f1 < "$src/RELEASE")"
case "$id" in *[!0-9a-f]*|"") refuse "RELEASE must start with the commit id" ;; esac
dest="$opt/releases/$id"
if [ -f "$dest/casita.jar.sha256" ] && [ "$(cut -d' ' -f1 < "$dest/casita.jar.sha256")" != "$want" ]; then
  refuse "release $id is already installed with a different jar; a release is never replaced"
fi

# The settings systemd will pass to Casita: each must appear exactly once, exactly as below (no quotes, spaces, CR or
# continuation lines, which systemd would read differently). The app's own guard checks them again, and more, at start.
#   staging:    CASITA_DEPLOYMENT=staging, CASITA_DOOR=simulated, CASITA_MEROSS_COMMANDMODE=off
#   production: CASITA_DEPLOYMENT=production, CASITA_DOOR=meross, CASITA_MEROSS_COMMANDMODE=off|dry-run|live, and the
#               Meross account in /etc/casita/meross.env (root only, 0600: systemd reads it as root)
plain_file() {  # plain_file FILE: present, no CR, no continuation lines
  [ -f "$1" ] || refuse "$1 is missing"
  if grep -q $'\r' "$1"; then refuse "$1 has CR line endings"; fi
  if grep -qE '\\[[:space:]]*$' "$1"; then refuse "$1 has continuation lines (a line ending in \\)"; fi
}
# setting FILE KEY: the one line that sets KEY (refused unless exactly one), however it is spaced or prefixed.
setting() {
  local lines count
  lines="$(grep -E "^[[:space:]]*(export[[:space:]]+)?${2}[[:space:]]*=" "$1" || true)"
  count="$(printf '%s' "$lines" | grep -c . || true)"
  [ "$count" -eq 1 ] || refuse "$1 must set $2 exactly once (it does $count times)"
  printf '%s' "$lines"
}
plain_file "$env_file"
deployment="$(setting "$env_file" CASITA_DEPLOYMENT)" || exit 2
door="$(setting "$env_file" CASITA_DOOR)" || exit 2
mode="$(setting "$env_file" CASITA_MEROSS_COMMANDMODE)" || exit 2
case "$deployment" in
  CASITA_DEPLOYMENT=staging)
    [ "$door" = CASITA_DOOR=simulated ] || refuse "$env_file must say exactly CASITA_DOOR=simulated (it says: $door)"
    [ "$mode" = CASITA_MEROSS_COMMANDMODE=off ] || refuse "$env_file must say exactly CASITA_MEROSS_COMMANDMODE=off (it says: $mode)"
    ;;
  CASITA_DEPLOYMENT=production)
    [ "$door" = CASITA_DOOR=meross ] || refuse "$env_file must say exactly CASITA_DOOR=meross in production (it says: $door)"
    case "$mode" in CASITA_MEROSS_COMMANDMODE=off|CASITA_MEROSS_COMMANDMODE=dry-run|CASITA_MEROSS_COMMANDMODE=live) ;;
      *) refuse "$env_file must say exactly CASITA_MEROSS_COMMANDMODE=off, dry-run or live in production (it says: $mode)" ;;
    esac
    plain_file "$meross_env"
    owner_uid=0; [ -n "$root" ] && owner_uid="$(id -u)"
    [ "$(stat -c '%u %a' "$meross_env")" = "$owner_uid 600" ] || refuse "$meross_env must be owned by root with mode 0600"
    for k in CASITA_MEROSS_EMAIL CASITA_MEROSS_PASSWORD; do
      line="$(setting "$meross_env" "$k")" || exit 2
      case "$line" in "$k="?*) ;; *) refuse "$meross_env must set $k=<value>" ;; esac
    done
    ;;
  *) refuse "$env_file must say exactly CASITA_DEPLOYMENT=staging or CASITA_DEPLOYMENT=production (it says: $deployment)" ;;
esac

# ---------- install and switch ----------
install -d -m 0755 "$dest" "$dest/docs"
install -m 0644 "$src/casita.jar" "$dest/casita.jar"
install -m 0755 "$src/casita-access" "$dest/casita-access"
install -m 0644 "$src/RELEASE" "$dest/RELEASE"
if [ -f "$src/WATSON_DEPLOYMENT_READINESS.md" ]; then install -m 0644 "$src/WATSON_DEPLOYMENT_READINESS.md" "$dest/docs/"; fi
echo "$want  casita.jar" > "$dest/casita.jar.sha256"

point() { ln -sfn "$2" "$opt/$1.new" && mv -T "$opt/$1.new" "$opt/$1"; }   # atomic symlink switch
# readlink -e: empty unless the link and its target exist (-f would name a current that isn't there yet).
old="$(readlink -e "$opt/current" 2>/dev/null || true)"
prev_before="$(readlink -e "$opt/previous" 2>/dev/null || true)"
# What to go back to if this release fails: the one running now, or (when re-installing it) the one before it.
back=""
if [ -n "$old" ] && [ "$old" != "$dest" ] && [ -f "$old/casita.jar" ]; then back="$old"
elif [ -n "$prev_before" ] && [ "$prev_before" != "$dest" ] && [ -f "$prev_before/casita.jar" ]; then back="$prev_before"; fi

if [ -n "$old" ] && [ "$old" != "$dest" ]; then point previous "$old"; fi
point current "$dest"
echo "current -> $dest (previous -> $(readlink -e "$opt/previous" 2>/dev/null || echo none))"

# start_and_check: restart Casita on whatever current points at and wait for health. Never exits the script.
start_and_check() {
  systemctl reset-failed "$unit" >/dev/null 2>&1 || true
  if ! systemctl restart "$unit"; then echo "systemctl restart $unit failed" >&2; return 1; fi
  "$health" --wait 90
}

# stand_down: nothing healthy to run. Stop Casita, keep it from starting at boot, and drop the current link.
stand_down() {
  systemctl stop "$unit" || true
  systemctl disable "$unit" || true
  rm -f "$opt/current"
  echo "casita.service is stopped and disabled, and /opt/casita/current is removed. Release files and data are kept." >&2
  echo "Fix the release and install it again; it is enabled again once a release comes up healthy." >&2
}

if start_and_check; then
  # Enabled again after a failed first deployment disabled it. Starting at boot is part of being deployed.
  systemctl enable "$unit" || echo "warning: release $id is up, but systemctl enable $unit failed: it may not start at boot" >&2
  echo "Release $id is up."
  exit 0
fi

echo "Release $id did not come up healthy." >&2
if [ -z "$back" ]; then
  echo "No earlier release to go back to." >&2
  stand_down
  exit 1
fi
echo "Going back to $back" >&2
point current "$back"
# previous stays as it was before this install: never pointing at the release that just failed.
if [ -n "$prev_before" ] && [ "$prev_before" != "$dest" ] && [ "$prev_before" != "$back" ]; then point previous "$prev_before"
else rm -f "$opt/previous"; fi
if start_and_check; then
  echo "Back on $(cut -d' ' -f1 < "$back/RELEASE"); release $id is kept in $dest but not running." >&2
else
  echo "The earlier release didn't come up healthy either." >&2
  stand_down
fi
exit 1
