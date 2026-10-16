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
with several, set `casita.meross.device-uuid`. The error lists the openers by name and the last
four characters of each uuid. Full uuids are never logged or printed.

| Fact | Status |
|---|---|
| The MSG200 handles up to three garage doors | Confirmed (Meross product page) [8] |
| Community code recognises an opener by the `Appliance.GarageDoor.State` ability, not by its type name, and has no MSG200-specific code | Confirmed (source) [6] |
| The state reply is a list with one `{channel, open, lmTime}` entry per door, for every opener | Confirmed (source) [6] |
| On an opener with two or more channels, community code defaults to channel **1**, not 0 | Confirmed (source) [6] |
| A user reported only the first door of a two-door MSG200 showing up in Home Assistant | Confirmed (source) [7]. The cause isn't known |
| Our MSG200 advertises `Appliance.GarageDoor.State`, and replies in the same format | **Assumed until the live check prints it.** The check asks the opener for its abilities (`Appliance.System.Ability`) and prints the state reply's channels and keys |
| Which channel numbers our MSG200 uses for its doors | **Unknown**. The live check prints them; `casita.meross.channel` (default 0) must name the door |

Reading the wrong channel fails with a protocol error that lists the channels the opener did
report, instead of guessing one. Commands are unchanged and stay off.

## What this PR implements

- Sign-in, session lifecycle and backoff, MQTT connection and reconnection, subscriptions.
- **Read-only** door status (`GET`) from an MSG100 or MSG200 (see above for what's unverified on the MSG200).
- Local credentials from the environment or a git-ignored file (see "Local credentials").
- The command path exists but is **off by default** (`casita.meross.commands-enabled: false`):
  every command is rejected before anything is sent. When it's turned on (only after the
  hardware checklist), commands are QoS 0, sent once, only while connected, and any failure
  after the publish starts is an unknown outcome.

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
| Live check | `MEROSS_EMAIL`, `MEROSS_PASSWORD`, `MEROSS_DEVICE_UUID`, `MEROSS_CHANNEL` | same |

The live check never runs on a plain `mvn test`, so tests don't sign in to the real account by
accident. Ask for it:

```bash
mvn test -Dtest=MerossLiveCheckTest -Dmeross.live=true                 # credentials from the file
MEROSS_EMAIL=... MEROSS_PASSWORD='...' mvn test -Dtest=MerossLiveCheckTest   # or from the environment
MEROSS_CHANNEL=1 mvn test -Dtest=MerossLiveCheckTest -Dmeross.live=true      # another door channel
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
