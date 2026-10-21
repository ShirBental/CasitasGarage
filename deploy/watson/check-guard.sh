#!/usr/bin/env bash
# The guard on watson itself: starts the installed release as the casita user with /etc/casita/casita.env (and, in
# production, /etc/casita/meross.env) plus one unsafe override at a time, and checks that each refuses to start. Run on
# the VM as root. The running service isn't touched: each check stops at the guard, before any port is opened or
# anything is contacted. In production every check also sets CASITA_MEROSS_COMMANDMODE=off unless it is testing that
# setting, so even a start that wrongly got past the guard couldn't move a door (and it is stopped after 60 s).
#
#   sudo bash check-guard.sh
set -uo pipefail
[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)" >&2; exit 1; }
jar=/opt/casita/current/casita.jar
[ -f "$jar" ] || { echo "no release installed at $jar" >&2; exit 1; }

# The service's settings as KEY=VALUE pairs (JAVA_OPTS is for java, not Spring, so it is left out here).
base=()
while IFS= read -r line; do base+=("$line"); done < <(cat /etc/casita/casita.env /etc/casita/meross.env 2>/dev/null \
  | grep -E '^[A-Z_]+=' | grep -v '^JAVA_OPTS=')
deployment="$(sed -n 's/^CASITA_DEPLOYMENT=//p' /etc/casita/casita.env)"

# expect_refused <description> <KEY=VALUE>...: the override(s) on top of the settings must stop Casita at the guard.
fail=0
expect_refused() {
  local what="$1"; shift
  local safety=()
  if [ "$deployment" = production ] && ! printf '%s\n' "$@" | grep -q '^CASITA_MEROSS_COMMANDMODE='; then
    safety=(CASITA_MEROSS_COMMANDMODE=off)
  fi
  out="$(cd /var/lib/casita && timeout 60 sudo -u casita env -i PATH=/usr/bin:/bin "${base[@]}" "$@" ${safety[@]+"${safety[@]}"} \
    /usr/bin/java -Xmx128m -jar "$jar" --server.port=0 2>&1)"
  if grep -q "Staging guard: Casita won't start" <<<"$out"; then echo "ok:   refused: $what"
  else echo "FAIL: started past the guard: $what"; fail=1; fi
}

expect_refused "deployment unset (this is a Compute Engine VM)" CASITA_DEPLOYMENT=
expect_refused "deployment local" CASITA_DEPLOYMENT=local
expect_refused "the placeholder tailnet" "CASITA_SECURITY_RPID=watson.<TAILNET>.ts.net" "CASITA_SECURITY_ALLOWEDORIGINS=https://watson.<TAILNET>.ts.net"
expect_refused "a file store" CASITA_STORAGE_TYPE=file
expect_refused "a Firestore emulator" FIRESTORE_EMULATOR_HOST=127.0.0.1:18085
expect_refused "the dev profile" SPRING_PROFILES_ACTIVE=dev
expect_refused "live-test credentials" MEROSS_EMAIL=placeholder@example.com
if [ "$deployment" = production ]; then
  echo "(production: real doors, as set up for them)"
  expect_refused "the practice doors in production" CASITA_DOOR=simulated
  expect_refused "an unknown command mode" CASITA_MEROSS_COMMANDMODE=on
  expect_refused "channel 3 as a door" CASITA_MEROSS_DOORS_3=Gate
  expect_refused "channel 3 as a command channel" CASITA_MEROSS_COMMANDCHANNELS=1,2,3
  expect_refused "a public opener address" CASITA_MEROSS_TRANSPORT=local CASITA_MEROSS_LOCALURL=http://8.8.8.8
  expect_refused "no account password" CASITA_MEROSS_PASSWORD=
else
  echo "(staging: simulation only)"
  expect_refused "the real doors" CASITA_DOOR=meross
  expect_refused "live commands" CASITA_MEROSS_COMMANDMODE=live
  expect_refused "dry-run commands" CASITA_MEROSS_COMMANDMODE=dry-run
  expect_refused "Meross credentials" CASITA_MEROSS_EMAIL=placeholder@example.com
  expect_refused "JSON settings turning on the real doors" 'SPRING_APPLICATION_JSON={"casita":{"door":"meross"}}'
fi
[ $fail -eq 0 ] && echo "Guard on watson ($deployment): PASS" || echo "Guard on watson ($deployment): FAIL"
exit $fail
