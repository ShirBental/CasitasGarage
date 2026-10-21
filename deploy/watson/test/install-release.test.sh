#!/usr/bin/env bash
# Tests for deploy/watson/install-release.sh on a scratch filesystem, with a fake systemctl and a fake casita-health:
# nothing here touches a real service, VM, cloud or door. Needs GNU coreutils (mv -T, readlink -f), like watson.
#
#   bash deploy/watson/test/install-release.test.sh
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
script="$here/../install-release.sh"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
mv --version >/dev/null 2>&1 || { echo "skipped: needs GNU coreutils (mv -T), as on watson"; exit 0; }

# ---------- fakes ----------
mkdir -p "$work/bin"
cat > "$work/bin/systemctl" <<'FAKE'
#!/usr/bin/env bash
# Logs every call. restart fails for the release ids in FAKE_RESTART_FAIL; enable/disable are remembered.
echo "systemctl $*" >> "$FAKE_LOG"
cur="$(cut -d' ' -f1 < "$CASITA_TEST_ROOT/opt/casita/current/RELEASE" 2>/dev/null || echo none)"
case "$1" in
  restart) case " $FAKE_RESTART_FAIL " in *" $cur "*) echo "Job for casita.service failed." >&2; exit 1 ;; esac ;;
  enable) echo enabled > "$CASITA_TEST_ROOT/unit-state" ;;
  disable) echo disabled > "$CASITA_TEST_ROOT/unit-state" ;;
esac
exit 0
FAKE
chmod +x "$work/bin/systemctl"
cat > "$work/health" <<'FAKE'
#!/usr/bin/env bash
# Healthy only when the release that current points at is listed in FAKE_HEALTHY.
cur="$(cut -d' ' -f1 < "$CASITA_TEST_ROOT/opt/casita/current/RELEASE" 2>/dev/null || echo none)"
echo "health $cur $*" >> "$FAKE_LOG"
case " $FAKE_HEALTHY " in *" $cur "*) exit 0 ;; *) exit 1 ;; esac
FAKE

GOOD_ENV='# /etc/casita/casita.env (test)
CASITA_DEPLOYMENT=staging
CASITA_DOOR=simulated
CASITA_MEROSS_COMMANDMODE=off
CASITA_SECURITY_RPID=watson.example-tailnet.ts.net
JAVA_OPTS=-Xmx128m -XX:+UseSerialGC'

# fresh: a watson as bootstrap-vm.sh leaves it (no release yet, service enabled), with some data in place.
fresh() {
  root="$work/root"; rm -rf "$root"
  mkdir -p "$root/opt/casita/releases" "$root/etc/casita" "$root/usr/local/sbin" "$root/var/lib/casita/invites"
  printf '%s\n' "$GOOD_ENV" > "$root/etc/casita/casita.env"
  install -m 0755 "$work/health" "$root/usr/local/sbin/casita-health"
  echo "secret" > "$root/var/lib/casita/admin.secret"; echo "link" > "$root/var/lib/casita/invites/i1.txt"
  echo enabled > "$root/unit-state"
  data_before="$(cd "$root/var/lib/casita" && find . -type f -exec sha256sum {} + | sort)"
  healthy=""; restart_fail=""
}
# release ID: a release directory as deploy-release.sh makes it; sets $sha.
release() {
  local d="$work/src-$1"; rm -rf "$d"; mkdir -p "$d"
  echo "jar of $1 ${2:-}" > "$d/casita.jar"; echo '#!/bin/sh' > "$d/casita-access"
  echo "$1 2026-10-21T00:00:00Z test release $1" > "$d/RELEASE"
  sha="$(sha256sum "$d/casita.jar" | cut -d' ' -f1)"
}
deploy() {  # deploy ID [SHA]: run the installer; sets $code, $out; appends to $log
  release "$1"; local s="${2:-$sha}"
  out="$(PATH="$work/bin:$PATH" CASITA_TEST_ROOT="$root" FAKE_LOG="$log" FAKE_HEALTHY="$healthy" FAKE_RESTART_FAIL="$restart_fail" \
    bash "$script" "$work/src-$1" "$s" 2>&1)"; code=$?
}

# ---------- asserting ----------
fails=0; passes=0
scenario() { name="$1"; FAILED=no; log="$work/calls.log"; : > "$log"; }
bad() { echo "  FAIL [$name]: $*"; fails=$((fails + 1)); FAILED=yes; }
done_scenario() {
  local data_after; data_after="$(cd "$root/var/lib/casita" && find . -type f -exec sha256sum {} + | sort)"
  [ "$data_after" = "$data_before" ] || bad "/var/lib/casita changed"
  [ "$FAILED" = no ] && { passes=$((passes + 1)); echo "  ok   $name"; }
}
expect_code() { [ "$code" = "$1" ] || bad "exit $code, expected $1: $(echo "$out" | tail -2 | tr '\n' ' ')"; }
expect_out() { grep -qF -- "$1" <<<"$out" || bad "output lacks: $1"; }
link() { readlink "$root/opt/casita/$1" 2>/dev/null | sed 's#.*/##' || true; }
expect_link() { [ "$(link "$1")" = "$2" ] || bad "$1 -> '$(link "$1")', expected '$2'"; }
expect_no_link() { if [ -e "$root/opt/casita/$1" ] || [ -L "$root/opt/casita/$1" ]; then bad "$1 still exists (-> $(link "$1"))"; fi; }
expect_release_kept() { [ -f "$root/opt/casita/releases/$1/casita.jar" ] || bad "release $1 was deleted"; }
expect_no_release() { [ ! -e "$root/opt/casita/releases/$1" ] || bad "release $1 was installed"; }
expect_unit() { [ "$(cat "$root/unit-state")" = "$1" ] || bad "unit is $(cat "$root/unit-state"), expected $1"; }
expect_call() { grep -qx -- "$1" "$log" || bad "no call: $1"; }
expect_no_call() { ! grep -q -- "$1" "$log" || bad "unexpected call: $(grep -- "$1" "$log" | head -1)"; }
expect_calls_in_order() {
  local got want; got="$(grep -E '^(systemctl (restart|stop|disable|enable)|health)' "$log" | sed 's/ --wait 90//')"
  want="$(printf '%s\n' "$@")"
  [ "$got" = "$want" ] || { bad "calls differ"; diff <(echo "$want") <(echo "$got") | sed 's/^/      /'; }
}

echo "install-release.sh, on a scratch filesystem with fake systemctl and health"

scenario "first deployment, healthy: current set, no previous, enabled"
fresh; healthy="aaaa111"; deploy aaaa111
expect_code 0; expect_link current aaaa111; expect_no_link previous; expect_unit enabled
expect_calls_in_order "systemctl restart casita.service" "health aaaa111" "systemctl enable casita.service"
[ "$(cat "$root/opt/casita/releases/aaaa111/casita.jar.sha256")" = "$sha  casita.jar" ] || bad "sha256 file wrong"
done_scenario

scenario "upgrade, healthy: current -> new, previous -> old"
fresh; healthy="aaaa111 bbbb222"; deploy aaaa111; deploy bbbb222
expect_code 0; expect_link current bbbb222; expect_link previous aaaa111; expect_release_kept aaaa111
done_scenario

scenario "first deployment fails its health check: stopped, disabled, current removed, files kept"
fresh; healthy=""; deploy aaaa111
expect_code 1; expect_no_link current; expect_no_link previous; expect_release_kept aaaa111; expect_unit disabled
expect_calls_in_order "systemctl restart casita.service" "health aaaa111" "systemctl stop casita.service" "systemctl disable casita.service"
expect_out "No earlier release to go back to."
done_scenario

scenario "first deployment: systemctl restart fails -> handled as a failure, not an early exit"
fresh; restart_fail="aaaa111"; healthy="aaaa111"; deploy aaaa111
expect_code 1; expect_no_link current; expect_release_kept aaaa111; expect_unit disabled
expect_calls_in_order "systemctl restart casita.service" "systemctl stop casita.service" "systemctl disable casita.service"
expect_out "systemctl restart casita.service failed"
done_scenario

scenario "upgrade fails its health check: back to the running release, previous unchanged"
fresh; healthy="aaaa000 aaaa111"; deploy aaaa000; deploy aaaa111     # previous -> aaaa000, current -> aaaa111
: > "$log"; deploy bbbb222
expect_code 1; expect_link current aaaa111; expect_link previous aaaa000; expect_release_kept bbbb222; expect_unit enabled
expect_calls_in_order "systemctl restart casita.service" "health bbbb222" "systemctl restart casita.service" "health aaaa111"
expect_no_call "systemctl disable"; expect_out "Going back to"
done_scenario

scenario "upgrade: systemctl restart fails -> rolled back"
fresh; healthy="aaaa111 bbbb222"; deploy aaaa111
restart_fail="bbbb222"; : > "$log"; deploy bbbb222
expect_code 1; expect_link current aaaa111; expect_no_link previous; expect_release_kept bbbb222; expect_unit enabled
expect_calls_in_order "systemctl restart casita.service" "systemctl restart casita.service" "health aaaa111"
done_scenario

scenario "upgrade fails and the earlier release fails too: stopped, disabled, current removed, files kept"
fresh; healthy="aaaa111"; deploy aaaa111
healthy=""; : > "$log"; deploy bbbb222
expect_code 1; expect_no_link current; expect_release_kept aaaa111; expect_release_kept bbbb222; expect_unit disabled
expect_calls_in_order "systemctl restart casita.service" "health bbbb222" "systemctl restart casita.service" "health aaaa111" \
  "systemctl stop casita.service" "systemctl disable casita.service"
done_scenario

scenario "after a failed first deployment, a healthy one is enabled again"
fresh; healthy=""; deploy aaaa111; healthy="bbbb222"; : > "$log"; deploy bbbb222
expect_code 0; expect_link current bbbb222; expect_unit enabled; expect_call "systemctl enable casita.service"
done_scenario

scenario "re-installing the running release, which now fails: back to the one before it"
fresh; healthy="aaaa000 aaaa111"; deploy aaaa000; deploy aaaa111
healthy="aaaa000"; : > "$log"; deploy aaaa111
expect_code 1; expect_link current aaaa000; expect_no_link previous; expect_unit enabled
done_scenario

# bad_env DESCRIPTION EXPECTED-TEXT FILE-CONTENT: refused with exit 2, before anything changes.
bad_env() {
  scenario "staging config refused: $1"
  fresh; healthy="aaaa111 bbbb222"; deploy aaaa111
  if [ "$3" = "<missing>" ]; then rm "$root/etc/casita/casita.env"; else printf '%s' "$3" > "$root/etc/casita/casita.env"; fi
  : > "$log"; deploy bbbb222
  expect_code 2; expect_out "$2"; expect_out "nothing was changed"
  expect_link current aaaa111; expect_no_release bbbb222; expect_unit enabled
  [ -s "$log" ] && bad "systemctl or health was called: $(head -1 "$log")"
  done_scenario
}
E='CASITA_DEPLOYMENT=staging
CASITA_DOOR=simulated
CASITA_MEROSS_COMMANDMODE=off
'
bad_env "file missing" "casita.env is missing" "<missing>"
bad_env "CASITA_DOOR missing" "must set CASITA_DOOR exactly once (it does 0 times)" "${E/CASITA_DOOR=simulated
/}"
bad_env "CASITA_DOOR only in a comment" "must set CASITA_DOOR exactly once (it does 0 times)" "${E/CASITA_DOOR=simulated/#CASITA_DOOR=simulated}"
bad_env "CASITA_DOOR=meross" "must say exactly CASITA_DOOR=simulated" "${E/CASITA_DOOR=simulated/CASITA_DOOR=meross}"
bad_env "CASITA_MEROSS_COMMANDMODE=live" "must say exactly CASITA_MEROSS_COMMANDMODE=off" "${E/=off/=live}"
bad_env "CASITA_MEROSS_COMMANDMODE=dry-run" "must say exactly CASITA_MEROSS_COMMANDMODE=off" "${E/=off/=dry-run}"
bad_env "CASITA_DEPLOYMENT=local" "must say exactly CASITA_DEPLOYMENT=staging" "${E/=staging/=local}"
bad_env "CASITA_DEPLOYMENT=Staging" "must say exactly CASITA_DEPLOYMENT=staging" "${E/=staging/=Staging}"
bad_env "duplicated, same value" "must set CASITA_DEPLOYMENT exactly once (it does 2 times)" "${E}CASITA_DEPLOYMENT=staging
"
bad_env "duplicated, overriding later" "must set CASITA_DOOR exactly once (it does 2 times)" "${E}CASITA_DOOR=meross
"
bad_env "quoted value" "must say exactly CASITA_MEROSS_COMMANDMODE=off" "${E/=off/=\"off\"}"
bad_env "trailing space" "must say exactly CASITA_DOOR=simulated" "${E/=simulated/=simulated }"
bad_env "leading space" "must say exactly CASITA_DOOR=simulated" "${E/CASITA_DOOR/  CASITA_DOOR}"
bad_env "spaces around =" "must say exactly CASITA_DOOR=simulated" "${E/CASITA_DOOR=/CASITA_DOOR = }"
bad_env "export prefix" "must say exactly CASITA_DOOR=simulated" "${E/CASITA_DOOR/export CASITA_DOOR}"
bad_env "CRLF line endings" "CR line endings" "$(printf 'CASITA_DEPLOYMENT=staging\r\nCASITA_DOOR=simulated\r\nCASITA_MEROSS_COMMANDMODE=off\r\n')"
bad_env "continuation line" "continuation lines" "${E}JAVA_OPTS=-Xmx128m \\
CASITA_DOOR=meross
"

scenario "the shipped casita.env.example passes the staging check"
fresh; cp "$here/../casita.env.example" "$root/etc/casita/casita.env"; healthy="aaaa111"; deploy aaaa111
expect_code 0; expect_link current aaaa111
done_scenario

# production SETTINGS [MEROSS-ENV] [MODE]: casita.env set for the real doors, and a root-only meross.env.
P='CASITA_DEPLOYMENT=production
CASITA_DOOR=meross
CASITA_MEROSS_COMMANDMODE=off
'
M='CASITA_MEROSS_EMAIL=placeholder@example.com
CASITA_MEROSS_PASSWORD=placeholder
'
production() {
  printf '%s' "$1" > "$root/etc/casita/casita.env"
  if [ "${2-x}" != "<missing>" ]; then printf '%s' "${2-$M}" > "$root/etc/casita/meross.env"; chmod "${3:-600}" "$root/etc/casita/meross.env"; fi
}
for mode in off dry-run live; do
  scenario "production, command mode $mode, account in a root-only meross.env: installed"
  fresh; healthy="aaaa111"; production "${P/=off/=$mode}"; deploy aaaa111
  expect_code 0; expect_link current aaaa111
  done_scenario
done
bad_prod() {
  scenario "production refused: $1"
  fresh; healthy="aaaa111 bbbb222"; deploy aaaa111
  production "$3" "${4-$M}" "${5:-600}"; [ "${4-}" = "<missing>" ] && rm -f "$root/etc/casita/meross.env"
  : > "$log"; deploy bbbb222
  expect_code 2; expect_out "$2"; expect_out "nothing was changed"; expect_link current aaaa111; expect_no_release bbbb222
  [ -s "$log" ] && bad "systemctl or health was called"
  done_scenario
}
bad_prod "simulated door" "must say exactly CASITA_DOOR=meross in production" "${P/=meross/=simulated}"
bad_prod "unknown command mode" "CASITA_MEROSS_COMMANDMODE=off, dry-run or live" "${P/=off/=on}"
bad_prod "quoted command mode" "CASITA_MEROSS_COMMANDMODE=off, dry-run or live" "${P/=off/=\"live\"}"
bad_prod "no meross.env" "meross.env is missing" "$P" "<missing>"
bad_prod "meross.env readable by others" "must be owned by root with mode 0600" "$P" "$M" 644
bad_prod "meross.env group-readable" "must be owned by root with mode 0600" "$P" "$M" 640
bad_prod "no password" "must set CASITA_MEROSS_PASSWORD exactly once (it does 0 times)" "$P" "CASITA_MEROSS_EMAIL=a@example.com
"
bad_prod "empty password" "must set CASITA_MEROSS_PASSWORD=<value>" "$P" "CASITA_MEROSS_EMAIL=a@example.com
CASITA_MEROSS_PASSWORD=
"
bad_prod "two emails" "must set CASITA_MEROSS_EMAIL exactly once (it does 2 times)" "$P" "${M}CASITA_MEROSS_EMAIL=b@example.com
"
bad_prod "staging door settings in production" "must say exactly CASITA_DOOR=meross in production" "CASITA_DEPLOYMENT=production
CASITA_DOOR=simulated
CASITA_MEROSS_COMMANDMODE=off
"
bad_prod "staging with the real doors" "must say exactly CASITA_DOOR=simulated" "CASITA_DEPLOYMENT=staging
CASITA_DOOR=meross
CASITA_MEROSS_COMMANDMODE=live
"

scenario "SHA-256 mismatch: refused, nothing changed"
fresh; healthy="aaaa111 bbbb222"; deploy aaaa111; : > "$log"
deploy bbbb222 0000000000000000000000000000000000000000000000000000000000000000
expect_code 2; expect_out "sha256 is"; expect_link current aaaa111; expect_no_release bbbb222; [ -s "$log" ] && bad "called systemctl"
done_scenario

scenario "same release id with a different jar: refused, the installed release is never replaced"
fresh; healthy="aaaa111"; deploy aaaa111; first="$(sha256sum "$root/opt/casita/releases/aaaa111/casita.jar")"
release aaaa111 rebuilt; : > "$log"
out="$(PATH="$work/bin:$PATH" CASITA_TEST_ROOT="$root" FAKE_LOG="$log" FAKE_HEALTHY="$healthy" FAKE_RESTART_FAIL="" bash "$script" "$work/src-aaaa111" "$sha" 2>&1)"; code=$?
expect_code 2; expect_out "already installed with a different jar"
[ "$(sha256sum "$root/opt/casita/releases/aaaa111/casita.jar")" = "$first" ] || bad "jar replaced"
done_scenario

scenario "usage: wrong number of arguments refused"
fresh; out="$(CASITA_TEST_ROOT="$root" bash "$script" only-one 2>&1)"; code=$?
expect_code 2; expect_out "usage"
done_scenario

echo
echo "$passes passed, $fails failed"
[ $fails -eq 0 ]
