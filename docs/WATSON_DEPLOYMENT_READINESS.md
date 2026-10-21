# Watson deployment readiness (GCP staging, simulation only)

Prepared 2026-10-21. Nothing here was run against GCP, Tailscale or Meross. Every result below comes from
this environment: local builds, the pinned Firestore emulator, and child-process startups. Items that can
only be proven on the VM are marked **on the VM**.

## 0. Verdict

**GO for simulation-only staging on watson, once the approvals and inputs in section 13 are in hand, and
provided the on-VM gates pass during the runbook:** G3 (no delete), the staging guard on the VM, health, and
G6 (latency, measured and recorded).
**NO-GO for any live hardware control:** H1 and H3 are open, and the candidate keeps both doors simulated.

## 1. GitHub and `main`

| | State |
|---|---|
| `main` | `11ce85a3f645a0cdebc65a2cb8d3f4edc916f0f9` (push CI green) |
| Merged | #2 `8c7a6c6`, #3 `8011117`, #1 `23d84b3`, #4 `2378fb5`, #5 `6046bbc`, #6 `11ce85a`; all merge commits, and each resulting tree equal to its validated tree (+ `docs/DESIGN.md`) |
| Open, unchanged | #7 `73c6ac8`, #8 `f084aaa`, #9 `6684272`, #10 `d2a541a`, #11 `1ae0aaa`. All drafts, still stacked (bases not retargeted); #8 and #9 still say "do not merge" |
| Pushed, not merged | `fix/staging-simulation-guard` `229c577` (H2, first version); `claude/casitas-pr-verification-pi6cww` `a0bed26` (review notes) |

## 2. The release candidate (local only, not pushed)

**Branch `release/watson-staging-rc`, head ``** (the commit that adds this report; the tests below ran on `05114fb`, and only scripts and this report changed after it), built on `main` `11ce85a`:

1. #7, #8, #9, #10, #11 merged in order (`--no-ff`). After each, the tree equals that PR's validated head
   plus only `docs/DESIGN.md`, so after #11 it is exactly the validated top of the stack (`94a029f75834`)
   + #1's doc. No conflicts.
2. H2 (`229c577`) merged, then extended (section 5).
3. The deployment package (`deploy/watson/`, section 11), and watson host-name examples in comments and docs.
4. One test fix: `FirestoreEmulator.provision` writes the schema marker through its own setup client (30 s).
   It used the test's 2 s client, which caused the one failed run seen yesterday. Assertions are unchanged.

Compared with the validated top of the stack, the candidate differs in exactly these files:
`docs/DESIGN.md` (#1); the staging guard (`StagingGuard.java`, `META-INF/spring.factories`, 4 lines of
`application.yml`, `docs/STAGING_GUARD.md`, three test classes); `FirestoreEmulator.java` (test setup);
`deploy/watson/*`; this report; `.gitignore` (`watson.conf`); and host-name examples in comments (`application.yml`,
`CasitaSecurityProperties.java`, `docs/APP.md`). **No production class other than `StagingGuard` changed.**

**Not in the candidate:** `fix/door-timing-h1-h3` `c94ca93` (local, section 8). It changes door timing
logic, and it isn't needed while both doors are simulated.

## 3. Tests (CI-equivalent: `mvn -B clean verify` + the pinned emulator v1.22.0 + `check-test-report.py`)

| Run | Commit | Run | Failed | Skipped | Skip guard |
|---|---|---|---|---|---|
| Candidate, run 1 | `2291dd1` | 712 | 0 | 7 | pass |
| Candidate, run 2 | `05114fb` | 712 | 0 | 7 | pass |
| Candidate, before the guard extension | `0484d64` | 688 | 0 | 7 | pass |
| H1/H3 branch | `c94ca93` | 720 | 0 | 7 | pass |
| For reference: CI on #11 | `1ae0aaa` | 614 | 0 | 7 | pass |

The 7 skips are always `MerossLiveCheckTest` (1) and `MerossLiveCommandTest` (6), which are opt-in and
never run here. No real Meross credentials exist in this environment.

What the suite covers, by the areas asked for (all from run 2; 0 failures and 0 skips in each):

| Area | Tests |
|---|---|
| WebAuthn enrollment, sign-in and revocation, end to end with real WebAuthn (`AccessFlowContract` on the file / DynamoDB / Firestore stores) | 45 / 56 / 55 |
| Sign-in pages, CSRF, behind Tailscale Serve, device identity (`SignInTest`, `CsrfCookieTest`, `BehindTailscaleServeTest`, `TailscaleDeviceIdentityTest`) | 10 + 1 + 4 + 8 |
| Unauthorized and revoked devices against the door API, Ford/Toyota isolation, two taps sending one command (`GarageControllerTest`) | 24 |
| Door rules, concurrent commands (8 threads, 1 activation), holds (`GarageServiceTest`); two doors moving independently (`GarageDoorsTest`) | 32; 19 |
| Meross command path, offline (`MerossDoorCommandTest`) | 27 |
| Polling, dedup, back-off, stale and timed-out answers (`PollerScriptTest`, 20 scenarios under GraalJS); caching and public files | 21; 10 + 6 |
| Security store contract, incl. restarts, races, lost answers, outages (`FirestoreSecurityStoreTest`, Dynamo, File) | 64; 53; 36 |
| Staging guard: rules / real startups / real environment variables in child JVMs | 62 / 12 / 24 |
| Restart and recovery of the real jar (`kill -9`, same store) | section 4 |

**Flakiness.** One failure was seen yesterday (55 errors in `FirestoreAccessFlowTest`, all from its setup
timing out against a cold emulator). The root cause was found and fixed (section 2, item 4). Since then, the
candidate has passed every full run, the two consecutive runs above included.

## 4. VM sizing and memory (this environment; not a cgroup, not the VM)

The release jar (97 MB), run with the template's JVM flags (`-Xmx128m`, Serial GC, `-Xss512k`, …),
`casita.door=simulated`, the Firestore store against the emulator, and port 18081:

| Measure | Result |
|---|---|
| Start to `/healthz` | 5.6 s (Mac benchmark: 1.8 s; this container is slower) |
| RSS at rest | 175 MiB |
| RSS after 6,000 requests (3,000 `/healthz` 200, 3,000 signed-out `/api/doors` 401) | 194 MiB |
| NMT committed | 161 MiB (heap 42 of 128 MiB, metaspace 47, code 14) |
| `kill -9`, then start again on the same store | healthy in 5.6 s, RSS 174 MiB, no WARN/ERROR lines |
| Benchmark (`docs/firestore-benchmark/RESULTS.md`, 60-minute soak, Firestore) | 196 MiB max, no growth |

e2-micro: 1 GiB memory, 2 shared vCPUs. Budget: Casita about 200 MiB (capped at `MemoryMax=512M` by the
unit), tailscaled about 30–50 MiB, Debian about 150 MiB, plus a 1 GiB swap file as a margin. **Expected to
fit with room to spare. Confirm on the VM with `casita-health` after a day of use.** If RSS ever nears
400 MiB, the unit throttles at 384 MiB and restarts Casita at 512 MiB, before tailscaled or sshd are starved.

## 5. The staging guard (H2)

`StagingGuard`, an `EnvironmentPostProcessor`, runs after every property source and before any bean. On
a Compute Engine VM (recognised by `/sys/class/dmi/id/product_name`, which no setting can change), Casita
starts only with `casita.deployment=staging`. In staging it refuses to start unless all of these hold:

- `casita.door` is exactly `simulated`, so **Ford and Toyota are both simulated**;
- `casita.meross.command-mode` is exactly `off`;
- **no Meross credentials** exist anywhere: properties, `MEROSS_*` or `config/meross.local.json`;
- `rp-id` is `watson.<real tailnet>.ts.net` (placeholders are refused), and the only origin is exactly
  `https://<rp-id>`;
- `device-identity` is `tailscale`, the family isn't empty, and the data directory is absolute;
- the store is `firestore` with no emulator, session cookies are `Secure`, and the `dev` profile is off.

Each setting is read the way `@ConditionalOnProperty` reads it and the way `@ConfigurationProperties`
binds it, and a setting the two read differently is refused. Every reason is reported at once.

| Requirement | Verified by |
|---|---|
| Staging requires simulation; both doors simulated | `StagingStartupTest`: real startup, roster Ford + Toyota both `SIMULATED`, no Meross credentials, session or client beans |
| Real Meross credentials rejected | `StagingGuardTest` (each place), `StagingStartupTest`, `StagingEnvironmentVariablesTest` (`CASITA_MEROSS_EMAIL`, `MEROSS_EMAIL`) |
| Live command mode rejected | the same three, `live` and `dry-run`, in every spelling |
| Unsafe or ambiguous configuration stops startup | 62 rule cases in `StagingGuardTest`, including case variants, padding, placeholders, YAML's `false` for a bare `off`, missing values, and settings read differently two ways |
| Not bypassable by normal environment overrides | `StagingEnvironmentVariablesTest`: Casita in its own JVM with `deploy/watson/casita.env.example`, filled in, plus one override each. It starts past the guard as filled in; every unsafe override is refused, including `SPRING_APPLICATION_JSON` and `JAVA_TOOL_OPTIONS=-D…`. On a VM, `CASITA_DEPLOYMENT=local` can't help, because the hardware decides |
| The guard is what refuses | With `spring.factories` removed, the startup refusals start instead (checked by hand) |
| **On the VM** | `deploy/watson/check-guard.sh`: 12 refusals with the real env file and the installed jar |

What the guard doesn't do: it doesn't protect a non-GCP machine deliberately set to `local`, and it can be
removed only by changing the code. Treat any change to `StagingGuard` or its `spring.factories` entry as a
safety change.

## 6. WebAuthn and sign-in on watson

| Setting (`/etc/casita/casita.env`) | Value |
|---|---|
| `CASITA_SECURITY_RPID` | `watson.<TAILNET>.ts.net`: the tailnet's real DNS name, from the Tailscale admin console (DNS page) or `tailscale status --json \| jq -r .Self.DNSName` on watson after `tailscale up --hostname=watson` |
| `CASITA_SECURITY_ALLOWEDORIGINS` | `https://watson.<TAILNET>.ts.net`, the same name with no port |
| `CASITA_SECURITY_FAMILY` | the members' short names, comma-separated (e.g. `shir,hubby`) |
| `CASITA_SECURITY_DEVICEIDENTITY` | `tailscale` |
| `SERVER_SERVLET_SESSION_COOKIE_SECURE` | `true` |

- **Consistency:** the guard requires the origin to be exactly `https://` + rp-id, and
  `tailscale-serve.sh` refuses to start Serve unless the node's own DNS name equals `CASITA_SECURITY_RPID`.
  `CasitaSecurityProperties` also refuses an origin that isn't https or isn't on the rp-id.
- **Behind Serve:** Serve terminates HTTPS and proxies to `127.0.0.1:8080`. Casita trusts forwarded headers
  only from 127.0.0.1, and redirects stay relative (`BehindTailscaleServeTest`). Each request's device comes
  from `tailscale whois` on the source address; Funnel requests and tagged nodes are refused
  (`docs/ACCESS.md`). Casita binds only to 127.0.0.1, and the network admits nothing inbound but IAP SSH.
- **Enrollment and revocation:** one invite per device, 5 minutes, single use; a passkey with user
  verification; the device bound to its tailnet node. Revoking a device, passkey, member or invite takes
  effect on the next request. All of this runs in the `AccessFlowContract` against the file, DynamoDB and
  Firestore stores (section 3).
- **Sessions and cookies:** `HttpOnly`, `Secure`, `SameSite=Lax`, 7 days idle, in memory. A restart signs
  everyone out, and Face ID signs them back in. There's no remember-me yet.
- **Fail closed:** in staging, a missing or placeholder rp-id, wrong origins, an empty family, development
  device identity, insecure cookies or the dev profile each stop startup (section 5). An unreachable
  Firestore refuses access, and a missing schema marker stops startup.
- **Secrets:** none are committed (history scanned on 2026-10-20). The admin secret is created by Casita in
  `/var/lib/casita/admin.secret` (0600). The VM uses no service-account key.
- **The existing iPhone preview and its passkeys are untouched.** watson is a different rp-id, so its
  passkeys are new and separate, and nothing on the Mac changes.

## 7. Firestore: IAM, gates and behaviour

- **Least privilege:** the `casita-runtime` service account gets one custom role, `casitaFirestoreRuntime`,
  with `datastore.entities.get`, `list`, `create`, `update` and `datastore.databases.getMetadata`. It has
  **no delete**, which is G3, and no other role. It is attached to the VM, which uses it through the metadata
  server. No keys are ever created.
- **G3 (no delete), on the VM:** `check-g3.sh <PROJECT_ID>` reads `meta/schema` (expects 200), and tries
  to delete a document that doesn't exist (expects 403: IAM is checked first, so nothing can be removed
  either way). It needs PASS before the first start. The emulator doesn't enforce IAM, so no local result
  covers G3.
- **G6 (latency), on the VM:** `check-g6.sh <PROJECT_ID>` gives the REST read latency from watson.
  - With Tomcat's access log on for one measuring session, it also gives the server time of `/api/doors`
    (every poll includes the access check's BatchGet), `/api/activity` and passkey sign-in.
  - Proposed pass: REST read p95 ≤ 100 ms, `/api/doors` p95 ≤ 250 ms, sign-in p95 ≤ 1 s.
  - **The emulator figures (p95 16 ms per poll) are not evidence for G6.**
- **Conditional writes and concurrency:** each decision is one consistent read and one atomic batch
  conditioned on what it read. Tested with 20-way redemption races, deterministic interleavings, lost
  answers, contention and outages (`FirestoreSecurityStoreTest`, `docs/FIRESTORE_SECURITY_REVIEW.md`).
- **Enrollment and revocation persistence, idempotency and restarts:** the whole `SecurityStoreContract` and
  `AccessFlowContract` run against the emulator, restarts included.
- **Audit events:** a security event is written in the same batch as each change, both or neither. Known
  gap: refusals decided by the access check alone (a revoked device's next request) are logged, not stored.
- **Firestore unavailable:** requests are refused, no session is granted and invites stay unused. At
  startup, an unreachable database or a missing `meta/schema` stops Casita, and `install-release.sh` then
  rolls back.
- **Not covered by tests:** real-cloud latency (G6), IAM (G3), TTL deletion timing (cleanup only) and Security
  Rules. Rules apply only to client SDKs; confirm deny-all in the console.
- **Free tier:** only the first database gets it. The provisioning script creates `(default)` only.

## 8. H1 and H3 (live control only; not enabled anywhere)

- **H1:** `travel-time` is 10 s for every door, while Ford was watched for about 20 s. With 10 s, Casita
  counts the door as settled once the open's check passes at 12 s, so a close requested between 12 and 20 s
  would reach a door still opening, which could stop or reverse it. Restarts and remote-control opens have
  the same 10 s window.
- **H3:** Toyota's reading lagged the door by at least 25 s. A remote-control close hidden by a stale
  reading can't be detected by any setting.
- **Prepared, not merged: `fix/door-timing-h1-h3` `c94ca93`.**
  - A per-door `casita.garage.sensor-lags`: settling and checks wait travel time plus lag.
  - **Live mode refuses to start for any door without its own measured `travel-times` and `sensor-lags`.**
  - Tests for the H1 hazard with the defaults, the fix with the door's own timing, a lagging reading, and
    outside movement or restarts.
  - **No timing values are set.** The supervised measurement procedure is in that branch's
    `docs/MEROSS.md`, "Timings before live commands".
- **Neither issue is resolved** until the timings are measured on the doors and configured. Both doors stay
  simulated in the candidate.

## 9. GCP resources

| Resource | Name / setting |
|---|---|
| Project | `<PROJECT_ID>`, billing linked |
| APIs | `compute`, `firestore`, `iap` |
| Service account | `casita-runtime@<PROJECT_ID>.iam.gserviceaccount.com`, no keys |
| Custom role | `projects/<PROJECT_ID>/roles/casitaFirestoreRuntime` (get, list, create, update, getMetadata) |
| Firestore | `(default)`, Native mode, `us-east1`, delete protection; TTL on `invitations.ttl` and `securityEvents.expiresAt`; index exemptions on 3 byte fields of `passkeys`; document `meta/schema {schemaVersion: 1}` |
| VPC | `watson-net` (custom), subnet `watson-subnet` `10.80.0.0/28` in `us-east1`, Private Google Access |
| Firewall | `watson-net-allow-iap-ssh`: tcp:22 from `35.235.240.0/20` only; everything else inbound refused (implied deny) |
| VM | `watson`, `us-east1-b`, `e2-micro`, Debian 12, 30 GB `pd-standard`, Shielded VM, OS Login, ephemeral external IPv4 (outbound only), labels `app=casita,env=staging` |
| Budget | $5/month alert (console; you set it) |

Not used: load balancers, static IPs, Cloud NAT, Cloud Logging agent (logs stay in journald), snapshots,
secrets. Region: `us-east1`, one of the three Always Free e2-micro regions (`us-west1`, `us-central1`,
`us-east1`).

## 10. Cost (expected $0–$5 a month)

| Item | Expected |
|---|---|
| e2-micro in `us-east1` | $0 (Always Free: one e2-micro a month in the three US regions) |
| 30 GB standard persistent disk | $0 (Always Free) |
| **External IPv4 address, in use** | **about $3.65/month** ($0.005/hour). Billed separately from the free VM |
| Network egress | about $0 (Tailscale control traffic, apt updates, Firestore responses). Check the current free allowance on the billing page |
| Firestore | $0 within the free tier: 50,000 reads, 20,000 writes and 20,000 deletes a day, 1 GiB stored, 10 GiB egress a month. A poll every 5 s while the app is open costs about 1.2 reads/s (2 requests × 3 documents ÷ 5 s), about 4,300 an hour of open app; hidden pages don't poll |
| IAP SSH, VPC, firewall, IAM | $0 |

**Cost risks:**
- a VM type, region or disk type outside the free tier (the script pins e2-micro, `us-east1`, `pd-standard`);
- a second VM;
- a reserved static IP (charged even when unused);
- Cloud NAT;
- turning on the Ops Agent with heavy logs;
- snapshots;
- a named (non-default) Firestore database;
- an app left open and visible all day on several devices, which would approach the read quota but not exceed it.

**Set the $5 budget alert.** Optional saving: an IPv6-only VM would avoid the IPv4 charge, but is untested
with Tailscale, Adoptium and Debian mirrors here, so it isn't recommended for the first deployment.

## 11. Runbook

`deploy/watson/README.md` lists the scripts. Every script that changes something is a dry run without
`--apply`. **Before any of this: the approvals and inputs in section 13.**

**A. On the Mac, once:**
1. Get the candidate: `git fetch origin && git switch release/watson-staging-rc`. It must be pushed
   first, which needs approval.
2. Download the emulator jar if needed (`docs/FIRESTORE.md`, "Locally").
3. `cp deploy/watson/watson.conf.example deploy/watson/watson.conf` and set `PROJECT_ID`.
4. `gcloud auth login && gcloud config set project <PROJECT_ID>`.

**B. Provision:**
5. `deploy/watson/provision-gcp.sh deploy/watson/watson.conf` (dry run: read every command).
6. `deploy/watson/provision-gcp.sh deploy/watson/watson.conf --apply`. If a flag is rejected by your gcloud
   version, stop and fix it there.
7. Console → Billing → Budgets: a $5/month alert on the project.

**C. Bootstrap watson:**
8. `gcloud compute scp --recurse deploy/watson watson:~/watson-setup --zone=us-east1-b --tunnel-through-iap`
9. `gcloud compute ssh watson --zone=us-east1-b --tunnel-through-iap`, then on watson:
10. `sudo bash ~/watson-setup/bootstrap-vm.sh`
11. `bash ~/watson-setup/check-g3.sh <PROJECT_ID>` must print **G3: PASS**.
12. `sudo tailscale up --hostname=watson`, and open the sign-in link. In the Tailscale admin console, disable
    key expiry for watson, and make sure MagicDNS and HTTPS certificates are on.
13. `tailscale status --json | jq -r .Self.DNSName` gives `watson.<tailnet>.ts.net.`. That is the verified
    name.
14. `sudo nano /etc/casita/casita.env` and fill in `<TAILNET>` (twice), `<MEMBERS>` and `<PROJECT_ID>`.

**D. First release, from the Mac:**
15. `deploy/watson/deploy-release.sh deploy/watson/watson.conf` (clean checkout; full CI build; prints the
    SHA-256).
16. `deploy/watson/deploy-release.sh deploy/watson/watson.conf --apply` copies the release, installs it,
    starts it and checks health.

**E. Verify on watson:**
17. `casita-health` must be all `ok`: service active, `/healthz`, a `staging guard: ok … door=simulated
    command_mode=off …` line, and Funnel off.
18. `sudo bash ~/watson-setup/check-guard.sh` must print **Staging guard on watson: PASS** (12 refusals).
19. `sudo bash ~/watson-setup/tailscale-serve.sh`, then the same with `--apply`.
20. From a tailnet device, `https://watson.<tailnet>.ts.net` shows the sign-in page.

**F. Enroll and try it:**
21. On watson, `sudo casita-access invite shir "Shir's iPhone"`, then `sudo cat
    /var/lib/casita/invites/<id>.txt`. Open the link on that iPhone, on the tailnet, within 5 minutes, and use
    Face ID.
22. The app shows "Simulation mode", and Ford and Toyota are both practice doors. Open and close each, and
    check the history.
23. `sudo systemctl restart casita` signs everyone out; sign back in with Face ID.
24. Optional: enroll a second device, `casita-access revoke-device <node id>`, and confirm that device is
    signed out on its next request.

**G. G6 (latency):**
25. Add these to `/etc/casita/casita.env`, then `sudo systemctl restart casita`:
    `SERVER_TOMCAT_ACCESSLOG_ENABLED=true`,
    `SERVER_TOMCAT_BASEDIR=/var/lib/casita/tomcat` and
    `SERVER_TOMCAT_ACCESSLOG_PATTERN=%h %t %m %U %s %{ms}T`.
26. Use the app for about 10 minutes, including a sign-in, then run `bash ~/watson-setup/check-g6.sh
    <PROJECT_ID>`.
27. Remove the three lines and restart. Record the results in section 14.

**Updating later:** commit, then step 16 again. Health is checked automatically, and a failed release rolls
back by itself.

**Logs:** `journalctl -u casita -f`, or `-n 200`, or `--since today`. Health: `casita-health`.

## 12. Rollback

| Situation | Action |
|---|---|
| A new release is unhealthy | `install-release.sh` rolls back by itself, to `/opt/casita/previous` |
| Go back by hand | `sudo casita-rollback`. Only the jar changes; the data and Firestore stay |
| Stop staging, keep everything | `sudo systemctl disable --now casita`, then `sudo tailscale serve reset` |
| Stop paying for the VM | `gcloud compute instances stop watson --zone=us-east1-b`. A stopped VM releases its ephemeral IP; the disk stays, and is free within 30 GB |
| Remove everything | Only with your explicit approval: delete the VM, network and firewall rule, and the IAM binding and role. Firestore has delete protection; turn it off deliberately first |

The first release has no previous one. If it fails, `casita-health` and `journalctl -u casita` say why, and
the fix is a new release.

## 13. Blockers and open items

**Needed from you before deploying:**
1. **Approval to push** `release/watson-staging-rc` (so the Mac can build it), and decide whether to
   deploy from it or to merge #7–#11 and H2 to `main` first (batches 3–5; #8 and #9 still say "do not
   merge"). Recommendation: deploy from the candidate, which is exactly the validated stack + #1 + H2 +
   deployment files, then merge.
2. The GCP project ID with billing linked, the verified tailnet name, and the family's short names.
3. Approval for each external action in section 15.

**Only provable on the VM** (the runbook covers each):
- G3; G6; `check-guard.sh`;
- `tailscale whois` from Casita's sandboxed unit, as the non-root `casita` user (a failure fails closed:
  invites stay unused);
- the gcloud flags against your gcloud version (the dry run shows them);
- the `temurin-21-jre` package;
- Tailscale's HTTPS certificate for watson;
- memory over a day.

**Known and accepted for simulation-only staging:**
- sessions end on restart (no remember-me);
- access-check refusals are not stored as security events;
- the external IPv4 cost (about $3.65/month).

**Before any live control (not part of staging):**
- H1 and H3: measure, configure, and merge `fix/door-timing-h1-h3` or an equivalent;
- a separate review;
- the hardware checklist in `docs/DESIGN.md`, section 6;
- production on Compute Engine would need a deliberate change to the guard.

**M1 equivalence: not verified.** `qa/casita-m1-integration` @ `4cefe33` isn't on GitHub or in this
environment. On the Mac:

```bash
cd ~/Dev/CasitasGarage && git fetch origin
git rev-parse 4cefe33^{tree}                          # the M1 tree
git rev-parse origin/feat/toyota-commands^{tree}      # 94a029f75834… (the validated top of the stack)
git diff --stat 4cefe33 origin/feat/toyota-commands   # empty if M1 is the stack's top
# once the candidate is pushed: only the files listed in section 2 should differ
git diff --stat 4cefe33 origin/release/watson-staging-rc
```

Every file in that second diff outside the list in section 2 needs an explanation before deploying.

## 14. Results to record tomorrow

| Gate | Result | Date / release |
|---|---|---|
| G3 (`check-g3.sh`) | | |
| Staging guard on watson (`check-guard.sh`) | | |
| `casita-health` after first start | | |
| G6 REST read p50 / p95 | | |
| G6 `/api/doors` p50 / p95, sign-in p95 | | |
| RSS after a day (`casita-health`) | | |
| M1 tree comparison | | |

## 15. Copy-paste checklist

```text
[ ] Approvals: push release/watson-staging-rc; provision GCP; deploy; Tailscale changes; billing budget
[ ] Inputs: PROJECT_ID=________  TAILNET=________.ts.net  MEMBERS=________
[ ] Mac: git fetch origin && git switch release/watson-staging-rc && git log -1   (expect the commit that adds docs/WATSON_DEPLOYMENT_READINESS.md)
[ ] Mac: M1 check (section 13) explained
[ ] Mac: cp deploy/watson/watson.conf.example deploy/watson/watson.conf ; set PROJECT_ID
[ ] Mac: gcloud auth login ; gcloud config set project $PROJECT_ID
[ ] Mac: deploy/watson/provision-gcp.sh deploy/watson/watson.conf            (read the dry run)
[ ] Mac: deploy/watson/provision-gcp.sh deploy/watson/watson.conf --apply
[ ] Console: $5 budget alert on the project
[ ] Mac: gcloud compute scp --recurse deploy/watson watson:~/watson-setup --zone=us-east1-b --tunnel-through-iap
[ ] Mac: gcloud compute ssh watson --zone=us-east1-b --tunnel-through-iap
[ ] VM:  sudo bash ~/watson-setup/bootstrap-vm.sh
[ ] VM:  bash ~/watson-setup/check-g3.sh $PROJECT_ID                         -> G3: PASS
[ ] VM:  sudo tailscale up --hostname=watson ; admin console: key expiry off, MagicDNS + HTTPS on
[ ] VM:  tailscale status --json | jq -r .Self.DNSName                       -> watson.<tailnet>.ts.net.
[ ] VM:  sudo nano /etc/casita/casita.env   (TAILNET x2, MEMBERS, PROJECT_ID)
[ ] Mac: deploy/watson/deploy-release.sh deploy/watson/watson.conf            (builds, verifies)
[ ] Mac: deploy/watson/deploy-release.sh deploy/watson/watson.conf --apply
[ ] VM:  casita-health                                                       -> all ok, door=simulated command_mode=off
[ ] VM:  sudo bash ~/watson-setup/check-guard.sh                             -> PASS (12 refusals)
[ ] VM:  sudo bash ~/watson-setup/tailscale-serve.sh ; then --apply
[ ] Phone (tailnet): https://watson.<tailnet>.ts.net shows sign-in
[ ] VM:  sudo casita-access invite shir "Shir's iPhone" ; sudo cat /var/lib/casita/invites/<id>.txt
[ ] Phone: open the link within 5 min, Face ID; app says Simulation mode; Ford and Toyota practice doors work
[ ] VM:  sudo systemctl restart casita ; phone signs back in
[ ] G6:  access log on (section 11, step 25), use 10 min, bash ~/watson-setup/check-g6.sh $PROJECT_ID, access log off
[ ] Record section 14
```
