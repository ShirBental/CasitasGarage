# Benchmark with Firestore (emulator): results

Run 2026-10-20 on the same Mac as `docs/GCP_FREE_TIER_FEASIBILITY.md` section 3 (Apple M5, 32 GB, macOS,
OpenJDK 21.0.12). Simulation only: simulated doors, a stand-in `tailscale whois`, software passkeys,
nothing sent to Meross, Tailscale or any cloud. Casita on 127.0.0.1:18080, the emulator on
127.0.0.1:18085. Port 8080 was left alone.

> **Every Firestore number here is a local, non-production estimate.** The store was Google's Firestore
> emulator on the same machine: no network, no TLS, no IAM, not Google's storage or lock manager. Casita's
> memory and CPU are real measurements of the real client library. Firestore *latency* in production
> (same region, us-east1) must be measured on the VM (gate G6).

## What was measured

- **Build:** a local, unpublished integration of `qa/security-integration` @ `3b5b2cf` (PR #7 UI, Toyota
  commands, PRs #8 and #9) with `feat/firestore-persistence` @ `45a570f`, merged cleanly in a detached scratch
  checkout. No branch was changed. Jar: **96 MB** (was 42 MB: gRPC, protobuf and the Google client).
- **Same workload and scripts as the feasibility benchmark** (`Driver.java`, unchanged): two phones enrolled
  with real invites and real WebAuthn, 5 s polling, a command every 60 s, a sign-in every 5 min; soak at
  ~5× load for 60 minutes; a burst; negative checks. The polling fix (`fix/casita-polling`) was **not**
  in this build. The driver polls constantly, as before: the worst case.
- **Runs:** `file` store, tuned JVM (today's baseline, re-measured); `firestore`, tuned; `firestore`, default
  JVM; `firestore` 60-minute soak, tuned. Plus 5 cold starts each for file and Firestore, and a direct
  call-by-call latency probe of the store (`FirestoreLatency.java`, 300 iterations).
- Tuned flags as in the feasibility doc: `-XX:MaxRAM=1g -XX:ActiveProcessorCount=2 -XX:+UseSerialGC -Xms32m
  -Xmx128m -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:TieredStopAtLevel=1
  -XX:+ExitOnOutOfMemoryError`. Still **not a cgroup limit**; RSS is measured, not enforced.

Raw output: `results/summary.txt`, `results/firestore-latency.csv`, `results/startup-*.txt`.

## Results

### Startup (5 cold starts each, tuned)

| Store | Spring "Started in" | To first `/healthz` | CPU at +10 s | RSS at +10 s |
|---|---|---|---|---|
| file | 0.80–0.91 s | 1.08–1.24 s | 1.47–1.53 s | 136 MiB |
| **firestore** | 1.50–1.58 s | 1.77–1.86 s | 2.23–2.32 s | **173 MiB** |

Firestore adds about 0.7 s and 0.8 CPU-s to startup (the client, gRPC, and the `meta/schema` read), and
**+37 MiB** at rest.

### Memory

| Run | Duration | RSS median / max | RSS first 10% → last 10% | NMT committed (start → end) | GC pauses |
|---|---|---|---|---|---|
| realistic, **file**, tuned | 10 min | 151 / 158 MiB | 150 → 139 | 144 → 151 MiB | 57, max 16 ms |
| realistic, **firestore**, tuned | 10 min | 189 / **196 MiB** | 193 → 189 | 173 → 211 MiB (heap 52, threads 35, metaspace 49, code 18) | 100, max 37 ms |
| realistic, firestore, default JVM (G1 on this Mac) | 10 min | 246 / 264 MiB | 260 → 255 | 316 → 397 MiB (threads 139, GC 38) | 47, max 11 ms |
| **soak, firestore, tuned, ~5× load** | **60 min, 9,046 requests** | 125 / **196 MiB** | 150 → **126** | 173 → 215 MiB | 191, max 59 ms, total 0.55 s |

- **No growth:** over the hour, RSS ended lower than it started (126 vs 150 MiB, last vs first 10%).
- **Firestore's cost in memory: about +40 MiB** (196 vs 158 MiB max). That's within the feasibility
  doc's +40–80 MiB estimate, at its low end. It comes mostly from metaspace (+16), thread stacks (+13:
  gRPC's executor and Netty event-loop threads) and code cache.
- **Use the tuned flags.** The default-JVM run reached 264 MiB, mostly thread stacks (139 MiB committed,
  1 MiB default stacks × gRPC threads). `-Xss512k` and Serial GC matter more with Firestore than without.
- The emulator itself used 40–197 MiB in its own JVM. **That is not part of Casita's footprint**:
  production Firestore is a remote service.

### Latency (server time on loopback, ms)

| Request | file, tuned p50 / p95 / p99 | firestore, tuned p50 / p95 / p99 | soak, firestore p50 / p95 / p99 (n) |
|---|---|---|---|
| Poll `/api/doors` (access check: 1 BatchGet of 3 docs) | 3.4 / 13.4 / 18.4 | 6.3 / 16.2 / 20.3 | 3.5 / 7.3 / 17.2 (7,132) |
| Command → `MOVING` | 2.1–2.9 / 3.1–11.0 | 3.6–4.2 / 5.5–8.4 | 2.5–2.6 / 5.4 / 7.1–7.9 (356) |
| Passkey sign-in: assertion (+ `recordUse` + event) | 10.8 / 17.5 | 17.6 / 24.7 | 26.3 / 43.9 / 75.0 (120) |
| Invite / redeem / register options / register passkey (p50) | 14 / 97 / 9 / 25 | 25 / 18 / 40 / 37 | – |
| Burst: 200 polls from 20 threads | 0.8 / 2.2 / 2.5 | 2.5 / 5.0 / 5.3 | 1.6 / 3.4 / 3.9 |
| 4 simultaneous "open Ford" | 1 MOVING + 3 BUSY | 1 MOVING + 3 BUSY | 1 MOVING + 3 BUSY |

In the soak, 5 of 7,132 polls took over 50 ms (max 199 ms). **All 9,046 soak requests and all 550 per
realistic run returned the expected status.** The unknown node and the cookie replayed from the other phone
got 401 in every run. Every run had 0 WARN/ERROR lines in Casita's log.

**The store alone** (`FirestoreLatency.java`, the emulator, 300 iterations each, ms):

| Call | p50 | p95 | p99 | max |
|---|---|---|---|---|
| `accessView`: 1 BatchGet, 3 docs (every signed-in request) | 0.43 | 1.22 | 1.88 | 3.80 |
| `member`: 1 get | 0.31 | 0.92 | 1.41 | 1.69 |
| `createInvitation`: read + batch (doc + event) | 0.70 | 2.23 | 3.13 | 3.89 |
| `consume`: read + conditional batch | 0.77 | 2.34 | 3.68 | 5.04 |
| `recordUse` (sign-in): read + conditional batch | 0.80 | 2.58 | 3.49 | 4.77 |
| `revokeDevice`: read + conditional batch | 0.72 | 2.21 | 3.10 | 4.60 |
| `enroll`: 4-doc read + 5-write conditional batch | 1.05 | 3.31 | 5.11 | 8.28 |

On the VM, each of these adds the same-region round trips to Firestore: one for reads (`accessView` is
one RPC), two for a decision (read, then commit). *Estimate:* a few ms to ~20 ms each, so a poll of
roughly 10–30 ms and a sign-in of 50–100 ms on an e2-micro. **Unmeasured: gate G6.**

### CPU (measured on the M5)

| Run | CPU-s | Wall | Average |
|---|---|---|---|
| realistic, file, tuned | 4.0 | 658 s | 0.60% of one core |
| realistic, firestore, tuned | 6.0 | 658 s | 0.92% |
| soak, firestore, tuned (~5× load) | 28.7 | 3,653 s | 0.79% (≈ 3.2 ms CPU per request) |

Firestore costs about 1.5× the CPU of the file store per request (protobuf, gRPC, TLS-less here). On an
e2-micro (*estimate*, the feasibility doc's 2–3× factor): ~2–3% of one core with both phones open all the
time, against a 25% sustained baseline. The burst bucket stays full. Startup estimate: ~3.5–7 s with burst
credit.

## The 1 GB budget, revised

| Consumer | MiB | Kind |
|---|---|---|
| Casita with Firestore, tuned JVM | **126–196** (max seen 196) | **Measured** (macOS RSS) |
| `tailscaled` | 30–60 | *Estimate* (unchanged) |
| Ubuntu 24.04 Minimal, guest agent, OS Login, journald | 150–250 | *Estimate* (unchanged) |
| **Total in use** | **≈ 310–510** | |
| **Left for page cache and safety** | **≈ 490–690** | |

**It fits.** The NO-GO line in the feasibility doc was "over ~400 MiB RSS". Measured max: 196 MiB. Keep
`MemoryMax=450M` for the service, the tuned flags, and the 512 MiB swap file. `-Xmx128m` was enough: the
heap held about 52 MiB committed with ~20 MiB live.

## Firestore reads and the bill

Reads per request are as designed: **3 document reads per signed-in request** (one BatchGet), 2 per
enrollment-session request. A decision is a read plus a commit. Writes: an event with every change, so a
sign-in is 2 writes and an enrollment 5.

With `fix/casita-polling` (measured in that branch: an idle visible minute is 2 requests instead of 24,
hidden is 0):

| Pattern | Polls/day | Reads/day | Share of 50,000 free |
|---|---|---|---|
| Typical: ~6 opens/day/person, ~1 min each | ~150 | **~1,300** | ~3% |
| One screen always open, not touched (idle after 3 min: every 60 s) | ~1,440 | **~4,400** | ~9% |
| Both phones always open, not touched | ~2,900 | **~8,800** | ~18% |
| A screen actively touched all day (5 s polling) | ~17,000 | ~52,000 | ~104% (≈ $0.01/day over) |

Before the polling fix, "one screen always open" was 232% of the free quota (~$0.60/month) and "both phones
always open" 464% (~$1.64/month). With it, **every realistic pattern is inside the free quota.** Egress:
an idle minute is 1.6 KB and a warm reload 5.5 KB (was 192 KB), far under 1 GiB a month.
