# Door timing: why Close used to wait ~30 s, and what changed

Both doors, Ford and Toyota, finish moving in about 5–10 s. Casita used to refuse **Close** for about 30 s after
an **Open**, even with someone standing at the garage looking at a fully open, stopped door. This is why, what changed,
and what is still deliberately refused.

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

1. **`verify-grace` defaults to 0 s** (application.yml and `GarageProperties`; PR #13). The check is at travel time +
   sensor lag.
2. **Travel time: 10 s is the expected value for both doors**, to be confirmed with `casita-door-watch` before it is
   set in production (deploy/watson/LIVE_DOORS.md, step 4). Live mode still refuses to start without each door's own
   measured `travel-times` and `sensor-lags`.
3. **"I've checked the door" (rule 9 in `GarageService`).** The wait is now treated as two parts:
   `travelTime` (the door may still be **moving**: never skipped) and `sensorLag` (the door has stopped but the
   **report** may be late: a person looking at the door can stand in for it). From travel time on, the door's status
   has `canCloseIfChecked: true`, and the app shows **Close** with the note "Waiting for the sensor. At the garage?
   Look, then close." Tapping it asks: *"Look at the Ford door. Is it all the way up and stopped, with the doorway clear?
   Only close it if you can see it."* Confirming sends `POST /api/doors/{door}/close?checked=true` (the older Ford-only `POST /api/close` takes the same parameter, under the same rules).

A checked close goes through only when **all** of these hold, checked on the server:

| Condition | Why |
|---|---|
| The door's commands are on, closing from the app is allowed, nothing is held after a stuck or lost move | Rules 7 and 8 unchanged |
| The sensor can be read right now, and reads **not closed** | The person's word never overrides a reading. A reading still at "closed" (the sensor hasn't caught up with the open) refuses: the opener itself may think the door is closed and could take a close as a button press |
| No command of unknown outcome is pending (`UNCONFIRMED`) | It could still reach the opener late |
| Casita's own acknowledged open was sent **at least `travelTime` ago** and the only change Casita has seen since is the one it caused; or Casita **saw** the door change to "not closed" (wall button, remote) at least `travelTime` ago | Any movement Casita can know about has finished. A wall-button reversal during the open (closed again, then opened again) is refused until the open's own check, then counts from the new "not closed". Before that, even a checked close is `BUSY` |
| Not right after a restart or a resume | Casita didn't see that door open, and a command of unknown fate from before could still land: the check doesn't shorten the wait there (travel + lag, then an ordinary close) |
| The opener doesn't report a change of its reading less than `travelTime` ago (when it reports a change time; ours reports none, `lmTime` 0) | A change Casita missed between two reads. Used only to refuse |
| The person answered the app's question within 15 s | A dialog left open says nothing about the door now; the app asks for a fresh look instead (client side) |
| No other close is pending | One movement at a time; a second checked close is `BUSY` |

When it goes through: the pending open is closed out as `OPEN_INFERRED` (its scheduled check then does nothing), the
person's check is recorded as `CHECKED` under their name, then the close as `MOVING`, and the close is judged exactly
as before: `CLOSED_CONFIRMED` when the sensor reads closed, `STUCK` (and held) if it doesn't within travel + lag. The
command still passes `MerossDoor`'s own re-read just before sending. `OPEN_INFERRED` and `CHECKED` are recorded before
the close is sent: if the send then fails (`ERROR`, or `UNCONFIRMED`), the history shows the check followed by that
outcome, and an unchecked close still waits for the open's travel + lag. A checked close of a door that has already
settled is an ordinary close and records no `CHECKED`.

Without a check (a phone away from the garage, a Shortcut), nothing changed except the grace: Close waits for the
check at travel + lag.

## The user's scenario, second by second

Someone at the garage opens a door from the app at 0 s. The door stops at about 8 s. Travel time is configured at 10 s and the sensor lag at 25 s (`GarageServiceTest`, "the user's scenario"):

| Meross reading | Close (after looking) works at | Before this change |
|---|---|---|
| Changes to not closed within a few seconds (as Ford's did) | **10 s**, the travel time | 37 s (10 + 25 + 2) |
| Still "closed" at 25 s (as Toyota's was once seen) | **25 s**, the moment the reading changes | 37 s |
| Travel time left at the old 22 s | 22 s | 22 + lag + 2 s |

So the wait becomes the **travel time** when Meross reports promptly. When Meross is stale, the wait is however long it stays stale: nothing safe can be done sooner with this sensor (below). Away from the garage (no look), Close waits for the check at travel + lag as before.

## What `checked=true` is, and isn't

It is the signed-in member saying "I looked". It is **not** proof that anyone is at the garage or that the doorway is clear. Any signed-in member can send it, from anywhere, including a browser that signed itself back in without Face ID (PR #16), and Casita can't verify presence. So its effect is limited to what a wrong answer could cost:

- It never skips the travel time, never overrides a reading, a hold, an unknown command, a restart or a resume, and never applies to opens or Shortcuts.
- If someone confirms without looking, the close goes to a door that Casita has watched long enough for any movement it knows of to be over, and whose sensor says it isn't closed. That is what an ordinary close does at travel + lag, minus one case: a remote or wall button that started moving the door in the last `sensorLag` seconds without the reading showing it. A close then can stop or reverse that movement. This is the same risk docs/MEROSS.md already describes for a door moved by the remote while its reading is stale.
- Obstructions are still handled by the opener's own photo eyes and auto-reverse, and a close that doesn't finish ends `STUCK` and holds the door.

## Exactly when Close is allowed

| Situation | Close without a look | Close after a look |
|---|---|---|
| Sensor unreadable | refused (`UNKNOWN`) | refused |
| Held after a stuck or lost move | refused (`HELD`) | refused |
| Commands for this door off, or closing from the app off | refused (`DISABLED`) | refused |
| A command of unknown outcome pending | refused (`BUSY`) | refused |
| Another close pending | refused (`BUSY`) | refused |
| Casita's own open, before its travel time | refused | refused |
| Casita's own open, after travel time, reading still "closed" | refused | refused (the opener may think it is closed) |
| Casita's own open, after travel time, reading not closed, no other change since | refused until travel + lag | **allowed** |
| Same, but the reading changed again since (wall-button reversal) | counts from the newest change | refused until the open's own check (travel + lag); after that, allowed once the newest change has had its travel time |
| Casita's open had an unknown outcome (`UNCONFIRMED`) | refused until its check; the door then counts from when Casita saw it change, not from the command | the same |
| Door opened by wall button or remote, seen changing | refused until travel + lag after the change | allowed after travel time from the change |
| Just after a restart or a resume (no change seen) | refused until travel + lag | refused until travel + lag |
| The opener reports a change of its reading less than travel time ago | as above | refused |
| Settled (travel + lag passed) | allowed | allowed (an ordinary close, no `CHECKED` entry) |

The app offers Close exactly in the allowed cases. `GarageServiceTest#theAppOffersACheckedCloseExactlyWhenTheServerWouldSendIt` checks it for every second of five scenarios.

## What a binary sensor can't support, and the minimum extra verification

- **A reading still at "closed" after travel time.** If the MSG200 reports "open" only after its own travel timer, a
  person can see an open door while Casita still reads "closed". Casita can't send a close then: the opener's own
  state is "closed" and a close may be ignored (`execute: 0`) or act as a button press. The fix is a faster or second
  signal, not an override. In order of cost:
  1. Read the opener's `Appliance.GarageDoor.MultipleConfig` (read-only) to learn each channel's configured
     open/close duration. `casita-door-watch` now prints them first (numeric fields only), if the opener answers. If it is longer than the real 10 s, shortening it in the Meross app (if offered there) shortens
     the report delay. **Changing it is a device setting: needs approval and a supervised test.**
  2. Use the opener's signed PUSH notifications (already traced on `/app/<userId>/subscribe`, not used): the Meross app
     showed Toyota open before `System.All` did. Measure `sensorLag` again with them.
  3. A second sensor at the fully-open position (a tilt or reed sensor) would prove "fully open", which today is only
     inferred.
- **Changes between two reads.** Casita knows of a change only when it reads the sensor (the app polls; a command
  reads first). A door that leaves "closed", comes back and leaves again between two reads looks unchanged. The opener
  would say when its reading changed (`lmTime`), but ours reports 0, so nothing catches this today; the person's look
  is what covers it. The PUSH notifications above would.
- **A door moved by the remote while its reading is stale** (docs/MEROSS.md, "What it shows"): unchanged, and not
  fixable by timing. The person's check covers it only when the person is at the door.

## Tests

`GarageServiceTest` ("a person at the door stands in for the sensor's delay"): checked close after travel time; refused
before travel time; refused while the sensor still reads closed (and a duplicate open stays `BUSY`); the open's
scheduled check does nothing afterwards; a checked close that doesn't close ends `STUCK` and holds; a second checked close
is `BUSY`; eight concurrent checked closes send one command; an `UNCONFIRMED` open is never ended by a check; wall
button, restart and resume each wait travel time first; a settled door's checked close records nothing extra; an unreadable
sensor, closing switched off and a door switched off all refuse; no grace means the check is at travel + lag.
`GarageControllerTest`: `?checked=true` through the API, CSRF required, nothing recorded for refusals.
