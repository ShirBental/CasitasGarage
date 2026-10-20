# Merge readiness: PRs #1–#11

Review date: 2026-10-20. Simulation only: no Meross command was sent, no live test was run, and nothing on
GitHub was changed. Every SHA below was re-read from GitHub and `git fetch` for this review; nothing is
taken from the earlier handoff snapshot (`docs/session-handoff` @ `2e5e695` isn't on GitHub, so it
couldn't be read from this environment).

## 1. Current state (verified)

`main` is `739c0ab` (README and .gitignore only). Every PR below is open, `mergeable_state: clean`, and
green on its current head (one `build` job; #9 has two green runs on the same SHA).

| PR | Branch | Head | Base (SHA) | Tree | Draft | Diff vs base |
|---|---|---|---|---|---|---|
| #1 Design doc | `docs/design` | `0a4f741` | `main` (`739c0ab`) | `96463f7f5dad` | no | 1 file, +272 |
| #2 Skeleton + CI | `feat/skeleton` | `a01b0bc` | `main` (`739c0ab`) | `7467a8daae3d` | no | 8 files, +195 |
| #3 GarageService | `feat/garage-service` | `9538640` | #2 (`a01b0bc`) | `e4d597346355` | no | 23 files, +1204 |
| #4 Meross client | `feat/meross-cloud` | `fd69015` | #3 (`9538640`) | `b4db9e541511` | no | 26 files, +3318 −1 |
| #5 MSG200 commands | `feat/msg200-commands` | `cadd33c` | #4 (`fd69015`) | `a978b851a5a8` | **yes** | 15 files, +842 −124 |
| #6 Ford close + API | `feat/ford-close-and-status` | `9051e97` | #5 (`cadd33c`) | `2da9de9690f5` | **yes** | 12 files, +629 −69 |
| #7 Casita app | `feat/casita-app` | `73c6ac8` | #6 (`9051e97`) | `dffabe24b884` | **yes** | 76 files, +3672 −23 |
| #8 Device access | `feat/device-access` | `f084aaa` | #7 (`73c6ac8`) | `7f5a6c2b6e46` | **yes** | 45 files, +2976 −199 |
| #9 DynamoDB store | `feat/dynamodb-invitations` | `6684272` | #8 (`f084aaa`) | `c3ca4b7fe761` | **yes** | 56 files, +5235 −1607 |
| #10 Firestore store | `feat/firestore-persistence` | `d2a541a` | #9 (`6684272`) | `ec731f7135d7` | **yes** | 29 files, +3379 −12 |
| #11 Toyota commands | `feat/toyota-commands` | `1ae0aaa` | #10 (`d2a541a`) | `94a029f75834` | **yes** | 26 files, +1418 −283 |

- **Stack is linear.** Each branch contains the one below it (`git merge-base --is-ancestor`), and each PR's
  base SHA equals the head of the PR below. The polling/caching work is #7's head commit (`73c6ac8`), and
  #8–#11 were all rebuilt on it on 2026-10-20.
- **Overlap:** each PR's diff against its base is exactly what GitHub shows, so no PR re-carries another's
  changes. #1 touches only `docs/DESIGN.md`, which no stack branch has; a trial merge of #1 into #11 is clean.
- **Not on GitHub** (and so not reviewable here): `docs/session-handoff`, `infra/gcp-staging` (M2), and
  any later polling work in the local `casita-polling-4d4af6` worktree. Anything there isn't part of this plan.

### Tests (this review, local, same commands as CI)

Run in a fresh worktree per branch, with Meross credentials unset (no `MEROSS_*`/`CASITA_MEROSS_*`, no
`config/meross.local.json`). The Firestore emulator is the pinned v1.22.0, SHA-256 verified. Ports are
random; nothing used 8080.

| PR | Run | Failed | Skipped | Matches CI |
|---|---|---|---|---|
| #2 | 2 | 0 | 0 | — (log cut off before the totals) |
| #3 | 24 | 0 | 0 | — |
| #4 | 117 | 0 | 1 | — |
| #5 | 141 | 0 | 3 | — (PR text: 141 run, 4 skipped; 3 here because no local credentials file) |
| #6 | 166 | 0 | 4 | — (PR text: 166/0/5, same reason) |
| #7 | 234 | 0 | 4 | — |
| #8 | 286 | 0 | 4 | — |
| #9 | 444 | 0 | 4 | yes (CI 444/0/4) |
| #10 | 563 | 0 | 4 | yes (CI 563, guard passed) |
| #11 | 614 | 0 | 7 | yes (CI 614, guard passed) |

These are the minimums for gate 5. At every step, the skips must be only the Meross live tests.

The skipped tests are only `MerossLiveCheckTest` and `MerossLiveCommandTest` (opt-in, real account). The
CI log for #11 reads "614 tests in 36 reports, 7 skipped", the same as here, and its skip guard passes.

## 2. PR #1: the design doc

**Recommendation: merge #1 first, but only after adding a status banner and a "superseded" list to it.**
Don't merge it as-is, and don't treat it as authoritative.

Why merge it at all: the stack links to it as the gate for live control. `README.md`, `docs/MEROSS.md`,
`docs/APP.md`, `application.yml` and `MerossProperties` all point at "the hardware prerequisites in
docs/DESIGN.md", and section 6's hardware checklist and section 5's safety rules still match the code.
Without #1 those links are dead on `main`.

What is out of date (the banner should say each of these is superseded, and by what):

| DESIGN.md says | Current decision (where) |
|---|---|
| EC2 `t4g.micro`, AWS IAM/KMS | GCP Compute Engine e2-micro staging (M2, `infra/gcp-staging`, not on GitHub yet) |
| Parameter Store for passkeys, users, secrets | `SecurityStore`: file (default), DynamoDB (#9) or Firestore (#10). Meross secrets still env/local file |
| Remember-me cookie, fixed 6 months, `SameSite=Strict` | Not built. In-memory session, 7 days idle, `SameSite=Lax` (#7) |
| Invite: one-time token, 10 minutes, any device | One invite per device, 5 minutes, enrollment-only session, bound to the Tailscale node (#8) |
| A passkey alone signs you in | Passkey **and** an authorized tailnet node, re-checked on every request (#8) |
| Shortcut status tokens | Not built |
| One MSG100, one door | MSG200, Ford (ch 1) and Toyota (ch 2); ch 3 disabled and never addressed (#5, #11) |
| Travel time 10 s measured | Ford measured at 20 s (docs/MEROSS.md); see finding H1 |
| Open questions: Java version, MQTT client | Decided: Java 21, HiveMQ |
| Host `casita.<tailnet>.ts.net` | The server is **`watson`**: `watson.<tailnet>.ts.net` (decided 2026-10-20; see section 4) |
| Activity log / push: open | Still open (in-memory log, log-only notifier) |
| Build order steps 5–8 | Replaced by the actual stack (#5–#11) and M2 |

Still valid and worth keeping as written: sections 5 (safety rules and known limitations) and 6 (hardware
prerequisites), and the API table in section 8 (#11 adds per-door endpoints).

## 3. Hardware safety review (#4–#6, #11, and the top of the stack)

### Verified safe

- **Real commands are off by default.** `casita.door: simulated` (and `matchIfMissing` selects the
  simulated roster), `casita.meross.command-mode: "off"` (quoted so YAML doesn't read `false`; the record
  also defaults to OFF), `command-channels: [1]`.
- **Three independent switches** are needed before a SET is published: `casita.door=meross`,
  `command-mode=live`, and the channel listed in `command-channels`. A door outside `command-channels` gets
  `MerossDoor.readOnly` (mode OFF, empty channel set). `MerossProperties` refuses to start when
  `command-channels` names a door that isn't in `doors`.
- **Simulation and live can't be confused.** The two rosters are mutually exclusive
  `@ConditionalOnProperty` configs. Each door carries a `DoorControl` (SIMULATED/OFF/DRY_RUN/LIVE) that
  `/api/doors` reports and the UI uses ("Simulation mode" badge; Toyota disabled while not live).
- **No production credentials are committed.** I scanned every commit on every branch for key and token
  patterns, `meross.local.json`, `.env`, `data/`, `admin.secret` and private keys: none found. The example
  file holds placeholders. Emails are all `@example.com`, and tailnet names are `example-tailnet`.
  Credentials come only from the environment or the git-ignored `config/meross.local.json`; the start-up
  error names what's missing, and a test checks that logs never contain them.
- **Commands need explicit authorization.** In the app: a passkey session from a family member on an
  authorized tailnet node, re-checked on every request; CSRF on every POST; no GET toggles
  (`thereIsNoToggleAndCommandsAreNeverAGet`). In the live tests: `-Dmeross.live=true` plus a confirmation
  string naming the action, door, channel and today's date; Toyota also needs its measured travel time.
- **Duplicate commands can't trigger repeated physical actions.**
  - `GarageService` is `synchronized` per door, and keeps one movement at a time until it's checked
    (`twoTapsAtOnceSendOneCommand`, `simultaneousRequestsActivateTheDoorOnlyOnce`).
  - A command goes only toward the opposite of a settled reading. `MerossDoor` re-reads the door just
    before building the SET.
  - The SET is published at **QoS 0**, once, on an already-live connection only (never right after signing
    in), and never retried. An unconfirmed publish drops the connection so nothing stays queued.
- **Unknown state is handled safely.**
  - An unreadable sensor gives `UNKNOWN` and nothing is sent.
  - A failure after hand-off gives `UNCONFIRMED`; the door is treated as possibly moving and checked later.
  - `STUCK`/`LOST` hold every command until a person calls `resume`.
  - `execute: 0`, or an acknowledgement naming another channel, is an unknown outcome, never success.
  - A disabled channel (`doorEnable: 0`) is refused.

### Findings

| # | Severity | Finding | Blocks |
|---|---|---|---|
| H1 | **High** | `application.yml` sets `casita.garage.travel-time: 10s` for every door, but Ford's travel time is 20 s (docs/MEROSS.md, and `MerossLiveCommandTest`'s default of 20). In live mode the settle rule would let a close go after only 10 s of "not closed", which could hit a door still opening and act as a second button press (stop or reverse). The check at 12 s would also report a normal open as `STUCK`. Simulation is unaffected. | Enabling live mode. Not a merge blocker while `command-mode` is off |
| H2 | **High** | **No hard guard ties a cloud deployment to simulation.** A staging VM controls the real doors if it has `CASITA_DOOR=meross`, `CASITA_MEROSS_COMMAND_MODE=live` and credentials, which can come from the environment or from a `config/meross.local.json` in its working directory. Nothing in the code knows it is staging. | Any GCP staging deployment (M2) |
| H3 | Medium | The MSG200's `System.All` reading lags the door: Toyota was seen still "closed" at least 25 s after fully opening (docs/MEROSS.md). `GarageService` assumes current readings, so outside movement (remote or wall button) can be misjudged. Ford's lag hasn't been measured. Documented, and Toyota stays off, but it is a prerequisite that isn't enforced by code. | Adding channel 2 to `command-channels`, and Ford live |
| H4 | Low | `MerossLiveCheckTest` runs when `MEROSS_EMAIL` is set, even without `-Dmeross.live=true`. It is read-only, but it signs in to the real account during an ordinary `mvn test`. `MerossLiveCommandTest` is also enabled then, though each test still needs the dated confirmation, so no command can be sent. | Nothing; tidy-up |
| H5 | Low | A misspelled `casita.door` (e.g. `merros`) loads neither roster, so start-up fails with a missing-bean error rather than a clear message. It fails closed. | Nothing; tidy-up |
| H6 | Info | A test fixture uses `1912089283245425180234298f1a2b3c`, which has the shape of a Meross device UUID. Its tail and its variants (`…f1a0001`, `…f1a9999`) look synthetic. Please confirm it isn't the real opener's UUID; it isn't a credential either way. | Nothing |

### Other findings (docs and process)

- **D1:** `docs/FIRESTORE.md` and `docs/firestore-benchmark/RESULTS.md` link to
  `docs/GCP_FREE_TIER_FEASIBILITY.md`, and #10/#11 point at `docs/github/PR_STACK.md`. Neither file
  exists on any GitHub branch.
- **D2:** PR descriptions are stale in places:
  - #8 and #9 still say "**Draft, do not merge**".
  - #9 says 333 tests, but its CI ran 444.
  - Comments in `MerossProperties` and `application.yml` still say the Meross secrets "move to Parameter
    Store next", which is the AWS plan.
- **D3:** CI's skip guard (`scripts/ci/check-test-report.py`) and the required Firestore emulator only
  arrive with #10. Until then, `main`'s CI can't catch a silently skipped test, so the gates below run
  `scripts/merge-gate/check-skips.py` locally for #2–#9.
- **D4 (Firestore):** there's no data migration from file or DynamoDB to Firestore. Switching
  `casita.storage.type` means re-enrolling each device with a new invite, as PERSISTENCE.md already plans
  for DynamoDB. Start-up refuses a missing `meta/schema`, and refuses `FIRESTORE_EMULATOR_HOST` set without
  the explicit emulator setting. Still open: IAM no-delete (gate G3, can't be tested on the emulator), real
  latency (G6), and refusals not stored as security events.
- **D5 (polling × Firestore):** every signed-in request runs one consistent `BatchGetDocuments` of member,
  passkey and device. One poll is two requests (`/api/doors` and `/api/activity`), so polling every second
  during a movement is about 6 document reads a second. That's fine for two people, but G6 should measure it.

## 4. Fixes, with suggested branches

None of these needs a change to a stacked branch. Each is a new, small PR on `main` after the stack lands,
so the merge gates' tree comparison stays exact.

| Fix | Branch | Change | Needed before |
|---|---|---|---|
| H1 | `fix/ford-travel-time` | `casita.garage.travel-times.Ford: 20s` (or a global 20 s plus margin), and a test that the committed `application.yml` gives Ford at least 20 s | Live mode |
| H2 | `fix/staging-simulation-guard` | `casita.deployment` (`local`/`staging`/`production`). With `staging`, start-up fails unless `casita.door=simulated` and `command-mode=off`, and `MerossLocalCredentials` never reads the file. Tests for each refusal. The M2 unit sets `CASITA_DEPLOYMENT=staging` | Any GCP deployment |
| H3 | (hardware session, then a code gate) | Measure Ford's and Toyota's reading lag. Then either a startup check that a live channel's travel time exceeds its measured lag, or a documented sign-off | Toyota in `command-channels`, Ford live |
| H4 | `fix/live-tests-opt-in` | `requested()` returns true only with `-Dmeross.live=true` | Nothing |
| H5 | `fix/door-setting-check` | Validate `casita.door` ∈ {simulated, meross} with a clear message | Nothing |
| #1 | `docs/design` (a commit on #1) | Status banner and the superseded table from section 2 | Merging #1 |
| Host name | `docs/watson-host` (or with M2) | The server is `watson`. Set `rp-id: watson.<tailnet>.ts.net` and `allowed-origins: [https://watson.<tailnet>.ts.net]` in the staging and production config, and update the examples in `application.yml`, `docs/APP.md` and `CasitaSecurityProperties`. No code change: both are settings, and device checks use the tailnet user, not the host name. **Passkeys are bound to the rp-id, so set it before anyone enrolls; renaming the server later means everyone re-enrolls.** The companion's name stays `casita.brand.companion: Watson`, which is separate | First enrollment on the server |
| D1 | `docs/missing-links` | Add or unlink `GCP_FREE_TIER_FEASIBILITY.md` and `PR_STACK.md` (or land them with M2) | Nothing |

## 5. Merge method

**Use merge commits for every PR. Don't squash or rebase.**

- Each branch already contains the real commits of the branches below it. If a lower PR is merged with a
  merge commit, those commits land on `main` unchanged, so the next PR's diff against `main` is exactly its
  own and its tree is still the one that was tested.
- Squash or rebase would put new commits on `main` that no upper branch contains. Every upper PR would then
  show the lower changes again, or conflict, and could only be fixed by rewriting each branch. That would
  throw away the validated trees.
- With merge commits, after #N merges, `main^{tree}` must equal #N's validated tree (plus `docs/DESIGN.md`
  if #1 went first). That is the gate below. After #11, `main^{tree}` must equal `94a029f75834`
  (+ DESIGN.md). Please compare that with your M1 integration tree hash, which I couldn't see from here.

**Retargeting and fresh CI.** After a lower PR merges, the next PR's base must become `main`. GitHub only
does this on its own if the lower branch is deleted. Changing a base doesn't re-run the `pull_request`
workflow, and re-running an old run tests the old merge ref. So each step uses **Update branch** (merging
`main` into the PR head). That adds one merge commit with an identical tree and starts a fresh CI run on
the real merge result.

## 6. Readiness per PR

Tests: in every row, "post-merge" means push CI on `main` green, plus `scripts/merge-gate/check-gate.sh`.

| PR | Needs first | Expected change | Risks | Post-merge tests | Ready for review | Ready to merge |
|---|---|---|---|---|---|---|
| #1 | none | `docs/DESIGN.md` | Read as authoritative while outdated | none (docs) | yes | **No:** banner first |
| #2 | none | pom, app, `/healthz` public, everything else 401, CI | none | baseline 2/0/0 | yes | **Yes** |
| #3 | #2 | `GarageService` rules, simulated door, properties validation | none; fake door only | baseline 24/0/0 | yes | **Yes**, after retarget and fresh CI |
| #4 | #3 | Meross client, read-only; commands rejected | Real cloud sign-in if `casita.door=meross` | baseline + skips = live only | yes | Yes, in batch 2 |
| #5 | #4 | MSG200 SET, `command-mode` off/dry-run/live, Ford-only allowlist | Command path exists; off by default | baseline; `MerossDoorCommandTest` all run | yes (draft) | Draft; mark ready first (batch 2) |
| #6 | #5 | Final outcomes, hold after STUCK/LOST, garage API (401 until #7) | H1 matters once live | baseline | yes (draft) | Draft; batch 2 |
| #7 | #6 | Passkey sign-in, invites, mobile UI, Watson, polling and caching | Session and CSRF model; forwarded headers from 127.0.0.1 only | baseline; `BehindTailscaleServeTest`, `CacheHeadersTest`, `PollerScriptTest` | yes (draft) | Draft; batch 3 |
| #8 | #7 | Passkey + Tailscale node; one invite per device; admin local only | Access model change; fail-closed on whois | baseline; `FileAccessFlowTest` | yes (draft) | **Body says "do not merge"**: needs your OK (batch 4) |
| #9 | #8 | `SecurityStore`, DynamoDB store, enrollment transaction | Large refactor (−1607); default stays `file` | baseline; Dynamo and File contract tests | yes (draft) | **Body says "do not merge"**: needs your OK (batch 4) |
| #10 | #9 | Firestore store, emulator CI, skip guard | G3/G6 open; default stays `file` | 563/0/4 + guard (CI) | yes (draft) | Needs M1 sign-off (batch 5) |
| #11 | #10 | Per-door roster and API, Toyota simulated, Meross reads both doors, auth tests | Meross mode now reads both channels; Toyota stays read-only | 614/0/7 + guard; `GarageControllerTest` (24) | yes (draft) | Batch 5; final tree check |

## 7. Merge gates (every PR)

Go only if all of these hold, checked right before the merge (not from an earlier run):

1. **Head unchanged:** the PR head is the SHA recorded above (or the update-branch merge commit made in
   this step, whose tree equals the recorded tree).
2. **Base:** the base is `main`, and `main` is the merge commit of the previous step.
3. **Mergeable:** `mergeable_state: clean`, no conflicts.
4. **Fresh CI:** green on the head *after* the base change (from Update branch), not an older run.
5. **No silent skips:** for #2–#9, `check-skips.py target/surefire-reports <baseline>` on a local build of
   the head: count ≥ baseline, skips only `MerossLive*`. For #10–#11, CI's skip guard passes.
6. **Same tree:** `check-gate.sh <head> origin/<branch> [docs/DESIGN.md]` gives "tree matches".
7. **Hardware still off:** the gate's checks pass (`door: simulated`, `command-mode: "off"`,
   `command-channels: [1]`, no local config or secrets committed).
8. **Security unchanged:** `git diff <prev main> <head> -- src/main/java/home/casita/security
   src/main/resources` is exactly the PR's reviewed diff (compare `git diff ... | sha256sum` with the PR's
   own diff).
9. **After the merge:** push CI on `main` is green, and `main^{tree}` is the PR's tree.

**Stop at once and report** if CI fails, a conflict appears, a security diff differs from the reviewed one,
any live-hardware default changes, an expected test is skipped or the count drops, or the tree differs from
the validated one. Don't fix forward inside the merge sequence; a fix gets its own PR.

## 8. Batches and approvals

| Batch | PRs | Approval needed before it starts |
|---|---|---|
| **1** | #2, #3 | **A1:** the operations below |
| 2 | #4, #5, #6 | **A2:** mark #5 and #6 ready; decide on H1 (fix right after the stack, or before #6) |
| 3 | #7 | **A3:** mark #7 ready. Merging changes nothing in local worktrees (the iPhone preview is untouched) |
| 4 | #8, #9 | **A4:** lift "do not merge" on #8 and #9; mark them ready |
| 5 | #10, #11 | **A5:** M1 sign-off for Firestore; mark ready; give the M1 integration tree hash for the final check |
| docs | #1 | **A6:** add the banner commit to `docs/design` (a push), then merge #1 (any time; it doesn't overlap) |
| after | H1, H2 fixes | **A7:** open the fix PRs (branches in section 4) |

### Batch 1: exact GitHub operations (none done yet)

PR #2 (base `main` is unchanged since its CI ran, so its run on `a01b0bc` tested the real merge result):

1. Gate check: `pull_request_read get #2`, with head `a01b0bc66f6e82924b930c7dda7505e636ef056d`, base
   `main` @ `739c0ab33ea39cb54da935eb9b534b701dc8c233`, `clean`, `build` green.
2. `merge_pull_request` owner `ShirBental`, repo `CasitasGarage`, `pullNumber: 2`, `merge_method: merge`,
   `sha: a01b0bc66f6e82924b930c7dda7505e636ef056d` (refused if the head moved).
3. Wait for push CI on `main` to go green; `main^{tree}` = `7467a8daae3d`.

PR #3:

4. `update_pull_request` `pullNumber: 3`, `base: main` (skip if GitHub already retargeted it).
5. `update_pull_request_branch` `pullNumber: 3`, `expectedHeadSha: 9538640f7adb1d6efec084fd651bff7eb3a2ad32`.
   This merges `main` into `feat/garage-service` with one merge commit, and that commit's tree must still
   be `e4d597346355`. It starts a fresh CI run.
6. Gates 1–8 on the new head (local `check-skips.py` ≥ 24 tests, `check-gate.sh`).
7. `merge_pull_request` `pullNumber: 3`, `merge_method: merge`, `sha: <new head from step 5>`.
8. Push CI on `main` green; `main^{tree}` = `e4d597346355`. **Stop and report.**

Steps 2, 4, 5 and 7 are GitHub writes. I'll do them only after you approve batch 1.
