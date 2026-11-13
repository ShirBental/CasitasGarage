# Door timing: why Close used to wait ~30 s, and what changed

Neither door's travel time has been measured with a stopwatch yet (estimates range from 5–10 s to the 20 s Ford was once
watched for). Casita used to refuse **Close** for about 30 s after
an **Open**, even with someone standing at the garage looking at a fully open, stopped door, and Siri had the same wait.
This is why, what changed, which restrictions protect against real hazards and which only against wrong outcomes, and
what is still refused.

## Root cause

`GarageService` (one per door) refuses every command while a movement is pending, until it has checked the sensor at

```
check = travelTime(door) + sensorLag(door) + verifyGrace
```

1. **An open can't finish early.** A close ends as soon as Casita reads "closed" (on the app's next status poll, about
   every second after a command) (the only position the binary
   sensor proves). An open has no such signal: "not closed" proves only that the door left the closed position, so the
   open always stayed pending for the whole check window. Close was refused (`BUSY`) the whole time.
2. **Three different waits were added together:**
   - `travelTime`: how long the door itself moves. Production is set per door on watson
     (`CASITA_GARAGE_TRAVELTIMES_*`); the setup guide suggested **22 s for Ford**, from the 20 s the first live close
     test *watched* the door, not from a stopwatch.
   - `sensorLag`: how long the Meross reading can trail the door (Toyota's was seen to trail by 25 s or more; according
     to meross_lan's sources and traces, the opener reports a door's new state only after its own configured travel
     timer, 15 s by default, and the cloud adds its own delay; not yet confirmed on our MSG200). This is **reporting delay, not movement**: the door has stopped, the report hasn't arrived.
   - `verifyGrace`: a further 2 s of margin.

   With Ford at 22 s + its lag + 2 s, the lock lasts about 30 s. The values actually set on watson live in
   `/etc/casita/casita.env` and are not in this repository: confirm them with
   `sudo grep -E 'TRAVELTIMES|SENSORLAGS|VERIFYGRACE' /etc/casita/casita.env` on watson.
3. **Settling a door opened by the wall button** (or seen open after a restart) also waits `travelTime + sensorLag`
   before Close is allowed, for the same reason.

The UI adds no wait of its own: it polls faster for 30 s after a command (`FAST_AFTER_COMMAND_MS`), which only
changes how often it asks.

## What changed

1. **`verify-grace` (2 s) now only delays a movement's check** (confirming a close, inferring an open), never the next
   command.
2. **Travel time is whatever each door is measured at** (deploy/watson/LIVE_DOORS.md step 4; no value is assumed, 10 s
   below is an example). Live mode still refuses to start without each door's measured `travel-times` and `sensor-lags`.
3. **The opposite command waits for the door's travel, not the sensor's delay (rule 9).** After Casita's own
   acknowledged open, Close (the app's button, from anywhere, or Siri) works from `travelTime` after the command. No
   look is needed. The app's "I've checked the door" still exists and is recorded (`CHECKED`), but it no longer changes
   any timing, so the app now just shows **Close**.
4. **A close succeeds only if the door stays closed (rule 10).** Casita reads the sensor every 2 s while its own
   command is pending, with or without anyone asking. A close that reads "closed" lets an Open go at once, but stays
   "closing" in the app and in Siri's answers. It is recorded as `CLOSED_CONFIRMED`, and the family told "All tucked
   in", only at its check (`travelTime + sensorLag + verify-grace` after the command) if the reading still says "closed".
   - A reading back at "not closed" before then ends it `STUCK`: held, with "closed, then opened again".
   - An Open sent before then ends it `SUPERSEDED`: not confirmed, and nobody is told it closed.

## Hazards versus wrong outcomes

| Restriction | What it prevents | Kind |
|---|---|---|
| The opener's photo eyes and auto-reverse (the opener's, untouched) | Closing onto a person, a pet or a car | **Physical hazard** |
| No command against the reported position (rule 2): no close while it reads "closed", no open while "not closed" | The command acting as a button press: a door moving the wrong way | Wrong outcome. It becomes a hazard only where the opener's own protections don't apply, i.e. never on a close the photo eyes see |
| Commands off per door, remote close off (rules 7, 8) | Any remote movement, as configured | Operator choice |
| One movement at a time, duplicates and concurrent commands refused (rule 3) | A second press stopping or reversing the first | Reliability |
| No close before `travelTime` after the last movement Casita knows of (rule 9) | A close reaching a door still moving: it stops or reverses | Reliability |
| After a restart or resume: the full `travelTime + sensorLag` | A command of unknown fate from before landing late | Reliability |
| Unknown outcomes never retried, checked by the sensor (rule 4) | A double press | Reliability |
| Hold after `STUCK` or `LOST` until someone resumes (rule 6) | Moving a door nobody has checked after it didn't do what it was told (an obstruction, an auto-reverse) | **Physical hazard** (repeating a close onto an obstruction) and reliability |
| A close confirmed only at its check, if still "closed" (rule 10) | Reporting a failed, stopped or reversed close as closed | Reporting accuracy |
| The opener's warning (beep, flash) before an unattended close (the opener's; not yet verified on ours) | A silent remote close nobody nearby expects | **Physical hazard** if missing: a hardware check |

What was dropped: the `sensorLag` wait before a close after Casita's own open. It protected against nothing that moves
the door: the report is late, the door is not. It only made a stop or reversal from an unseen cause less likely in the
first seconds, and those are reliability problems that rule 10 now detects.

## The user's scenario, second by second

A door opened by Casita at 0 s. Travel time 10 s, sensor lag 25 s (`GarageServiceTest`, "Siri and the app from away"):

| Meross reading | Close (Siri, or the app) works at | Before |
|---|---|---|
| Leaves "closed" within a few seconds (as Ford's did) | **10 s**: the travel time | 37 s (10 + 25 + 2) |
| Still "closed" at 25 s (as Toyota's was once seen) | **25 s**: the moment the reading changes | 37 s |
| The wall button reverses it during the open | the open's check (35 s), or later if the newest change was less than 10 s before | 37 s or later |
| After a restart (Casita didn't see it open) | 35 s after Casita first read it | the same |

After a close that reads "closed" at 9 s, an Open works at once (the close is then `SUPERSEDED`, not confirmed).

## Plausible ways it goes wrong, and what Casita reports

| What happens | Mechanism | What Casita does | Hazard? |
|---|---|---|---|
| Travel time set too short | The close at "travel time" reaches a door still opening: a press that stops or reverses it | The close's check reads "not closed": `STUCK`, held, "didn't close" (`LiveDoorsTest`, understated travel time) | No: the door stops or goes back up. Fix: measure it |
| Wall button or remote during an open | A second movement | If Casita sees it (the watch reads every 2 s), no close until that movement has had its travel time. If it happens between two reads and never reaches "closed", it can't be seen: the close may then stop or reverse it, and the check reports `STUCK` | No |
| Obstruction during a close | The opener's photo eyes and auto-reverse send it back up | The reading never says "closed": `STUCK`, held, "didn't close" | Handled by the opener |
| Auto-reverse at touch-down | "Closed" for a moment, then "not closed" | `STUCK` at once, held, "closed, then opened again". Never "tucked in" | Handled by the opener |
| Reading stale at "closed" when Casita opens (Tony's B1) | The door was really up (wall button), so Casita's open is a press that closes it; the reading's catch-up looks like the open's own change; Siri's close is then a press that opens it again | The reading then shows "closed" (late) and "not closed" again: `STUCK`, held, "closed, then opened again". Never confirmed closed (`aStaleClosedReadingWhenTheOpenWasSentIsCaughtNotReportedClosed`); an Open in between ends the close `SUPERSEDED`, not closed | The first step is an unattended close started by an "open": a hazard only if the opener doesn't warn then (hardware check) |
| An Open while a close reads "closed" but isn't confirmed | The door may be reversing at touch-down | The close ends `SUPERSEDED` (never "closed"); the Open is a new movement, checked on its own | No |
| "Closed" reported later than the measured lag | A slower sensor than measured | `STUCK` at the check (a false alarm, never a false "closed"). Fix: measure the lag again | No |
| Lost acknowledgement | The command may still land late | `UNCONFIRMED`: no opposite command until its check, then counted from when Casita saw the door change | No |
| Two Siri requests at once, or Siri and the app | — | One command; the rest `BUSY` | No |

## What `checked=true` is now

The member saying "I looked". It is recorded with their name (`CHECKED`) when it came with a close that rule 9 allowed
before the door settled. It changes no timing and proves nothing about presence. Shortcuts can't send it (400).

## Exactly when Close is allowed (app and Siri alike)

| Situation | Close |
|---|---|
| Sensor unreadable | refused (`UNKNOWN`) |
| Held after a stuck or lost move | refused (`HELD`) |
| Commands for this door off, or remote close off | refused (`DISABLED`) |
| A command of unknown outcome pending | refused (`BUSY`) |
| Another close pending | refused (`BUSY`) |
| Casita's own open, before its travel time | refused ("may still be opening") |
| Casita's own open, after travel time, reading still "closed" | refused ("the sensor still says closed") |
| Casita's own open, after travel time, reading not closed, no other change since | **allowed** |
| Same, but the reading changed again since (a reversal Casita saw) | refused until the open's check; then allowed once the newest change has had its travel time |
| Door moved by the wall button or remote, seen changing | allowed from travel time after Casita saw the change |
| Just after a restart or a resume (no change seen) | refused until travel + lag |
| The opener reports a change less than travel time ago | refused |
| Settled | allowed |

The app offers Close exactly in the allowed cases. `GarageServiceTest#anOrdinaryCloseIsOfferedExactlyWhenTheServerWouldSendIt`
(and the checked variant) check it for every second from 0 to 60 s of ten scenarios.

## Remaining limits (with a mechanism)

- **A stale "closed" reading blocks Close until it changes.** That is up to 25 s on Toyota, as seen once. A close against
  "closed" would be a press that may do nothing or open the door. Improvements, in order: read the opener's own timers
  (`MultipleConfig`, printed by `casita-door-watch`), use its PUSH notifications, or add a sensor at the open position.
- **Changes between two reads.** A press that reverses a door and doesn't pass through "closed" within the 2 s between
  reads is invisible. The close that follows may stop or reverse that movement. It is reported `STUCK`, not prevented.
- **A reversal reported only after the close's check.** With a travel time or lag set too short, a door can reverse
  after Casita confirmed it closed. Nothing watches after the check, so that reopening shows only as an ordinary change.
  Measuring the timings with margin (and the 2 s grace) is what covers it.
- **A hold is in memory.** After a restart, a door held after `STUCK` can be commanded again without anyone resuming it.
  Rule 5's full wait still applies.
- **The opener's own change time.** Ours reports none (`lmTime` 0). If a firmware update starts reporting it, rule 9
  would refuse a close until travel time after that reported change, which could bring the long wait back on Toyota.
  The test suite would not show it; the supervised checks would.
- **"Not closed" is not "fully open".** A door stopped partway reads the same. Casita says "open" in the app and in Siri's
  answers, and the history says "open inferred".
- **Hardware assumptions** (DESIGN.md "Hardware prerequisites", and release readiness section 6):
  - the measured travel time covers every run;
  - `SETACK` means the relay fired;
  - a press while opening stops or reverses the door (either is handled);
  - the opener warns (beeps or flashes) before a remote close: the one item that is a real hazard if missing.

## Cost

One cloud read per door every 2 s while a command is pending (about 5–15 per command, fewer when the app is polling).
A read holds that door's lock for its round trip, as a status read does. Scheduled tasks run on two threads, so one
door's slow read seldom delays the other's check, and an overdue check also runs before any command or status. A failed
read changes nothing: the scheduled check decides.

## Tests

`GarageServiceTest`:
- **Siri and the app from away:**
  - the close at travel time;
  - a stale reading;
  - spoken reasons;
  - a reversal by a second command;
  - an obstruction;
  - auto-reverse at touch-down;
  - B1;
  - a late "closed" within the lag, and one beyond it;
  - an open right after a close (`SUPERSEDED`, no "closed" message), and one during a close that then reverses;
  - an open while still closing;
  - eight simultaneous Siri closes (one command);
  - repeated commands;
  - a restart.
- **Rule 9:**
  - before travel time;
  - wall button;
  - unconfirmed;
  - an opener-reported change;
  - a failed send.
- **The watch:**
  - stops with the movement;
  - skips a read made just before;
  - survives errors;
  - a failure at the check is `LOST`.

`LiveDoorsTest` (a fake MSG200 that moves each door and reports it late):
- Ford's close at its travel time reaches no moving door;
- Toyota's close at the moment its lagging reading changes;
- an understated travel time is reported `STUCK`.

`GarageControllerTest`: the API, CSRF.
