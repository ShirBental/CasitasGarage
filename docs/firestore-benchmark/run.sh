#!/bin/bash
# Usage: run.sh <name> <store: file|firestore> <duration_s> <poll_ms> <cmd_every_s> <signin_every_s> <jvm flags...>
# Starts Casita (the integration build in $S/bench-src: simulated doors, tailscale device identity with a fake whois) on
# 127.0.0.1:18080, samples its RSS/CPU every 5 s, runs Driver, then records heap/NMT and stops it. With 'firestore', the
# store is the Firestore emulator at $EMU (127.0.0.1:18085), in a fresh project per run. Never port 8080.
set -u
B=$(cd "$(dirname "$0")" && pwd); S=$(dirname "$B")
NAME=$1 STORE=$2 DUR=$3 POLL=$4 CMD=$5 SIGNIN=$6; shift 6
OUT=$B/results/$NAME; rm -rf "$OUT"; mkdir -p "$OUT/data"
JAR=$S/bench-src/target/casitas-garage-0.1.0-SNAPSHOT.jar
CP="$S/bench-src/target/test-classes:$S/bench-src/target/classes:$(cat $S/cp.txt)"
echo "$@" > "$OUT/jvm-flags.txt"
STOREARGS=(--casita.storage.type=file)
if [ "$STORE" = firestore ]; then
  PROJECT=bench-$NAME-$(date +%s)
  java -cp "$CP" "$B/Provision.java" "$EMU" "$PROJECT" > "$OUT/provision.log" 2>&1 || { echo "provision failed"; exit 1; }
  STOREARGS=(--casita.storage.type=firestore --casita.storage.firestore.project-id=$PROJECT --casita.storage.firestore.emulator-host=$EMU)
fi
T0=$(python3 -c 'import time;print(time.time())')
java "$@" -XX:NativeMemoryTracking=summary -Xlog:gc*:file=$OUT/gc.log:uptime -jar "$JAR" \
  --server.port=18080 --casita.door=simulated "${STOREARGS[@]}" \
  --casita.security.rp-id=casita.test --casita.security.allowed-origins=https://casita.test \
  --casita.security.family=shir,hubby --casita.security.data-dir=$OUT/data \
  --casita.security.tailscale-cli=$B/fake-tailscale > "$OUT/app.log" 2>&1 &
PID=$!
until curl -s -o /dev/null http://127.0.0.1:18080/healthz; do sleep 0.05; kill -0 $PID 2>/dev/null || { echo "app died"; exit 1; }; done
T1=$(python3 -c 'import time;print(time.time())')
python3 -c "print('wall_to_first_healthz_s', round($T1-$T0,3))" > "$OUT/startup.txt"
grep -o "Started CasitaApplication in [0-9.]* seconds (process running for [0-9.]*)" "$OUT/app.log" >> "$OUT/startup.txt"
ps -o rss=,time= -p $PID | awk '{print "after_start_rss_kb",$1,"cpu_time",$2}' >> "$OUT/startup.txt"
jcmd $PID GC.heap_info > "$OUT/heap-after-start.txt" 2>&1
jcmd $PID VM.native_memory summary > "$OUT/nmt-after-start.txt" 2>&1
EMUPID=$(lsof -tiTCP:${EMU##*:} -sTCP:LISTEN 2>/dev/null | head -1)
( while kill -0 $PID 2>/dev/null; do echo "$(date +%s) $(ps -o rss=,%cpu=,time= -p $PID) emu $( [ -n "$EMUPID" ] && ps -o rss=,time= -p $EMUPID)"; sleep 5; done ) > "$OUT/samples.txt" &
SAMPLER=$!
sleep 20   # idle after start
jcmd $PID GC.heap_info > "$OUT/heap-idle.txt" 2>&1
ps -o rss=,time= -p $PID | awk '{print "idle_rss_kb",$1,"cpu_time",$2}' >> "$OUT/startup.txt"
java -cp "$CP" "$B/Driver.java" http://127.0.0.1:18080 "$OUT/data" "$DUR" "$POLL" "$CMD" "$SIGNIN" "$OUT/requests.csv" > "$OUT/driver.log" 2>&1
echo "driver exit $?" >> "$OUT/driver.log"
jcmd $PID GC.heap_info > "$OUT/heap-end.txt" 2>&1
jcmd $PID VM.native_memory summary > "$OUT/nmt-end.txt" 2>&1
grep -c "WARN\|ERROR" "$OUT/app.log" | awk '{print "app_log_warn_error_lines",$1}' >> "$OUT/startup.txt"
sleep 20   # idle after load
ps -o rss=,time= -p $PID | awk '{print "end_rss_kb",$1,"cpu_time",$2}' >> "$OUT/startup.txt"
kill $PID; wait $PID 2>/dev/null; kill $SAMPLER 2>/dev/null
echo finished $NAME
