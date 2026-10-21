# Meross: how commands, replies and sensor updates work

What Casita's Meross client relies on, and how sure we are of each piece. Written before
the write path is turned on, so the choices for commands are made against what is known.

**Nothing here has been checked on our own MSG100 yet.** Everything about how the
*device* behaves is either from community reverse-engineering or an assumption. The
hardware checklist in [DESIGN.md](DESIGN.md#hardware-prerequisites) is how we'll find out.

Legend:
- **Confirmed (source)**: read in community source code, docs or issue tracker (links below).
  This is someone else's reverse-engineering, not Meross documentation, and not tested by us.
- **Confirmed (library)**: stated in the HiveMQ client's release notes.
- **Assumed**: not documented anywhere we could read. The code treats it pessimistically.
- **Unknown**: we don't know, and the design doesn't depend on it.

## Signing in and connecting

| Fact | Status |
|---|---|
| The app signs in over HTTPS; the reply carries `userid`, `key`, `token`, `domain`, `mqttDomain` | Confirmed (source) [3] |
| Signing in to the wrong region returns a redirect to the right one | Confirmed (source) [3] |
| `apiStatus` 1033 means a two-factor code is required | Confirmed (source) [3] |
| HTTP requests are signed `md5(secret + timestamp + nonce + params)` | Confirmed (source) [4] (2019 page) |
| Exact sign-in path and body (`/v1/Auth/signIn`, md5-hashed password, `encryption: 1`) | **Assumed**. Checked by the read-only live test, not by us yet |
| Which `apiStatus` values mean "token expired" | **Unknown**. Any HTTP API failure marks the session stale; the next use signs in again |
| MQTT: broker from `mqttDomain`, port 443, TLS | Confirmed (source) [3] for the host; port 443 **assumed** |
| MQTT client id `app:` + md5("API" + random uuid), username `userid`, password `md5(userid + key)` | Confirmed (source) [1] |
| Message signature `md5(messageId + key + timestamp)` | Confirmed (source) [1] |

## Topics and replies

| Fact | Status |
|---|---|
| Commands go to `/appliance/<uuid>/subscribe` | Confirmed (source) [1][2] |
| Replies to *our* commands come back on `/app/<userid>-<appid>/subscribe` | Confirmed (source) [1][2] |
| Device push notifications (state changes) go to `/app/<userid>/subscribe` | Confirmed (source) [1][2]. **Not used by Casita yet** |
| Replies carry the request's `messageId` and a signature we can check | Confirmed (source) [1] (MerossIot verifies reply signatures) |
| A reply's method is `GETACK` / `SETACK`, or `ERROR` | **Assumed** from MerossIot's behavior and community examples |

## Command delivery, duplicates and acknowledgements

| Question | What we know | What Casita does |
|---|---|---|
| Does `SETACK` mean the door moved? | **Unknown.** It could mean "message received" or "relay fired". It never means "the door reached the position". | Treats `SETACK` only as "the device answered". `GarageService` always checks the sensor afterwards. |
| Can the device act without its reply reaching us? | **Assumed yes.** Any network path can lose the reply. | No reply in time = **unknown outcome**, never "nothing moved". |
| Does a `SET` act like a button press (a toggle)? | **Unknown.** `open: 1` might be idempotent, or might press the button even if the door is open or moving. | Assume it's a press. `GarageService` sends only toward the opposite of a settled sensor reading. |
| Can a command be delivered twice? | MQTT QoS 1 is at-least-once, so a broker may redeliver. Whether the device de-duplicates by `messageId` is **unknown**. | Commands are published with **QoS 0** (at most once). A lost command is safe: it's reported as an unknown outcome and checked. |
| Can a command go out late, after a reconnect? | HiveMQ does **not** republish queued or pending messages when a reconnect finds the session expired, by default (Confirmed (library) [5]). Casita connects with `cleanSession=true`, so every reconnect has a new session. | Defense in depth: commands are sent only while connected. If a command's publish doesn't complete in time, Casita drops the whole connection, so nothing stays queued. |
| Does the device check message timestamps or reject old messages? | **Unknown.** | Not relied on. |
| Are commands retried? | n/a | **Never.** Not by the client, not by `GarageService`. A person decides. |

## Sensor updates

| Fact | Status |
|---|---|
| `Appliance.GarageDoor.State` `GET` returns `state` with `channel`, `open` (0/1), `lmTime` | Confirmed (source) for the namespace; field names **assumed** from community examples |
| `open: 0` means fully closed; `open: 1` means anything else | **Assumed** (magnetic sensor at the closed position) |
| `lmTime` is the last change time, in device seconds | **Assumed**. Informational only, never used for safety |
| How fast the sensor reports after the door starts moving | **Unknown** |
| Push notifications on state change | Confirmed (source) that a push topic exists [1][2]. Casita **reads on demand with `GET`** and doesn't subscribe to pushes yet |

## MSG100 and MSG200

Casita treats a device as a garage door opener when the account's device list reports its type
as `msg100` or `msg200` (case-insensitive). With exactly one opener it's found automatically;
with several, set `casita.meross.device-uuid` (it must be an opener on the account). The error
lists the openers by name and the last four characters of each uuid. Full uuids are never
logged or printed.

How each is read:

| Opener | Read | Channel |
|---|---|---|
| MSG100 | `GET Appliance.GarageDoor.State`, `state` (unchanged; not yet checked on a real MSG100) | `casita.meross.channel`, 0 when unset |
| MSG200 | `GET Appliance.System.All`, `all.digest.garageDoor` | `casita.meross.channel`, **required** (no default) |

In both, a door the opener reports as disabled (`doorEnable: 0`) is refused rather than read: a
disabled MSG200 channel reports `open: 1`. A channel the opener doesn't report fails with the
channels it did report. Commands are covered in "Commands" below.

Our doors, in `application.yml`:

```yaml
casita:
  meross:
    doors:
      1: Ford
      2: Toyota
```

Every listed door is read on its own channel and has its own `GarageService`: its own movement,
settle watch, hold and resume, and its own activity entries (each names its door). Ford's movement
never makes Toyota busy, and a stuck Toyota never holds Ford. Channel 3 isn't listed, so it is never
shown or driven. `casita.meross.channel` is only needed for an opener with no doors listed (an MSG100);
if set alongside `doors` it must be one of them, and it changes nothing.

| Fact | Status |
|---|---|
| Our MSG200 answers `GET Appliance.System.Ability` and `GET Appliance.System.All` over MQTT | **Confirmed (our device)** |
| Our MSG200 never answers `GET Appliance.GarageDoor.State`, with payload `{}` or `{"state": {}}` | **Confirmed (our device)**: both time out with the request published and acknowledged |
| `digest.garageDoor` lists channels 1 (Ford, enabled), 2 (Toyota, enabled), 3 (disabled, reports `open: 1`) | **Confirmed (our device)** |
| Our MSG200 sends signed PUSH notifications on `/app/<userId>/subscribe` | **Confirmed (our device)**; traced, not used yet |
| The MSG200 handles up to three garage doors | Confirmed (Meross product page) [8] |
| Community code reads opener state from `System.All`'s `digest.garageDoor` | Confirmed (source) [6], (trace) [9] |
| meross_lan sends `GET Appliance.GarageDoor.State` with `{"state": {}}` and a recorded MSG200 answers it | Confirmed (source) [9]. Ours doesn't; firmware or cloud-vs-local routing may differ |
| How an MSG200 takes a command (`SET`) | See "Commands": sourced, **not yet tried on ours** |

## What this PR implements

- Sign-in, session lifecycle and backoff, MQTT connection and reconnection, subscriptions.
- **Read-only** door status (`GET`): an MSG200 through `System.All`, an MSG100 through `GarageDoor.State` (see above).
- Local credentials from the environment or a git-ignored file (see "Local credentials").
- The command path is **off by default** (`casita.meross.command-mode: "off"`); see "Commands".

## Commands

**Confirmed on our MSG200, Ford (channel 1) only, over the cloud:**

- **Open (2026-10-15):** one `SET`, `SETACK` received, sensor not closed afterwards, door seen fully open.
- **Close (2026-10-16):** Ford watched not closed for the travel time (20 s), then one `SET`; `SETACK` received, sensor closed afterwards, recorded `MOVING` then `CLOSED_CONFIRMED`.

Each sent exactly one `SET`, with no retry. Toyota and channel 3 have not been sent a command; Toyota's
first commands are the supervised test in "Toyota (channel 2)" below.

| Fact | Status |
|---|---|
| Command: `SET Appliance.GarageDoor.State`, payload `{"state": {"channel": c, "open": 1 or 0, "uuid": u}}` over the cloud | Confirmed (source) [6]: MerossIot's `_async_operate`, the same namespace and payload for every opener |
| Over local HTTP, meross_lan sends the same without `uuid` | Confirmed (source) [9] |
| The reply is `SETACK` with `state` (an object or a one-item list) carrying `channel`, `open`, `lmTime` and `execute` | Confirmed (source) [9], from real devices' traces in meross_lan |
| `execute: 1`: the opener acted. `execute: 0`: it didn't (seen when the door was already in that position) | Confirmed (source) [9]. meross_lan says it has rarely seen 0 |
| The `open` in a SETACK is the state *before* moving; the door changes after its travel time (15 s by default in `Appliance.GarageDoor.MultipleConfig`) | Confirmed (source) [9], emulator and traces |
| Our MSG200 answers this SET over the cloud | **Confirmed (our device)** for Ford, open and close: `SETACK` each time, and the sensor then matched. Not yet tried: Toyota. Its GET on this namespace still never answers |

How Casita sends one (`MerossDoor.command`), in order; anything failing before the send means
nothing was sent:

1. `casita.meross.command-mode` is `dry-run` or `live`. The default is `"off"`.
2. The opener is found and the door's channel is listed in `casita.meross.command-channels`, the
   per-door switch (`[1]`: Ford only). Toyota (2) can't be sent anything until it is added, and the
   disabled channel 3 never. A door that isn't listed is refused by its `GarageService` before any
   check or send (`DISABLED`), and `/api/doors` reports it `"control": "off", "live": false`.
3. The door is read again, right then: it must be enabled and report the opposite of the target.
4. **Dry run:** the signed SET is built, the connection is checked, nothing is published, and the
   command is rejected ("nothing was sent"). **Live:** the SET is sent once, QoS 0, never retried.
5. A SETACK is not proof of movement. One that says `execute: 0` or names another channel is an
   unknown outcome. `GarageService` then checks the sensor after the travel time and says whether
   the door moved (stuck, or confirmed).

`GarageService`'s own rules stay in front of all this: read first, only toward the opposite of a
settled reading, one movement at a time, unknown outcomes are not failures, no automatic retries.

Settings:

```yaml
casita:
  meross:
    command-mode: "off"     # "off" | dry-run | live; quote "off" (YAML reads a bare off as false)
    command-channels: [1]   # the per-door switch: Ford only
```

Per door, the control is `command-mode` if the door's channel is in `command-channels`, otherwise off:

| `command-mode` | `command-channels` | Ford | Toyota |
|---|---|---|---|
| `"off"` | anything | off | off |
| `dry-run` | `[1]` | dry-run | off |
| `live` | `[1]` | **live** | off |
| `live` | `[1, 2]` | **live** | **live** (only after its test, below) |

`/api/doors` reports each door's `control` and `live`, so the app shows real control only where it is.

Live tests (`MerossLiveCommandTest`) for Ford; Toyota's are in "Toyota (channel 2)" below:

```bash
# Dry run: GETs only; checks on the MQTT connection that no SET was published.
mvn test -Dtest=MerossLiveCommandTest#dryRunForFordBuildsTheCommandAndPublishesNoSet -Dmeross.live=true

# One real command to Ford, with someone watching the door. Each confirmation names its own
# action, Ford, channel 1 and today's date. The close needs Ford already not closed, and watches it
# stay so for the travel time first. Set MEROSS_TRAVEL_SECONDS to Ford's real travel time (default 20).
mvn test -Dtest=MerossLiveCommandTest#confirmedOpenOfFord -Dmeross.live=true -Dmeross.command.confirm=open-ford-channel-1-YYYY-MM-DD
mvn test -Dtest=MerossLiveCommandTest#confirmedCloseOfFord -Dmeross.live=true -Dmeross.command.confirm=close-ford-channel-1-YYYY-MM-DD
```

## Toyota (channel 2)

**Status: commands off; hardware test paused at step 0 (2026-10-19).** Toyota has never been sent a
command. Step 0 found that Toyota's reading can lag well behind the door (see "Step 0, first attempt"),
and steps 1–3 wait until that lag is measured and understood. Casita reads it, shows it, and refuses
every command to it (`DISABLED`) until channel 2 is in `casita.meross.command-channels`. It follows
exactly Ford's rules: its own `GarageService`, read first, only toward the opposite of a settled reading,
one movement at a time, unknown outcomes are not failures, no retries, held after stuck or lost until
someone resumes it (`POST /api/doors/toyota/resume`).

### How channel 2 gets enabled

Only after the hardware test below has passed with someone watching, and with the user's go-ahead:

1. If Toyota's measured travel time differs from `casita.garage.travel-time`, give it its own:
   `casita.garage.travel-times.Toyota: <seconds>s`. Casita settles and checks Toyota on that time.
2. Add channel 2 to the switch in the deployed configuration: `casita.meross.command-channels: [1, 2]`
   (`CASITA_MEROSS_COMMANDCHANNELS=1,2`). With `command-mode: live`, Toyota is then live.
3. Restart, and check `GET /api/doors`: Toyota `"control": "live", "live": true`; Ford unchanged.
4. The first Toyota command from the app is made with someone at the garage.

To switch Toyota off again, remove 2 from `command-channels` and restart. Nothing else changes for Ford.

### Hardware test plan (approved 2026-10-19; paused at step 0)

Modelled on the Ford close test (2026-10-16). One door per run: every command in it is for channel 2,
and channel 1 isn't in its command channels, so Ford can't be sent anything. The deployed app stays as
it is (Toyota off) throughout; the test runs from a laptop with `MerossLiveCommandTest`.

**Before starting**

- Someone at the garage, watching Toyota, the doorway clear, with the wall button or remote in hand.
- Ford closed, and nobody operating Ford (from the app or by hand) during the test.
- Stop at the first surprise: any failed assertion, an `UNCONFIRMED`, `STUCK` or `LOST`, a door moving
  that shouldn't, or Ford changing at all. Don't retry; use the wall button, and write down what happened.

**Steps**

0. *Read only.* `MEROSS_DOORS=2:Toyota mvn test -Dtest=MerossLiveCheckTest -Dmeross.live=true`: channel 2
   is enabled and reads closed. Open and close Toyota with its own remote while re-running it: it reads
   not closed, then closed. **Time Toyota's travel** both ways with a stopwatch; take the longer and round
   up: that is `T` (seconds).
1. *Dry run (GETs only).*
   `mvn test -Dtest=MerossLiveCommandTest#dryRunForToyotaBuildsTheCommandAndPublishesNoSet -Dmeross.live=true`.
   Expect: the built `SET` names `channel: 2`, only `GET`s published, Toyota unchanged.
2. *One open.* Toyota closed.
   `MEROSS_TRAVEL_SECONDS=T mvn test -Dtest=MerossLiveCommandTest#confirmedOpenOfToyota -Dmeross.live=true -Dmeross.command.confirm=open-toyota-channel-2-YYYY-MM-DD`.
   Expect: exactly one `SET`, naming channel 2; `SETACK`; recorded `MOVING` then `OPEN_INFERRED`; Toyota
   seen fully open; Ford still closed.
3. *One close.* Toyota open from step 2.
   `MEROSS_TRAVEL_SECONDS=T mvn test -Dtest=MerossLiveCommandTest#confirmedCloseOfToyota -Dmeross.live=true -Dmeross.command.confirm=close-toyota-channel-2-YYYY-MM-DD`.
   It watches Toyota not closed for `T` first (if it ever reads closed, nothing is sent), then exactly one
   `SET` naming channel 2. Expect: `SETACK`; recorded `MOVING` then `CLOSED_CONFIRMED`; Toyota seen closed.
4. Record the results here, as for Ford (date, `T`, the outcomes), then decide on enabling (above).

**Gate added after the first attempt:** step 1 starts only once step 0 has measured how long channel 2's
reading takes to follow the door, both ways, and that lag is short enough for `GarageService`'s rules
(below).

Each confirmation names its action, Toyota, channel 2 and the date, so it can't be reused for Ford,
another day or the other direction; Toyota's tests also refuse to run without `MEROSS_TRAVEL_SECONDS`.

### Step 0, first attempt (2026-10-19, read only)

The user was at the garage with Toyota's remote and a stopwatch; Ford and Toyota closed, doorway clear.
Casita sent only `GET`s throughout. No command went to either door.

| Time | What happened |
|---|---|
| 11:20:02 | Read: Ford CLOSED, Toyota (channel 2) CLOSED, channel 3 refused as disabled. Channel 2 is enabled. |
| before 11:20:33 | The user opened Toyota with its remote and reported it fully open (stopwatch time not recorded). |
| 11:20:33, 11:20:58 | Read: Toyota still **CLOSED**. The Meross app showed Toyota **OPEN** at the time. |
| 11:21:46 | Raw `System.All`: `digest.garageDoor` channel 2 `open: 1` (channel 1 `open: 0`; `lmTime` 0 on every channel). |
| 11:21:55 | Read: Toyota NOT CLOSED. Casita parses channel 2 correctly. |
| 11:22:39 | A 2-second read-only watcher saw Toyota change to CLOSED after the user closed it with the remote (press time not recorded). It stayed CLOSED until 11:27. Ford read CLOSED throughout. |

What it shows:

- Channel 2 is enabled and reads both CLOSED and NOT CLOSED correctly, and only on its own channel.
- **After the open, `System.All` kept reporting channel 2 closed for at least 25 s after the door was fully
  open**, while the Meross app already showed it open. The exact lag isn't known (no press time was
  recorded). Neither is the lag on the close, nor Toyota's travel time `T`.
- `lmTime` is 0 for every channel, so the opener gives no change time to compare against.

Why it matters: `GarageService` assumes a reading is current. A reading that lags the door longer than the
travel time can mislead it:

- *Fails safe:* a Casita open whose "not closed" arrives after travel time + grace is judged `STUCK`, and
  Toyota is held until someone resumes it.
- *Not safe:* a door moved by its remote or wall button. If Toyota is closing but still reads "not closed"
  (stale) after Casita has already watched "not closed" for the travel time, Casita could send a close to a
  door that is already moving, and the opener could treat it as a button press and stop or reverse it.
  Rule 5 (let "not closed" settle) only protects against movement Casita can see.

Open questions before step 1:

1. How long channel 2's reading lags the door, opening and closing, over a few tries. Next attempt: start
   the 2-second watcher first, and write down the phone clock time of each press and each stopwatch reading.
2. Whether Ford's channel lags the same way. Ford's open and close tests read the sensor 24 s after the
   command, which says its lag was under about 24 s then. It hasn't been measured.
3. Whether the opener's PUSH notifications (already traced on `/app/<userId>/subscribe`, not used) report
   the change sooner than `System.All`. The Meross app showed the open before `System.All` did.

**Not covered by this test:** both doors moving at once. Each `GarageService` allows it, and every
acknowledgement is checked against its own channel, but how our MSG200 handles two doors moving
together hasn't been seen. Worth watching the first time it happens, or testing on purpose once
Toyota is enabled.

## Local credentials

For local development and the read-only live check, the Meross email and password can come from
the environment or from `config/meross.local.json`. Each value is taken from the environment when
set, otherwise from the file. The file is git-ignored; never put real values in the example.

```bash
cp config/meross.local.example.json config/meross.local.json
chmod 600 config/meross.local.json
# edit config/meross.local.json: email, password, and deviceUuid if the account has several openers
```

| | Environment (wins) | File key |
|---|---|---|
| App (`casita.door=meross`) | `CASITA_MEROSS_EMAIL`, `CASITA_MEROSS_PASSWORD`, `CASITA_MEROSS_DEVICEUUID` | `email`, `password`, `deviceUuid` |
| Live check | `MEROSS_EMAIL`, `MEROSS_PASSWORD`, `MEROSS_DEVICE_UUID`, `MEROSS_DOORS` (default `1:Ford,2:Toyota`) | same |

The live check never runs on a plain `mvn test`, so tests don't sign in to the real account by
accident. Ask for it:

```bash
mvn test -Dtest=MerossLiveCheckTest -Dmeross.live=true                 # credentials from the file
MEROSS_EMAIL=... MEROSS_PASSWORD='...' mvn test -Dtest=MerossLiveCheckTest   # or from the environment
MEROSS_DOORS=1:Ford mvn test -Dtest=MerossLiveCheckTest -Dmeross.live=true       # read only some doors
```

If the email or password is missing from both places, the app refuses to start and the live check
fails, naming what's missing and where it looked. A malformed file, an unknown key (a typo such as
`pasword`) or a non-string value fails the same way. Messages never include a value, and the JSON
parser's own message isn't passed on, because it can quote the file. Credentials, tokens and
device uuids are not logged.

Credentials move to Parameter Store later; this file is for local use only.

## Sources

1. MerossIot, `meross_iot/utilities/mqtt.py` (client id, topics, MQTT password, reply signature):
   https://github.com/albertogeniola/MerossIot/blob/0.4.X.X/meross_iot/utilities/mqtt.py
2. MerossIot protocol notes: https://albertogeniola.github.io/MerossIot/meross-protocol.html
3. meross-homeassistant issue #527 (login reply, region redirect, apiStatus 1033):
   https://github.com/albertogeniola/meross-homeassistant/issues/527
4. MerossIot wiki, HTTP APIs (2019): https://github.com/albertogeniola/MerossIot/wiki/HTTP-APIs
5. HiveMQ MQTT client 1.2.0 release notes (resubscribe on by default, republish off by default):
   https://github.com/hivemq/hivemq-mqtt-client/releases/tag/v1.2.0
6. MerossIot, `meross_iot/controller/mixins/garage.py` and `meross_iot/device_factory.py` (state per
   channel, default channel, devices built from their abilities):
   https://github.com/albertogeniola/MerossIot/blob/0.4.X.X/meross_iot/controller/mixins/garage.py
7. meross-homeassistant issue #363 (MSG200 support; only the first door shown):
   https://github.com/albertogeniola/meross-homeassistant/issues/363
8. Meross MSG200HK product page: https://shop.meross.com/collections/deals/products/smart-garage-door-opener
9. meross_lan: namespace payloads (`merossclient/protocol/namespaces/__init__.py`, GET payload `{key: {}}`)
   and a recorded MSG200 trace (`emulator_traces/U0123456789012345678901234567891C-Kpippo-msg200.json.txt`):
   https://github.com/krahabb/meross_lan
