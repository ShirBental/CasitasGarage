# Release readiness: door wait, staying signed in, Siri for Toyota

Release candidate: `release/rc-door-auth-siri`, which is `feat/public-https` plus #15, #16 and #17 (draft PR #18 runs CI on it).
**Nothing is merged into a release branch or deployed, no production setting is changed, and no live command has been
sent. Each of those needs Shir's approval.**

## 1. The door delay: root cause and what remains

**Root cause.** After an open, `GarageService` refuses every command until it checks the sensor at
`travelTime + sensorLag + verifyGrace`. A close can end early (the sensor proves "closed"), but an open can't: "not
closed" proves only that the door left the closed position. So Close was refused (`BUSY`) for the whole window. In
production that window adds three things:
- Ford's configured travel time. The setup guide said 22 s, which was how long the first live test *watched* the door,
  not a stopwatch time. Both doors actually move in 5–10 s.
- The sensor lag. Toyota's reading was once seen trailing the door by 25 s or more. This is Meross reporting late, not
  the door moving.
- A further 2 s of grace.

The exact values on watson are in `/etc/casita/casita.env` (not in git); read them before deploying (section 7).

**Fix (#15).** The wait is split into **travel time**, which is never skipped, and **sensor lag**, which a person who
has looked at the door may skip ("Close after a look"). Grace is now 0 s. With a prompt Meross reading and travel time
set to the measured 10 s, Close works at **10 s** instead of 37 s. When Close is allowed and when it is refused is
tabled in docs/DOOR_TIMING.md ("Exactly when Close is allowed"). The app offers Close exactly in the allowed cases,
which a test checks for every second of five scenarios.

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
     (read-only). If they are longer than the real 10 s, shortening them is a device setting change (needs approval and
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

`mvn verify` on the release candidate (local, macOS, Java 21): **778 tests, 0 failures, 0 errors, 13 skipped**.
CI on PR #18 (with the Firestore emulator), on the RC before the final review fixes: **911 tests, 0 failures, 7 skipped**
(the live Meross tests only). Check that the run for the final RC commit is green too before approving.

| Skipped | Count | Why | Runs where |
|---|---|---|---|
| `MerossLiveCommandTest`, `MerossLiveCheckTest` | 7 | Talk to the real opener; only with `-Dmeross.live=true` | Never in CI; supervised, with approval |
| `FirestoreStartupVerifyTest` | 5 | Need the Firestore emulator | CI |
| `StagingStartupTest` (1 case) | 1 | This Mac has `config/meross.local.json` | CI |

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
| `CASITA_GARAGE_TRAVELTIMES_FORD`, `_TOYOTA` | The measured value, expected `10s` (never below what was measured) | After the measurement (section 6) |
| `CASITA_GARAGE_SENSORLAGS_FORD`, `_TOYOTA` | The measured longest lag | Same |
| `CASITA_GARAGE_VERIFYGRACE` | Remove if set (default is now 0 s) | With the above |
| `CASITA_SECURITY_REMEMBER_MAXAGE` / `_IDLE` | None (defaults 30d / 14d); `0s` turns remembering off | Only to change |
| `CASITA_MEROSS_COMMANDCHANNELS` | Unchanged; Toyota (`1,2`) only after its supervised test | Separately |

New on first start: `/var/lib/casita/remember.key` (owner-only). Back it up with the disk or accept one Face ID per
browser if it's lost.

## 6. Supervised hardware validation (someone at the garage, doorway clear)

1. Read-only: `sudo casita-door-watch 10 1`. Note what it prints for MultipleConfig. Work each door twice each way with
   the wall button. Record press → stop (travel) and stop → reading change (lag) per door and direction.
2. Set the timings (section 5), restart, `casita-health`.
3. Ford, live: open from the app, watching. At travel time the app shows Close with "Waiting for the sensor…". Tap,
   confirm while watching: it closes, and the history shows "Shir saw Ford open and stopped".
4. Ford: tap Close before travel time: refused. Tap Open twice quickly: one command.
5. Ford: open, then press the wall button mid-travel (reversal): Close after a look is refused until the new movement
   has had its travel time.
6. If Meross reports "closed" after the door is up: note for how long. Close should be refused until it changes.
7. Restart Casita: the phone opens the app without Face ID; the doors show. Revoke a test browser: it goes to sign-in at
   once.
8. Siri: docs/SHORTCUTS.md step 4 (unlocked, cellular, locked, revoke one door's token).
9. Toyota: the same after its own supervised command test (docs/MEROSS.md), then `COMMANDCHANNELS=1,2`.

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

Open items that are not code: the hardware validation (section 6), and Shir's approval for the merges, the deployment
and each settings change.
