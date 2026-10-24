# Casita's Garage: Design

> **Update:** the Tailscale parts of this design were replaced by public HTTPS behind Caddy with passkeys and
> per-browser invites (`docs/PUBLIC_HTTPS.md`, `docs/ACCESS.md`).

Casita is our home's helper. This first piece lets the family see and move the garage door
from their iPhones, safely, from anywhere.

Status: **historical, partly superseded** (2026-10-21). This is the original design from 2026-10-15.
Sections 5 (garage safety rules) and 6 (Meross cloud and its *hardware prerequisites*) still hold, and
are the checklist to complete before any live door command. Much of the rest has been replaced by what
was built; where the two differ, the code and the docs next to it win.

| This doc says | Now | Where |
|---|---|---|
| EC2 `t4g.micro`, AWS IAM and KMS | A GCP Compute Engine e2-micro, planned (staging first) | M2, not merged |
| Server `casita.<tailnet>.ts.net` | Server **`watson`**: passkeys bind to `watson.<tailnet>.ts.net` (set before anyone enrolls; a rename means re-enrolling) | `casita.security.rp-id` |
| Parameter Store for passkeys, users and secrets | One `SecurityStore` for members, passkeys, devices and invitations: file (default), DynamoDB or Firestore. Meross credentials from the environment or a git-ignored local file | `docs/PERSISTENCE.md`, `docs/FIRESTORE.md` |
| Remember-me cookie, fixed 6 months, `SameSite=Strict` | Not built: an in-memory session, 7 days idle, `SameSite=Lax` | `application.yml` |
| Invite: a 10-minute one-time token, any device | One invite per device, 5 minutes, an enrollment-only session | `docs/ACCESS.md` |
| A passkey alone signs you in | A passkey **and** an authorized Tailscale node, checked again on every request | `docs/ACCESS.md` |
| Shortcut status tokens | Not built | |
| One MSG100, one door | An MSG200: Ford (channel 1) and Toyota (channel 2); channel 3 is disabled and never addressed. Commands are off by default and Ford-only when enabled | `docs/MEROSS.md` |
| Travel time 10 s | Ford measured at 20 s, and the MSG200's reading can lag the door; both must be reflected in the configuration before any live command | `docs/MEROSS.md` |
| Open: Java version, MQTT client | Java 21, HiveMQ | |
| Open: activity log, push notifications | Still open: an in-memory log and a log-only notifier | |
| Build order (section 10) | Replaced by the PR stack (#2–#11) | |

## 1. Architecture

```
iPhone (Casita web app, Shortcuts)
   │  HTTPS over Tailscale (private, no public endpoint)
   ▼
EC2 t4g.micro ── Casita (Spring Boot)
   │  Meross cloud: HTTPS login + MQTT over TLS
   ▼
Meross MSG100 ──▶ garage door opener
```

- **Hosting:** one EC2 `t4g.micro` (1 GB RAM; Spring Boot doesn't fit in a nano's 0.5 GB).
- **Network:** Casita binds to `127.0.0.1` only. `tailscale serve` publishes it as
  `https://casita.<tailnet>.ts.net` to devices on our tailnet. The security group allows
  no inbound traffic (Tailscale connects outbound); SSH only through Tailscale.
- **Door control:** through Meross's cloud, so nothing is needed at home.

## 2. Stack

| Piece | Choice |
|---|---|
| Language / framework | Java, Spring Boot 4.x (*Open:* Java 21 LTS proposed) |
| Build | Maven |
| Sign-in | Spring Security passkeys (WebAuthn, built on webauthn4j) |
| Invites | Spring Security one-time token login |
| Long sessions | Spring Security remember-me (HMAC-signed cookie) |
| Secrets & credentials | AWS SSM Parameter Store (SecureString, dedicated KMS key) |
| Meross MQTT | *Open:* Eclipse Paho or HiveMQ MQTT client |

## 3. Who can use Casita

### Enrolling a phone
1. On the server: `casita invite hubby`. This prints a link holding a one-time token:
   single use, expires in 10 minutes.
2. He opens it on his iPhone. The token signs him in once, and the app asks him to create
   a passkey (Face ID). The server stores only the passkey's **public key**.
3. From then on he signs in with the passkey.

Notes:
- Passkeys need HTTPS and a fixed domain (the relying-party ID). If the tailnet name
  changes, everyone re-enrolls.
- iCloud Keychain syncs passkeys across one Apple ID's devices (iPhone, iPad). iOS can't
  force a passkey to stay on one device, so in practice it's one passkey per Apple ID.
- We ignore the signature counter: Apple's synced passkeys always report 0.

### Sessions
- After a passkey sign-in, a **remember-me cookie** keeps you signed in for a **fixed
  6 months** (no sliding renewal, so a stolen cookie can't keep itself alive).
- The cookie is `HttpOnly`, `Secure` and `SameSite=Strict`, and is signed with HMAC using a
  per-user secret from Parameter Store.
- Server-side sessions (after a passkey sign-in) live in memory. A restart ends them; the
  remember-me cookie signs the person back in.
- CSRF protection stays on (Spring default). The web app sends the CSRF token on POSTs.

### Revoking access
Deleting a passkey **does not** sign anyone out on its own: an existing session and a
remember-me cookie don't depend on the passkey after sign-in. So revoking is one operation
that cuts off every kind of credential:

| Credential | Lives in | Cut off by | Takes effect |
|---|---|---|---|
| Passkey | `/casita/passkeys/<id>` + in-memory cache | delete the parameter and the cache entry | next sign-in |
| Server-side session | Casita's memory | Spring Security `SessionRegistry`: expire all of the person's sessions | next request |
| Remember-me cookie | the phone (HMAC, per-user secret) | rotate the person's secret in Parameter Store and in the cache; the cookie is checked against the cached secret on every use | next request |
| Shortcut token | `/casita/shortcut-tokens/<id>` + cache | delete the parameter and the cache entry | next request |
| Invite (one-time token) | Casita's memory | delete it (it also expires after 10 minutes) | at once |

- **Revoke through Casita.** `casita revoke hubby` (or `--device <credentialId>`) talks to
  the running service on localhost. In one operation Casita writes Parameter Store and
  updates its caches (write-through), expires sessions, rotates the secret and deletes
  Shortcut tokens. That is what makes it immediate.
- **Remember-me is per person, not per device.** Removing one phone rotates that person's
  secret, which signs out all their devices; they sign back in with their passkey (Face ID
  once). Accepted trade-off: per-device remember-me would need tokens rewritten on every use.
- **Changes made outside Casita** (AWS console or CLI) are picked up by a refresh of all
  caches every 60 seconds, so they take effect within a minute. A refresh replaces the
  cache as a whole; a failed refresh keeps the last good cache and logs an error. The
  CloudTrail alarm on `/casita/*` writes flags these changes.
- **Panic button:** `casita revoke --all` rotates every secret, expires every session and
  deletes every Shortcut token. Passkeys stay, so the family can sign back in. If the server
  itself may be compromised, stop the instance and remove its IAM role.
- **One instance.** Caches and sessions are in one process. Running more than one Casita
  would need shared invalidation, which is out of scope.
- Build step 6 implements this. Its tests must show: an expired session is rejected, a
  cookie signed with a rotated secret is rejected, a deleted Shortcut token is rejected, and
  a passkey deleted outside Casita stops working after the next refresh.

### Moving the door
- **Decided: no extra Face ID per action.** A signed-in session can open and close.
  The iPhone's own lock screen (Face ID) is the guard.
- Risk we accept: anyone holding an *unlocked* phone, or a copy of the cookie, can move the
  door until the session ends.
- What limits it: hold-to-open in the UI, a push notification on every move, revocation
  (below), and the safety rules in section 5.

### Shortcuts and Siri
- Shortcuts can't use passkeys. They get a per-phone **status-only token** (random 256-bit;
  the server stores only its SHA-256 hash).
- "Hey Siri, open garage" and the arrival automation **open the Casita app**; the door
  moves from there.
- *Open:* now that actions don't need Face ID, should Shortcut tokens be allowed to open
  (not close) as well?

## 4. Parameter Store layout

```
/casita/meross/email               SecureString
/casita/meross/password            SecureString
/casita/users/<user>/secret        SecureString   remember-me HMAC secret (rotate = sign out)
/casita/users/<user>/display-name  String
/casita/passkeys/<credentialId>    SecureString   JSON: user, device label, public key (COSE,
                                                  base64url), userHandle, createdAt, lastUsedAt
/casita/shortcut-tokens/<id>       SecureString   JSON: user, SHA-256 hash, scope
```

- Loaded at startup with `GetParametersByPath` and cached in memory, so sign-ins don't call
  AWS. Casita's own changes update the cache as they're written; everything is refreshed
  every 60 seconds to pick up changes made elsewhere (see *Revoking access*).
- Written only on enroll, remove or rotate, plus `lastUsedAt` at most once a day.
- One parameter per passkey keeps each well under the 4 KB standard-tier limit.
- Invites (one-time tokens) live in memory only.

### IAM (EC2 instance role)
- `ssm:GetParameter`, `GetParametersByPath`, `PutParameter`, `DeleteParameter` on
  `arn:aws:ssm:<region>:<account>:parameter/casita/*` only.
- `kms:Decrypt` and `kms:Encrypt` on the dedicated Casita key only.
- Nobody else gets write access to `/casita/*`: **writing a passkey there is the same as
  getting a garage key.** CloudTrail alarm on `PutParameter` under `/casita/passkeys/`.

## 5. Garage safety rules

### What Casita can and can't know
`DoorDevice` spells out the contract. In short:
- **One binary sensor.** "Closed" means fully closed. "Not closed" means anything else:
  opening, closing, stopped partway or fully open. There's no position, no motion signal
  and no obstruction signal.
- **The command may act like a button press** (a toggle), so it isn't idempotent. Sending
  "open" to an open door, or anything to a moving door, may stop or reverse it.
- **A failed command may still have run.** Only a failure that guarantees nothing was sent
  (`DoorCommandRejectedException`) means nothing moved. Anything else is an unknown outcome.
- **The wall button and remote are invisible** except through sensor changes Casita
  happens to read.

### Rules (`GarageService`)
1. **Read first.** If the sensor can't be read, send nothing.
2. **Only toward the opposite.** Send "open" only when the sensor reports closed, and
   "close" only when it reports not closed. Never send a command toward the state the
   sensor already reports.
3. **One movement at a time, until checked.** After a command, nothing else is sent until
   Casita has checked the sensor, at travel time + grace (10 s + 2 s; a close finishes early
   once the sensor reports closed). If that check is overdue when a new request arrives, it
   runs first, so a failure is never skipped or blamed on a newer movement.
4. **Unknown outcomes aren't failures.** If a command fails without a guarantee that
   nothing was sent, Casita says it isn't sure, assumes the door may be moving, blocks
   other commands and checks after the travel time ("it went through after all" or "the
   door is still closed"). It never says "nothing moved" without that guarantee, and never
   retries on its own.
5. **Let a "not closed" door settle.** Casita sends "close" only after it has watched the
   sensor report "not closed" for at least the travel time. This covers the wall button,
   the remote, and a Casita restart while the door may be moving. "Closed" is the only
   reading that proves the door's position, so "open" from closed is allowed at once.
6. **Say what happened.** Every command and check is recorded. Moves, unconfirmed commands
   and failed checks are pushed to the family.
7. **Remote close can be switched off** in configuration.
8. **Bad configuration stops startup.** Travel time must be positive and the grace period
   zero or more.
9. **Physical safety is the opener's job.** Its photo-eye sensors and auto-reverse stop the
   door for people, pets and cars. The wall button and remote keep working without Casita.

### Known limitations
These rules don't solve:
- If someone starts *closing* the door with the wall button while it was settled open,
  Casita can't tell. A "close" from Casita then acts as a second press and may stop the
  door partway; the check reports it as not closed.
- "Open" on a door stopped partway answers "already open".
- In the instant before the sensor trips, a door that has started opening still reads closed.
- Casita only notices outside movement when it reads the sensor. If nobody has looked, the
  first "close" after an outside open waits the travel time.
- State is in memory. A restart during a movement loses its check: rule 5 protects the next
  close, but an "open" in flight at restart isn't reported.
- The tests run against a fake door. They show the rules are implemented, not that the real
  opener behaves as assumed. See *Hardware prerequisites* in section 6.

## 6. Meross cloud

- Sign in to Meross's HTTPS API with the account email and password. This returns a token,
  a user id and a key.
- Connect to Meross's MQTT broker over TLS, then publish signed commands to the device's
  topic and wait for its reply. Read door state with `Appliance.GarageDoor.State`.
- This is Meross's **unofficial** API, documented by the community (MerossIot, meross_lan).
  Meross can change it, and has warned that unofficial integrations may break. Keep it
  behind a `DoorDevice` interface so it's easy to fix or replace.
- *Open:* does our Meross account have two-factor login turned on?
- Re-login when the token expires; reconnect MQTT with backoff.

### Hardware prerequisites
Before switching Casita to the real door (`casita.door: meross`), check these on our MSG100
with someone at the garage watching. Until then they are **assumptions, not guarantees**,
and the rules in section 5 assume the worst case:

1. **Command semantics.** Does `SET open: 1` on an already-open door do nothing, or press
   the button? `open: 0` on a closed door? Any command while the door is moving?
2. **Sensor.** Does "closed" mean fully closed and nothing else? How soon after the door
   starts opening does it read "not closed"? Does a door stopped partway read "not closed"?
3. **Acknowledgement.** Does `SETACK` mean the relay fired, or only that the message
   arrived? Can the device act while its reply never reaches us?
4. **Duplicates.** MQTT QoS 1 may deliver a message twice. Would a duplicate `SET` press
   the button twice and reverse the door? If so, commands must use QoS 0 or the device must
   de-duplicate by message id.
5. **`lmTime`.** What it records, and on which clock. Not used for safety today.
6. **Travel time.** Time a full open and a full close; set `travel-time` to the slower one
   plus a margin (10 s measured so far).
7. **The opener's own safety.** Photo eyes aligned, auto-reverse tested, and whether the
   opener or MSG100 warns (beeps or flashes) before a close it can't see.
8. **Which client failures guarantee nothing was sent.** Only those may raise
   `DoorCommandRejectedException`. Everything else must stay an unknown outcome.

## 7. Activity and notifications

- Each event records who, what, when and the outcome (moved, already, refused, stuck).
- *Open:* where it lives: a local file with 30-day retention (lost if the server is
  rebuilt), DynamoDB with a TTL, or CloudWatch Logs.
- Push on every move and on every failed move. *Open:* ntfy (free; needs a private topic or
  token) or AWS SNS.
- The log shows when the family is home or away. Keep it private and short-lived.

## 8. API

| Method | Path | Who | What |
|---|---|---|---|
| GET | `/api/status` | session or Shortcut token | door state, since when, can close |
| POST | `/api/open` | session | open (safety rules apply) |
| POST | `/api/close` | session | close (safety rules apply) |
| GET | `/api/activity` | session | latest events |

Plus Spring Security's passkey, one-time-token and logout endpoints, and the web app.

## 9. Web app

The muted, playful Casita design we agreed on. Casita speaks in first person.
- Home screen: status card, press-and-hold Open/Close button, "What I've been up to" activity.
- Arrival sheet (`/?arrive=1`): "It's a big, busy world out there… / We missed you!" with
  **Yes, open it** and **Not now**.
- Add to Home Screen for a full-screen app.

## 10. Build order (one pull request each)

1. This design doc.
2. Project skeleton: `pom.xml`, Spring Boot app, CI that builds and tests every PR.
3. `GarageService` and safety rules, against a fake door, with tests.
4. Meross cloud client (login + MQTT) behind `DoorDevice`.
5. Parameter Store repositories and cache.
6. Security: passkeys, invites, remember-me, Shortcut tokens.
7. REST API and web app.
8. Deployment: EC2, Tailscale, systemd, IAM and KMS, runbook.

## Open questions

1. Java version (21 LTS proposed)?
2. MQTT client: Eclipse Paho or HiveMQ?
3. Does the Meross account use two-factor login?
4. Activity log: local file, DynamoDB or CloudWatch?
5. Push notifications: ntfy or SNS?
6. Can Shortcut tokens open the door, or status only?
