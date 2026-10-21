#!/usr/bin/env bash
# The staging guard on watson itself: starts the installed release as the casita user with /etc/casita/casita.env
# and one unsafe override at a time, and checks that each refuses to start. Run on the VM as root. The running
# service isn't touched: each check stops at the guard, before any port is opened or anything is contacted.
#
#   sudo bash check-guard.sh
set -uo pipefail
[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)" >&2; exit 1; }
jar=/opt/casita/current/casita.jar
[ -f "$jar" ] || { echo "no release installed at $jar" >&2; exit 1; }

# casita.env as KEY=VALUE pairs (one per line; JAVA_OPTS is for java, not Spring, so it is left out here).
mapfile -t base < <(grep -E '^[A-Z_]+=' /etc/casita/casita.env | grep -v '^JAVA_OPTS=')

# expect_refused <description> <KEY=VALUE>...: the override(s) on top of casita.env must stop Casita at the guard.
fail=0
expect_refused() {
  local what="$1"; shift
  out="$(cd /var/lib/casita && timeout 60 sudo -u casita env -i PATH=/usr/bin:/bin "${base[@]}" "$@" \
    /usr/bin/java -Xmx128m -jar "$jar" --server.port=0 2>&1)"
  if grep -q "Staging guard: Casita won't start" <<<"$out"; then echo "ok:   refused: $what"
  else echo "FAIL: started past the guard: $what"; fail=1; fi
}

expect_refused "deployment unset (this is a Compute Engine VM)" CASITA_DEPLOYMENT=
expect_refused "deployment local" CASITA_DEPLOYMENT=local
expect_refused "the real doors" CASITA_DOOR=meross
expect_refused "live commands" CASITA_MEROSS_COMMANDMODE=live
expect_refused "dry-run commands" CASITA_MEROSS_COMMANDMODE=dry-run
expect_refused "Meross credentials" CASITA_MEROSS_EMAIL=placeholder@example.com
expect_refused "live-test credentials" MEROSS_EMAIL=placeholder@example.com
expect_refused "the placeholder tailnet" "CASITA_SECURITY_RPID=watson.<TAILNET>.ts.net" "CASITA_SECURITY_ALLOWEDORIGINS=https://watson.<TAILNET>.ts.net"
expect_refused "a file store" CASITA_STORAGE_TYPE=file
expect_refused "a Firestore emulator" FIRESTORE_EMULATOR_HOST=127.0.0.1:18085
expect_refused "the dev profile" SPRING_PROFILES_ACTIVE=dev
expect_refused "JSON settings turning on the real doors" 'SPRING_APPLICATION_JSON={"casita":{"door":"meross"}}'
[ $fail -eq 0 ] && echo "Staging guard on watson: PASS" || echo "Staging guard on watson: FAIL"
exit $fail
