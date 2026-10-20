# Benchmark scripts: Casita with the Firestore store

Simulation only: simulated doors, a stand-in `tailscale whois`, software passkeys, the Firestore **emulator**.
Nothing reaches Meross, Tailscale or any cloud. Casita runs on 127.0.0.1:18080 and the emulator on
127.0.0.1:18085 (its own default is 8080, so the port is always given). Results: [RESULTS.md](RESULTS.md).

Layout (`S` is any scratch directory):

```
S/bench-src/   a detached checkout of the build under test (the report used 3b5b2cf + feat/firestore-persistence)
S/cp.txt       its test classpath
S/bench/       these files
```

```bash
git worktree add --detach "$S/bench-src" 3b5b2cf && (cd "$S/bench-src" && git merge --no-edit feat/firestore-persistence)
(cd "$S/bench-src" && mvn -o -DskipTests package test-compile && mvn -o dependency:build-classpath -Dmdep.outputFile=../cp.txt -Dmdep.includeScope=test)
cp docs/firestore-benchmark/* "$S/bench/" && EMU_JAR=/path/cloud-firestore-emulator-v1.22.0.jar "$S/bench/all.sh"   # ~100 min
python3 "$S/bench/summary.py" realistic-file-tuned realistic-firestore-tuned realistic-firestore-default soak-firestore-tuned
```

| File | What it does |
|---|---|
| `all.sh` | Starts the emulator, then the latency probe, cold starts, three 10-minute runs and the 60-minute soak |
| `run.sh` | One run: Casita with `file` or `firestore` (a fresh emulator project, provisioned), RSS/CPU sampled every 5 s (the emulator's too, separately), the driver, heap and NMT |
| `startup.sh` | N cold starts with either store |
| `Provision.java` | What provisioning does once: `meta/schema` in an emulator project |
| `FirestoreLatency.java` | The store's own calls, timed one by one against the emulator |
| `Driver.java`, `fake-tailscale`, `summary.py` | Unchanged from `docs/gcp-benchmark/` (feasibility benchmark) |
