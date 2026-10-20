#!/bin/bash
# The runs in docs/firestore-benchmark/RESULTS.md. Needs EMU_JAR (the emulator jar). About 100 minutes.
set -u
cd "$(dirname "$0")"; B=$(pwd); S=$(dirname "$B")
export EMU=127.0.0.1:18085
TUNED="-XX:MaxRAM=1g -XX:ActiveProcessorCount=2 -XX:+UseSerialGC -Xms32m -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:TieredStopAtLevel=1 -XX:+ExitOnOutOfMemoryError"
# The emulator: its own JVM, never on 8080. Not part of Casita's footprint (production Firestore is a remote service).
java -Xmx512m -jar "$EMU_JAR" --host=127.0.0.1 --port=18085 > "$B/results-emulator.log" 2>&1 &
EMUPID=$!
trap 'kill $EMUPID 2>/dev/null' EXIT
until nc -z 127.0.0.1 18085 2>/dev/null; do sleep 0.2; done
CP="$S/bench-src/target/test-classes:$S/bench-src/target/classes:$(cat $S/cp.txt)"
mkdir -p results
java -cp "$CP" FirestoreLatency.java "$EMU" latency-$(date +%s) 300 results/firestore-latency.csv > results/firestore-latency.log 2>&1
./startup.sh file-tuned file 5 -- $TUNED
./startup.sh firestore-tuned firestore 5 -- $TUNED
./run.sh realistic-file-tuned file 600 5000 60 300 $TUNED
./run.sh realistic-firestore-tuned firestore 600 5000 60 300 $TUNED
./run.sh realistic-firestore-default firestore 600 5000 60 300 -XX:MaxRAM=1g -XX:ActiveProcessorCount=2
./run.sh soak-firestore-tuned firestore 3600 1000 20 60 $TUNED
echo ALL DONE
