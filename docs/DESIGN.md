# Casita's Garage: Design

Casita is our home's helper. This first piece lets the family see and move the garage door
from their iPhones, safely, from anywhere.

Status: **draft for review**. Decisions marked *Open* still need an answer.

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
- **Revoking access:** remove the person's passkeys or rotate their secret, and they're
  signed out right away. Rotating every user's secret signs everyone out (the panic button).
- CSRF protection stays on (Spring default). The web app sends the CSRF token on POSTs.

### Moving the door
- **Decided: no extra Face ID per action.** A signed-in session can open and close.
  The iPhone's own lock screen (Face ID) is the guard.
- Risk we accept: anyone holding an *unlocked* phone, or a copy of the cookie, can move the
  door until the session ends.
- What limits it: hold-to-open in the UI, a push notification on every move, instant
  revocation, and the safety rules in section 5.

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

- Loaded once at startup with `GetParametersByPath` and cached in memory. Sign-ins never call AWS.
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

The opener only "presses the button", so the rules live in `GarageService`:

1. **Read the sensor first.** If the door's position is unknown, refuse and change nothing.
2. **Open and close are separate commands.** "Open" never closes; "close" never opens.
   Asking to open an open door is a no-op.
3. **One move at a time.** Ignore commands while the door is moving (door travel time is
   configurable, ~20 s).
4. **Check afterwards.** After the travel time, read the sensor again. If the door didn't
   get where it was going, log it and push "The garage door didn't close. Something may
   be in the way."
5. **Remote close can be switched off** in configuration.
6. **Fail safe.** Any error means the door doesn't move. The wall button and remote always
   keep working.

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
