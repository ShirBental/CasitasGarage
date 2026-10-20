#!/bin/bash
# Usage: startup.sh <name> <store: file|firestore> <runs> -- <jvm flags...>   (Firestore: emulator at $EMU, fresh project per run)
set +u
B=$(cd "$(dirname "$0")" && pwd); S=$(dirname "$B")
NAME=$1 STORE=$2 RUNS=$3; shift 3; [ "$1" = "--" ] && shift
CP="$S/bench-src/target/test-classes:$S/bench-src/target/classes:$(cat $S/cp.txt)"
OUT=$B/results/startup-$NAME; rm -rf "$OUT"; mkdir -p "$OUT"; echo "$STORE | $*" > "$OUT/flags.txt"
for i in $(seq 1 $RUNS); do
  rm -rf "$OUT/data"; mkdir -p "$OUT/data"
  STOREARGS=(--casita.storage.type=file)
  if [ "$STORE" = firestore ]; then
    PROJECT=startup-$i-$(date +%s); java -cp "$CP" "$B/Provision.java" "$EMU" "$PROJECT" > /dev/null 2>&1
    STOREARGS=(--casita.storage.type=firestore --casita.storage.firestore.project-id=$PROJECT --casita.storage.firestore.emulator-host=$EMU)
  fi
  T0=$(python3 -c 'import time;print(time.time())')
  java "$@" -jar $S/bench-src/target/casitas-garage-0.1.0-SNAPSHOT.jar --server.port=18080 --casita.door=simulated "${STOREARGS[@]}" \
    --casita.security.rp-id=casita.test --casita.security.allowed-origins=https://casita.test --casita.security.family=shir,hubby \
    --casita.security.data-dir=$OUT/data --casita.security.tailscale-cli=$B/fake-tailscale > "$OUT/app-$i.log" 2>&1 &
  PID=$!
  until curl -s -o /dev/null http://127.0.0.1:18080/healthz; do sleep 0.02; kill -0 $PID 2>/dev/null || { echo "app died"; exit 1; }; done
  T1=$(python3 -c 'import time;print(time.time())')
  sleep 10
  echo "run=$i healthz_s=$(python3 -c "print(round($T1-$T0,3))") $(grep -o 'Started CasitaApplication in [0-9.]* seconds' "$OUT/app-$i.log") rss_kb_10s=$(ps -o rss= -p $PID) cpu=$(ps -o time= -p $PID)" >> "$OUT/startup.txt"
  kill $PID; wait $PID 2>/dev/null
done
