# Release readiness: door wait, staying signed in, Siri open and close for Ford and Toyota

Release candidate: `release/rc-door-auth-siri`, which is `feat/public-https` plus #15, #16 and #17 (draft PR #18 runs CI on it).
**Nothing is merged into a release branch or deployed, no production setting is changed, and no live command has been
sent. Each of those needs Shir's approval.**

## 1. The door delay: root cause and what remains

**Root cause.** After an open, `GarageService` refuses every command until it checks the sensor at
`travelTime + sensorLag + verifyGrace`. A close can end early (the sensor proves "closed"), but an open can't: "not
closed" proves only that the door left the closed position. So Close was refused (`BUSY`) for the whole window. In
production that window adds three things:
- Ford's configured travel time. The setup guide said 22 s, which was how long the first live test *watched* the door,
  not a stopwatch time. Neither door has been timed yet; the real value comes from section 6.
- The sensor lag. Toyota's reading was once seen trailing the door by 25 s or more. This is Meross reporting late, not
  the door moving.
- A further 2 s of grace.

The exact values on watson are in `/etc/casita/casita.env` (not in git); read them before deploying (section 7).

**Fix (#15).** The wait is split into **travel time**, which is never skipped, and **sensor lag**, which a person who
has looked at the door may skip ("Close after a look"). Grace is now 0 s. With a prompt Meross reading, Close works at
**the door's travel time** instead of travel + lag + 2 s (for example 10 s instead of 37 s with a 10 s travel time and
25 s lag; the real numbers come from section 6). When Close is allowed and when it is refused is
tabled in docs/DOOR_TIMING.md ("Exactly when Close is allowed"). The app offers Close exactly in the allowed cases,
which a test checks for every second from 0 to 60 s of eight scenarios.

**What `checked=true` is not.** It is not proof that anyone is present or that the doorway is clear. Any signed-in
member can send it, including a browser that signed itself back in from far away. Its effect is therefore limited to the
one wait that is about reporting, not movement. The worst case of a false "I looked" is a close reaching a door that a
remote started moving within the last lag seconds without the reading showing it: the same risk an ordinary close
already carries with a stale reading. The opener's photo eyes and auto-reverse still apply, and a close that doesn't
finish ends `STUCK` and holds.

**Remaining limitations (binary sensor).**
- If Meross still reports "closed" after the door is up (seen once on Toyota), Close stays refused until the reading
  changes. A close sent against the opener's own "closed" state could be ignored or act as a button press, so there is
  no safe override. Smallest practical improvement, in order:
  1. Read the opener's own per-door timers. `casita-door-watch` now prints `Appliance.GarageDoor.MultipleConfig` first
     (read-only). If they are longer than the measured travel time, shortening them is a device setting change (needs approval and
     a supervised test).
  2. Use the opener's signed PUSH notifications, which reported Toyota open before `System.All` did.
  3. Add a sensor at the fully-open position.
- Changes between two reads are invisible: our opener reports no change time (`lmTime` 0). The person's look covers
  this; PUSH would fix it.
- After a restart or a resume, the look doesn't shorten the wait (travel + lag), because a command of unknown fate
  could still land.

## 2. Security issues found and resolved

| Where | Finding | Resolution |
|---|---|---|
| #16 | A revoked browser authorized again could revive copied cookies | Cookie (v2) bound to the browser's authorization (invite id), compared exactly; same-second case covered by a fixed-clock test; store contract test for all three stores |
| #16 | "Face ID every 30 days" not enforced for a session kept active | Every passkey session ends at Face ID + max-age, checked per request; renewal never moves it |
| #16 | Docs overstated: a copied cookie jar now works without Face ID | ACCESS.md "Limits" rewritten; sign-out clears only that browser |
| #16 | Idle deadline not renewed by use | Renewed once a day while in use |
| #16 | Filter order relied on insertion order | `AccessCheckFilter` registered explicitly after `RememberedSignInFilter` |
| #15 | Early close erased a wall-button reversal during the open | Sensor changes counted per movement; a newer movement counts from its own change |
| #15 | Restart/resume let the look shorten the wait | Rule 9 doesn't apply until a change is seen |
| #15 | Stale confirmation dialog | Counts only if answered within 15 s (client) |
| #17 | Usage line and test names | Fixed |
| RC (Tony) | After an `UNCONFIRMED` open that landed late, the check backdated the door's start to the command, so an ordinary close was allowed while it was still opening | Only an acknowledged command's start stands for the door's; tests (late landing, consistency check extended to 8 scenarios × 61 s) |
| RC (Natasha) | Lowering max-age didn't shorten a restored session's limit | `RememberedSignIns.expiry` used for the session and the renewed cookie; test |
| RC (Natasha) | A passkey session without a Face ID limit wasn't capped | Refused; the handler always sets it; test |
| RC | `canCloseIfChecked` true for a door whose commands are off (cosmetic) | False now |

## 3. Test results

**CI on PR #18, final RC code: CI_RESULT_PLACEHOLDER**

`mvn verify` locally on the final RC code (macOS, Java 21): LOCAL_RESULT_PLACEHOLDER.

Earlier, before Siri close: CI on `eb07df1` (run 43456394466) 914 tests, 0 failures, 7 skipped.

| Skipped | Count | Why | Runs where |
|---|---|---|---|
| `MerossLiveCommandTest`, `MerossLiveCheckTest` | 7 | Talk to the real opener; only with `-Dmeross.live=true` | Never in CI; supervised, with approval |
| `FirestoreStartupVerifyTest` | 5 | Local only: need the Firestore emulator | Ran and passed in CI |
| `StagingStartupTest` (1 case) | 1 | Local only: this Mac has `config/meross.local.json` | Ran and passed in CI |

Also not run locally (0 tests, not counted as skipped): `FirestoreSecurityStoreTest` (76 in CI) and
`FirestoreAccessFlowTest` (60 in CI), which need the emulator. In CI they ran and passed, including the new contract
test that a re-authorized browser gets the new invite id.

**Verified by automated tests:** door rules against a fake door (timelines, stale readings, reversals, duplicates,
concurrency, holds, restarts); the API and CSRF; remembered sign-in through the real filter chain (file store) and in
the store contract (file, DynamoDB, and Firestore in CI); Shortcut scoping for both doors.

**Not verified (needs hardware or the phone):** real travel times and sensor lag, the opener's behavior, MultipleConfig,
Siri on a locked phone, cellular, Safari and Home Screen cookie behavior across restarts.

## 4. PRs: dependency and merge order

```
release/watson-staging-rc  <- #12 feat/public-https  <- #15 fix/door-wait
                                                     <- #16 feat/remembered-sign-in
                                                     <- #17 feat/shortcut-toyota
```

1. **#15** into `feat/public-https`. It includes and supersedes #13, which is now closed.
2. **#16** into `feat/public-https`.
3. **#17** into `feat/public-https`.
4. **#12** (`feat/public-https`) into `release/watson-staging-rc`, the branch watson's releases come from.

#15–#17 are independent and merged cleanly in this order into the RC. #18 is CI only: don't merge it. The older stacked
drafts #7–#11 are already integrated into `release/watson-staging-rc`; leave them as they are.

## 5. Production configuration changes

| Setting (`/etc/casita/casita.env`) | Change | When |
|---|---|---|
| `CASITA_GARAGE_TRAVELTIMES_FORD`, `_TOYOTA` | The measured value per door (section 6 rounding; no value assumed) | After the measurement (section 6) |
| `CASITA_GARAGE_SENSORLAGS_FORD`, `_TOYOTA` | The measured longest lag | Same |
| `CASITA_GARAGE_VERIFYGRACE` | Remove if set (default is now 0 s) | With the above |
| `CASITA_SECURITY_REMEMBER_MAXAGE` / `_IDLE` | None (defaults 30d / 14d); `0s` turns remembering off | Only to change |
| `CASITA_MEROSS_COMMANDCHANNELS` | Unchanged; Toyota (`1,2`) only after its supervised test | Separately |
| `CASITA_GARAGE_SETTLEBYSIGHT_*` (rule 10) | **None: leave unset** (off for both doors) | Only after section 10's supervised test and approval |

New on first start: `/var/lib/casita/remember.key` (owner-only). Back it up with the disk or accept one Face ID per
browser if it's lost.

## 6. Supervised hardware validation (someone at the garage, doorway clear)

Steps 1–2 are read-only. Steps 3 on send live commands and need Shir's approval and a person watching the door.

1. **Measure (read-only).** On watson, two terminals:
   ```bash
   sudo casita-door-watch 15 1 | tee ~/door-watch-$(date +%F).log
   ```
   ```bash
   while read -r m; do echo "$(date +%T.%3N) $m"; done | tee -a ~/door-marks-$(date +%F).log
   ```
   With the wall button, at least three runs each way per door. In terminal 2, type `ford open press`, `ford open stop`
   (and so on) the moment each happens. Terminal 1 marks `<- Ford changed`. All times use watson's clock.
   - Travel = longest (stop − press), rounded up, plus 1 s. Whatever was measured; don't use an assumed value.
   - Sensor lag = longest (change − stop, 0 if earlier), rounded up, plus 1 s.
   - A reading still `CLOSED` with the door fully up: note how long.
   - Opener timers: the first line `Opener door settings (Appliance.GarageDoor.MultipleConfig)` (a GET; printed only once
     the RC is installed). Record it; change nothing.
   - Details: deploy/watson/LIVE_DOORS.md step 4.
2. **Configure.** Set the four values in section 5, `sudo systemctl restart casita`, `casita-health`.
3. **Ford, live.** Open from the app while watching. Before travel time, Close is refused. At travel time, Close shows
   "Waiting for the sensor…". Tap, confirm while looking: it closes, history shows "Shir saw Ford open and stopped".
4. **Ford.** Two quick Opens: one command. Open, then reverse with the wall button mid-travel: Close after a look is
   refused until the new movement has had its travel time.
5. **Stale "closed".** If seen in step 1: Close is refused until the reading changes; nothing is sent.
6. **Sign-in.** Restart Casita: the phone opens without Face ID. Revoke a test browser: it goes to sign-in at once.
7. **Siri, four phrases.** `casita-access shortcut-phone` per phone, the four Shortcuts (docs/SHORTCUTS.md step 2), then
   step 4 there: open, close too soon (refused, spoken reason), close after travel + lag (closes), close a closed door
   (refused, "sensor says closed"), cellular, locked, revoke one token (the other three keep working).
8. **Toyota.** Its own supervised command test (docs/MEROSS.md), then `CASITA_MEROSS_COMMANDCHANNELS=1,2`, then steps
   3–5 and 7 for Toyota.

## 7. Deployment and rollback

Before: `sudo grep -E 'TRAVELTIMES|SENSORLAGS|VERIFYGRACE|COMMAND' /etc/casita/casita.env` (record the values), then
`sudo cp -p /etc/casita/casita.env /etc/casita/casita.env.bak-$(date +%F)`.

1. After approval and the merges in section 4: from a clean checkout of `release/watson-staging-rc`, run
   `deploy/watson/deploy-release.sh deploy/watson/watson.conf`, check it, then add `--apply`. The CI build is
   installed, and it rolls back on its own if unhealthy.
2. `casita-health`. Each phone signs in with Face ID once (existing sessions have no remembered sign-in yet).
3. Hardware validation (section 6); settings changes only then.

**Rollback:**
- Settings: restore the `.bak` file, `sudo systemctl restart casita`.
- Code: `sudo casita-rollback` (previous jar; no data migration). **Revoke any Toyota Shortcut tokens first**: the old
  endpoint would still accept them.
- Kill switches without a rollback:
  - `CASITA_MEROSS_COMMANDMODE=off` stops all door commands.
  - Delete `remember.key` and restart to end every remembered sign-in.
  - `CASITA_SECURITY_REMEMBER_MAXAGE=0s` turns remembering off.
  - Revoke Shortcut tokens with `casita-access`.

## 8. Final reviews of the combined code

- **Natasha (security): releasable, no blockers.** Verified on the combined code:
  - The Shortcut chain (`@Order(1)`, `/shortcut/**`, stateless, bearer only) and the browser chain never overlap. No
    cookie authenticates `/shortcut/**`, and no token reaches `/api/**` or `checked=true`.
  - Cookie attributes are right.
  - CSRF applies to the first request after a restart and to `checked=true`.
  - Revocation and fail-closed paths hold end to end.
  - Nothing secret is logged.

  Both should-fix items are fixed (section 2). Accepted notes, not changed:
  - **`checked=true` from a remembered session far away:** allowed, and documented. Requiring a recent Face ID for it
    would add friction; it stays an option.
  - **Invite ids carry only 32 random bits (now the binding value):** a collision is about 2^-32 and can't be chosen by
    an attacker.
  - **The global Shortcut rate limit:** availability only.
  - **`JSESSIONID` and `XSRF-TOKEN` aren't `__Host-` cookies:** this predates the RC.
- **Tony (door safety): releasable pending supervised hardware validation, no blockers.** Every earlier finding is
  confirmed fixed. He found no path that sends a command to a door that may be moving, sends one twice, or reverses
  one. Ford and Toyota stay independent, and Shortcuts can't use `checked`. His should-fix and doc fixes are in
  (section 2).

## 9. Final verification (2026-11-13)

- CI on the final RC code: green (section 3).
- Replaying the merge order: #15, #16 and #17 merge into `feat/public-https` with no conflicts, and the result is
  identical to the RC's code (the only difference is this report, which lives only on the RC branch). #12 then merges
  into `release/watson-staging-rc` with no conflicts.
- PR states: #12 and #15–#17 open drafts with green CI; #13 closed as superseded by #15; #18 draft, CI only.
- Natasha's and Tony's findings: all fixed in code with tests (section 2), and their accepted notes documented
  (section 8). No open security findings.
- Since `eb07df1`: Siri close and the default-off rule 10 (section 10), with Natasha's and Tony's final reviews.

Open items that are not code: the hardware validation (section 6), and Shir's approval for the merges, the deployment
and each settings change.

## 10. Siri open and close (added 2026-11-13)

**What it does.** Four Shortcuts per iPhone: "Open Ford Garage", "Close Ford Garage", "Open Toyota Garage", "Close
Toyota Garage". Hands-free, over cellular, with no Face ID, browser or app. Each is its own token, one per phone, door
and action, each revocable on its own. `casita-access shortcut-phone <member> "<label>"` makes all four. Tokens made
before this stay open-only: the action is fixed at creation, and nothing changes it.

**Safety.** Siri's close is `GarageService.close(who)`, the same ordinary close as the app's Close button. A Shortcut
can't send `checked` (400, nothing sent). Every lock applies: an unchecked movement, a door that may be moving, a hold,
an unknown outcome, an unreadable sensor, a door switched off, and a sensor that says closed. Each refusal is spoken
with the reason and that nothing was sent.

**The sensor-watch question (Shir's critical concern), and the answer.**
- A closed/not-closed sensor can't prove a door has stopped. A rule that closed one travel time after Casita *saw* the
  reading change (rule 10, "settle by sight") would hold for Casita's own open. But Tony found (B1) that a reading stale
  at "closed" when the open is sent makes the open a press that closes the door. The reading's late catch-up then looks
  exactly like the open's own change, and a close at seen + travel would open the door while the log said closed.
- So rule 10 **ships off for both doors** (`casita.garage.settle-by-sight`, empty), and doors without it aren't even
  watched. Siri and the app from away keep the conservative wait: the open's check at **travel + lag** (+ grace, 0 s).
  With the default config, door behaviour is exactly rules 1–9: the same for the app and Siri.
- Tests: `byDefaultSiriWaitsTheFullTravelTimePlusLagAfterAnOpen`, `byDefaultAStaleClosedCatchUpIsNeverTakenForTheOpensOwnChange`,
  `withoutSettleBySightNothingIsWatched`, and `anOrdinaryCloseIsOfferedExactlyWhenTheServerWouldSendIt` and the checked
  variant over 10 scenarios × 61 s, with the switch both off and on. The scenarios: Siri with nobody asking, wall-button
  reversal, stale reading, restart, resume, unconfirmed open, an opener-reported change, and a missed change. Also
  `theWatchNeverChecksACloseEarly` (touch-down auto-reverse ends `STUCK`). Concurrent commands: the existing
  concurrency tests (eight simultaneous closes send one command).
- These run against a fake door, which models the reading, not a physical door separate from it. That is why the
  switch is off until a supervised test shows, per door: its reading never leaves "closed" later than the door is fully
  open; a press while opening stops it; and for Casita's own command, motion starts no earlier than the acknowledgement
  and the reading changes no earlier than motion (docs/DOOR_TIMING.md, "What a door needs"). Toyota, as observed, fails
  the first.

**Reviews of the combined code.**
- **Natasha (security): no blockers.** Scopes, independent revocation, no upgrade path, `checked` refused, no token on
  `/api/**` or `/admin/**`, nothing secret logged or on a command line, fail-closed unchanged, and no way to switch
  rule 10 on except operator configuration plus a restart. Her should-fixes are fixed:
  - the replay and closing-onto-someone wording;
  - two task threads;
  - the watch's read-skip and error handling;
  - any failure to read at a check now counts as `LOST` (held, told).
- **Tony (door safety): blocker B1 fixed by the default-off switch; no blockers; safe with the default config.** His
  should-fixes are fixed:
  - the watch only for switched-on doors, never checking a close early;
  - door names validated;
  - `canClose` false for a door switched off;
  - the docs on what the sensor can't tell;
  - DESIGN.md hardware prerequisite 9;
  - the renamed test.

**Hardware still to check (with approval, someone watching):** section 6 step 7 for each phone, Siri on a locked phone
(Apple doesn't document it for "Get Contents of URL"), cellular, and how long a Siri close after a Siri open waits with
the measured travel + lag.
