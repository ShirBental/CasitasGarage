#!/usr/bin/env bash
# Watch Ford's and Toyota's readings from watson, read-only, for measuring travel times and sensor lags while a person
# works the doors with the wall button or remote. Uses the service's own settings (/etc/casita/casita.env and the
# account in /etc/casita/meross.env) and the installed release. Run on watson as root:
#
#   sudo bash door-watch.sh [minutes, default 10] [seconds between readings, default 2]
#
# Nothing is sent to the doors (home.casita.meross.DoorWatch reads only). casita.service keeps running.
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)" >&2; exit 1; }
jar=/opt/casita/current/casita.jar
[ -f "$jar" ] || { echo "no release installed at $jar" >&2; exit 1; }
[ -f /etc/casita/meross.env ] || { echo "/etc/casita/meross.env (the Meross account) is missing" >&2; exit 1; }
vars=()
while IFS= read -r line; do vars+=("$line"); done < <(cat /etc/casita/casita.env /etc/casita/meross.env \
  | grep -E '^CASITA_MEROSS_[A-Z_]+=' || true)
cd /var/lib/casita
exec sudo -u casita env -i PATH=/usr/bin:/bin ${vars[@]+"${vars[@]}"} \
  /usr/bin/java -Xmx96m -XX:+UseSerialGC -cp "$jar" -Dloader.main=home.casita.meross.DoorWatch \
  org.springframework.boot.loader.launch.PropertiesLauncher "${1:-10}" "${2:-2}"
