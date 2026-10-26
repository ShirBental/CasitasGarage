# Siri and Apple Shortcuts

"Hey Siri, Open Ford Garage" opens the Ford door without opening Safari. Shortcuts can't use passkeys or the browser
key (docs/ACCESS.md), so a Shortcut gets its own credential: a **Shortcut token**. Code: `home.casita.security.shortcut`
and `web/ShortcutController`.

**The one thing to understand first:** a Shortcut token is a bearer secret. Casita can check that a request carries a
valid token. It can't check who is holding the phone, whether the phone was unlocked, or whether anyone spoke to Siri.
Everything below follows from that.

## What a token is

| | |
|---|---|
| Format | `casita_st_` + 256 random bits (base64url). Shown **once**, when created |
| Stored | only its SHA-256, in the security store with members, passkeys, devices and invitations: `shortcutTokens/t_{sha256}` in Firestore (one document per token) |
| Belongs to | one member, one device label ("Shir's iPhone") |
| Allows | **open** the **Ford** door. Nothing else: not close, not resume, not Toyota, not `/api/**`, not `/admin/**` |
| Also reads | its own door's status (so the Shortcut can say what really happened) |
| Lifetime | 180 days by default, at most 365; then it stops working |
| Checked on every request | read from the store, no cache: the token exists, isn't revoked or expired; its member is in `casita.security.family` and active. Store unreachable: refused (503) |
| Revoked by | `casita-access revoke-shortcut-token <id>`, or `revoke-member` (all of that member's tokens) |

Closing stays in the app on purpose: the app shows the door and its sensor before you close it.

## The API

All of it over HTTPS (plain HTTP is refused), with `Authorization: Bearer <token>`. A separate security chain handles
`/shortcut/**`: it never reads a cookie or a session, so the browser's sign-in means nothing here and a token means
nothing on `/api/**`. It has no CSRF protection because there's nothing for it to protect: CSRF rides on cookies the
browser sends by itself, and this chain reads none.

| Request | Answer |
|---|---|
| `POST /shortcut/v1/doors/ford/open` | the command's result (below) |
| `GET /shortcut/v1/doors/ford` | the door's status |
| anything else the token doesn't allow | `403` |
| no token, a bad, revoked or expired token, or a revoked member | `401` (the same answer whatever the reason; the reason is logged with the token's id) |
| over the limits | `429` with `Retry-After` |
| not HTTPS | `403` |

The command goes through the same `GarageService` as the app: every safety rule, hold, busy check and command mode
applies unchanged. A command answer:

```json
{
  "result": "accepted",
  "outcome": "MOVING",
  "door": "Ford",
  "control": "live",
  "live": true,
  "doorState": "OPENING",
  "confirmed": false,
  "checkAfterSeconds": 32,
  "message": "Opening up for you.",
  "speak": "Opening Ford. It isn't confirmed yet: I'll know once the sensor checks."
}
```

| `result` | HTTP | Means |
|---|---|---|
| `accepted` | 202 | sent, and the opener acknowledged it. **Not** that the door is open |
| `unconfirmed` | 202 | sent, no acknowledgement: it may or may not be moving |
| `already` | 200 | the sensor says it isn't closed; nothing was sent |
| `refused` | 409 | nothing was sent: busy, held after a stuck move, sensor unreadable, or the door switched off |
| `failed` | 502 | definitely not sent (in dry-run mode, every command ends here) |

`confirmed` is true only when the sensor has settled: `CLOSED`, or `NOT_CLOSED` (open is inferred; the sensor only
knows closed or not). To know the door opened, wait `checkAfterSeconds` and read the status. If `live` is false
(`simulated`, `dry-run`), `speak` says so first, and nothing ever describes the real door moving.

## Where tokens are kept, and why

In the security store, with everything else an access decision reads: Firestore in production, one document per token
(`shortcutTokens/t_{sha256}`); the same contract in the file store (local development) and DynamoDB. An earlier draft
kept them in a file next to `admin.secret`. That was a mistake: it split security state across two stores
(docs/PERSISTENCE.md, rule 5), put tokens on the VM's disk instead of Firestore's backups, and made revoking a member
two separate writes. It didn't even help during an outage, since checking a token also needs its member from Firestore.

- **Atomic:** creating is one batch, the token document created only if absent plus its security event; revoking is a
  compare-and-set on the document (`updateTime` unchanged), retried on conflict. A commit whose answer was lost is
  recognized by its own `changeEventId` and not repeated.
- **Immediate revocation:** every request reads the token and its member, strongly consistent, with no cache. A cache
  would delay revocation by its lifetime, and a cache used during an outage would accept a revoked token (fail open).
- **Cost:** two document reads per Shortcut request (token, member), a few a day; well within the free quota.
- **IAM:** nothing new (docs/FIRESTORE.md).

## Setting it up

### 1. Create the token (on watson)

```bash
gcloud compute ssh watson --zone us-central1-a --tunnel-through-iap
casita-access shortcut-token shir "Shir's iPhone"          # optional 3rd argument: days (default 180, max 365)
```

The answer contains `"token": "casita_st_..."`. It is shown this once and not kept anywhere. Copy it straight into the
Shortcut (step 2), using AirDrop or Notes between your own devices if you must (then delete the note). Don't send it
through chat or email, don't screenshot it or the finished Shortcut (the header shows the token), then `clear` the
terminal. If it leaks, revoke it and make a new one.

### 2. Build the Shortcut (on the iPhone, Shortcuts app)

New Shortcut, named **Open Ford Garage** (the name is what you say to Siri). Actions, in order:

1. **Get Contents of URL**
   - URL: `https://watsonbental.duckdns.org/shortcut/v1/doors/ford/open`
   - Show More → Method: **POST**
   - Headers: add one: key `Authorization`, value `Bearer ` followed by the token (one space after `Bearer`)
   - Request Body: none needed (if Shortcuts insists, choose JSON and add no fields)
2. **Get Dictionary Value**: Get **Value** for key `speak` in **Contents of URL**
3. **Speak Text**: the Dictionary Value

Optional, to hear whether it actually opened:

4. **Get Dictionary Value**: `checkAfterSeconds` in Contents of URL. Use it in **Wait**, or wait a fixed 35 seconds.
5. **Get Contents of URL**: `https://watsonbental.duckdns.org/shortcut/v1/doors/ford`, Method **GET**, the same
   `Authorization` header
6. **Get Dictionary Value**: `speak`, then **Speak Text**

Test it once in the Shortcuts app, while you can see the garage.

### 3. Use it

"Hey Siri, Open Ford Garage". To revoke a phone's token:

```bash
casita-access shortcut-tokens                    # ids, labels, state (never the tokens)
casita-access revoke-shortcut-token st-1a2b3c4d5e6f
```

Rotate the same way: create a new token, put it in the Shortcut, revoke the old one.

## Threat model

| Risk | What happens | What limits it |
|---|---|---|
| **Siri on a locked phone** | Siri can run Shortcuts from the lock screen, and Apple doesn't document that "Get Contents of URL" requires unlocking: assume it doesn't. Anyone within earshot of the phone may be able to say the phrase. A bearer token can't tell | See *Locked phones* below. Casita adds no fake check for this |
| **Token copied** (screenshot, backup, shared Shortcut, iCloud sync of Shortcuts) | Whoever has it can open Ford from anywhere until it expires or is revoked | Ford and open only; 6 commands per 10 minutes per token; every use logged with the token's id; revoke at once with `casita-access`; 180-day default life. **Never share the Shortcut**: shared Shortcuts carry their headers |
| **Replay / repeated requests** | The token is the same on every request; resending opens again if the door is closed | HTTPS only (nothing to capture in transit); `GarageService` refuses while a movement is unchecked, refuses when the door isn't closed, holds after a stuck move; per-token and per-client rate limits. No nonce: Shortcuts can't sign requests |
| **Compromised phone** | Same as a copied token, plus the browser's session | Revoke the token (`revoke-shortcut-token`) **and** the browser (`revoke-device`): they are separate credentials. `revoke-device` doesn't revoke tokens (nothing ties a token to a browser: a Shortcut sends only its token), but lists the member's active ones so you can pick the phone's by its label and id. Or revoke the member (`revoke-member`), which revokes both |
| **Other doors, closing, admin** | Not reachable: the token's door and action are checked against the path; `/api/**` ignores tokens; `/admin/**` is local-only with its own secret, and Caddy answers 404 for it | Tests cover each |
| **Guessing** | 256 random bits | Rate limits keep the endpoint from being hammered, not guessing |
| **Leaks through logs** | — | Tokens are never logged (only ids); Caddy drops the Authorization header from its access log (it also redacts it by default); error answers are fixed text |
| **Google Cloud outage** | Firestore can't be read, so no token can be checked | Refused (503), like every sign-in: fail closed. No cache or local copy is trusted instead, because it could still accept a revoked token |

### Locked phones (what can and can't be enforced)

- **Casita can't verify user presence.** No server-side check can, with a bearer token. Casita doesn't pretend to.
- **The only phone-side step Apple documents:** if a shortcut opens an app while the device is locked, Siri asks to
  unlock before continuing ([Apple: Run shortcuts with Siri](https://support.apple.com/guide/shortcuts/apd07c25bb38/ios)).
  Adding **Open App** as the Shortcut's first action should therefore make Siri ask you to unlock (Face ID or passcode)
  first. **This hasn't been verified on a device**: test it with the phone locked before relying on it. It is a step
  inside the Shortcut, on the phone: Casita can't check that it's there, and a copied token skips it.
- **The strict option:** Settings → Face ID & Passcode → *Allow Access When Locked* → turn off **Siri**. Siri then
  needs the phone unlocked for everything.
- If neither suits you, use Siri to open the Casita app instead (an **Open URLs** Shortcut with
  `https://watsonbental.duckdns.org`): one tap, and the unlocked phone, passkey session and browser key stay part of it.

**Recommendation:** use the token Shortcut with **Open App** as its first step, or with Siri off on the lock screen.
Keep each token on one phone, never share the Shortcut, and revoke a token as soon as its phone is lost, sold or
repaired.

## Operations

- **Deploy:** an ordinary release (`deploy-release.sh --apply`); no settings change. Tokens only exist once someone
  creates one, so a release with this code and no tokens behaves exactly as before.
- **Logs:** `journalctl -u casita | grep -i shortcut`: every command (token id, member, label, outcome, client address) and
  every refusal (reason, token id when known).
- **Activity:** commands appear in the app as "Shir via Shortcut asked to open Ford".
- **Kill switch:** revoke every token (`casita-access shortcut-tokens`, then `revoke-shortcut-token` each), or
  `revoke-member`, which revokes all of that member's tokens with everything else.
- **Backups:** tokens are in Firestore with the rest of the security state: replicated, with delete protection on the
  database and point-in-time recovery (7 days; docs/FIRESTORE.md, *Recovery*, for its cost), and losing the VM loses
  nothing. If the database is ever restored to an earlier time (PITR or an import), every revocation since then is
  undone, for tokens, devices, passkeys and members alike: revoke them again before Casita serves requests.
- **Audit:** creating and revoking a token writes a `securityEvents` record in the same commit (docs/FIRESTORE.md), kept
  400 days. Each use is one journal line (persistent journal, capped at 200 MB): for a command, the token id, member,
  label, door, outcome, control and client address in one line, so the line is itself the link between the token and
  the door command; for a refusal, the reason, the token id when known, and the client address. The app's activity list
  shows "Shir via Shortcut" but is kept in memory only.

## Consistency model

- **Authorization is decided once per request, at its start**, from two strongly consistent reads (the token, then its
  member). The door command follows a few milliseconds later, through the same `GarageService` as the app.
- **A revocation committed before a request's reads** (token, member, or the member's cleanup) refuses that request.
- **A revocation committed while a request is already past its reads** doesn't stop that one request: the door command
  goes ahead. Every later request is refused. The browser path behaves the same way.
- No transaction can close that window: the door command is a message to Meross, which can't be made part of a
  Firestore commit. The window is the time between the reads and the command, not the command's duration.
- Two commands at once (Shortcut and app, or two Shortcuts) are serialized by `GarageService`: the second is refused as
  busy while the first is unchecked.
