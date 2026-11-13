# Siri and Apple Shortcuts

Four phrases, each its own Shortcut on the iPhone: **"Open Ford Garage"**, **"Close Ford Garage"**, **"Open Toyota
Garage"**, **"Close Toyota Garage"**. They work hands-free, over Wi-Fi or cellular, without Face ID, a browser sign-in or
opening Casita. Shortcuts can't use passkeys or the browser
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
| Allows | **one action** (open **or** close) on **one door** (Ford or Toyota), both fixed when the token is created. Nothing else: not the other action, not resume, not the other door, not "I've looked at the door" (`checked`), not `/api/**`, not `/admin/**`. A phone with all four phrases has four tokens, each revoked on its own. A token made to open can never close: there is no upgrade, and tokens made before close existed stay open-only |
| Also reads | its own door's status (so the Shortcut can say what really happened) |
| Lifetime | 180 days by default, at most 365; then it stops working |
| Checked on every request | read from the store, no cache: the token exists, isn't revoked or expired; its member is in `casita.security.family` and active. Store unreachable: refused (503) |
| Revoked by | `casita-access revoke-shortcut-token <id>`, or `revoke-member` (all of that member's tokens) |

### Closing from Siri

A Siri close is an **ordinary close**, through the same `GarageService.close()` as the app's Close button, never the
app's "Close after a look". A voice request proves nothing about who is at the garage, so a Shortcut can't send
`checked`: a request that tries is refused (400) and nothing is sent. Siri and the app's Close button follow the same
door rules, in the same code:

- It refuses while a movement is unchecked or the door may be moving, while held after a stuck or lost move, when the
  sensor can't be read, when the door's commands or remote closing are off, and when the sensor says closed.
- Right after an open, it waits for the open's check at **travel time + sensor lag** (per door, as measured). That is
  the conservative wait: a closed/not-closed sensor can't prove a door has stopped, so Casita doesn't guess sooner. If
  the reading is still "closed" then, the open ends `STUCK` and the door is held. A shorter wait ("settle by sight") exists but is off
  for both doors, and can be switched on for a door only after its supervised test and with Shir's approval
  (docs/DOOR_TIMING.md, "Without a look"). In practice: arriving, the door is long settled by the time you have parked
  and got out; leaving, ask once you have driven out, and if it's too soon Siri says so.
- When it refuses, Siri says why and that nothing was sent. For example:
  - "Ford: The door may still be opening. Give it a few more seconds. I didn't send anything."
  - "Ford's sensor says it's closed, so I didn't send anything. If you can see it open, the sensor hasn't caught up
    yet: ask again in a little while, or use the wall button."

The opener's own photo eyes and auto-reverse still protect the doorway: Casita can't see it. A close that doesn't finish
ends `STUCK`, and Casita holds that door until someone checks it in the app.

## The API

All of it over HTTPS (plain HTTP is refused), with `Authorization: Bearer <token>`. A separate security chain handles
`/shortcut/**`: it never reads a cookie or a session, so the browser's sign-in means nothing here and a token means
nothing on `/api/**`. It has no CSRF protection because there's nothing for it to protect: CSRF rides on cookies the
browser sends by itself, and this chain reads none.

| Request | Answer |
|---|---|
| `POST /shortcut/v1/doors/{ford,toyota}/open` | the command's result (below), for an open token's own door |
| `POST /shortcut/v1/doors/{ford,toyota}/close` | the same, for a close token's own door |
| either, with a `checked` parameter | `400`, nothing sent: a Shortcut can't say it looked at the door |
| `GET /shortcut/v1/doors/{ford,toyota}` | the door's status, for the token's own door |
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
| `already` | 200 | nothing was sent: for an open, the sensor says it isn't closed; for a close, it says closed |
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

Each phone gets its **own** four tokens. Never reuse a token on a second phone; make that phone its own. A phone that
should only open, or only use Ford, gets only those tokens (`shortcut-token`, below).

### 1. Create the tokens (on watson)

```bash
gcloud compute ssh watson --zone us-central1-a --tunnel-through-iap
casita-access shortcut-phone shir "Shir's iPhone"            # four tokens: Ford open, Ford close, Toyota open, Toyota close (180 days)
```

The command prints four answers, each headed `== ford open ==` (and so on), each containing `"token": "casita_st_..."`,
`"door"` and `"action"`. Optionally add the days (default 180, at most 365): `shortcut-phone shir "Shir's iPhone" 365`.
One token at a time: `casita-access shortcut-token shir "Shir's iPhone" 180 toyota close` (days, door, action; the
defaults are 180, `ford`, `open`).

Each token is shown this once and not kept anywhere. Copy it straight into its Shortcut (step 2). If you must move it
between your own devices, use AirDrop or Notes, then delete the note. Don't send it through chat or email. Don't
screenshot it or the finished Shortcut (the header shows the token). Then `clear` the terminal. If a token leaks,
revoke it and make a new one. If `shortcut-phone` stops with FAILED, the tokens printed before it are real: use them
or revoke them (`shortcut-tokens` lists them).

### 2. Build the four Shortcuts (on the iPhone, Shortcuts app)

| Shortcut name (what you say) | URL | Token |
|---|---|---|
| `Open Ford Garage` | `https://watsonbental.duckdns.org/shortcut/v1/doors/ford/open` | `== ford open ==` |
| `Close Ford Garage` | `https://watsonbental.duckdns.org/shortcut/v1/doors/ford/close` | `== ford close ==` |
| `Open Toyota Garage` | `https://watsonbental.duckdns.org/shortcut/v1/doors/toyota/open` | `== toyota open ==` |
| `Close Toyota Garage` | `https://watsonbental.duckdns.org/shortcut/v1/doors/toyota/close` | `== toyota close ==` |

Build the first one:

1. Open **Shortcuts** → **+** (new shortcut). Tap the name at the top → **Rename** → `Open Ford Garage`.
2. *Optional (see "Locked phones")*: **Add Action** → search **Open App** → choose **Casita** or Safari. Only if you
   want Siri to need an unlocked phone: when the phone is locked this adds a Face ID or passcode prompt, which the
   setup otherwise doesn't have. Skip it for fully hands-free use, and accept the risk in "Locked phones".
3. **Add Action** → search **Get Contents of URL**. Tap the URL field and enter the URL from the table.
4. Tap the **›** (Show More) on that action:
   - **Method**: **POST**
   - **Headers** → **Add new header**: key `Authorization`, value `Bearer ` followed by the token (exactly one space
     after `Bearer`, no quotes, no line break)
   - **Request Body**: leave it as is (if Shortcuts insists, choose **JSON** and add no fields). Never add `checked`.
5. **Add Action** → **Get Dictionary Value**: Get **Value** for key `speak` in **Contents of URL**.
6. **Add Action** → **Speak Text**: **Dictionary Value**.
7. Tap **Done**.

Then the other three: long-press `Open Ford Garage` → **Duplicate**, rename it (table), open it, and change the **URL**
and the **Authorization** value to that row's. Check each one's URL and token twice: a wrong pair is refused (403),
never another door's move, but it won't work either.

*Optional, to hear the result:* after step 6, **Get Dictionary Value** `checkAfterSeconds` in **Contents of URL**, then
**Wait** that many seconds, then another **Get Contents of URL** for the door's status
(`https://watsonbental.duckdns.org/shortcut/v1/doors/ford`, Method **GET**, the same `Authorization` header), then
**Get Dictionary Value** `speak` → **Speak Text**.

In each Shortcut's details (the **ⓘ** button) you may turn off **Show in Share Sheet**; leave **Use with Siri** on. Do
**not** share the Shortcuts: a shared Shortcut carries its header, token included. If iCloud syncs your Shortcuts,
the tokens are on every device signed in to your Apple Account: treat those devices as holding them too.

Away from home: turn Wi-Fi off and try once on cellular. If it fails with no answer, check **Settings → Cellular →
Shortcuts** is on (the endpoint is public HTTPS, so nothing else is Wi-Fi-specific).

### 3. Use it

"Hey Siri, Open Ford Garage", "Hey Siri, Close Ford Garage", "Hey Siri, Open Toyota Garage", "Hey Siri, Close Toyota
Garage". Siri says what Casita answered: sent and not confirmed yet, nothing to do (already open, or the sensor says
closed), busy and why, held for a check, the sensor can't be read, or the token was refused. To revoke one token:

```bash
casita-access shortcut-tokens                    # ids, labels, doors, actions, state (never the tokens)
casita-access revoke-shortcut-token st-1a2b3c4d5e6f
```

Rotate the same way: create a new token, put it in its Shortcut, revoke the old one. Revoking one of a phone's tokens
leaves its other three working. A lost phone: revoke all four (or `revoke-member` if it is the member's only phone).

### 4. Check it on the phone (once per phone, someone watching the garage)

Automated tests cover the server side only. These need the iPhone:

1. Unlocked, on Wi-Fi: "Hey Siri, Open Ford Garage" → Siri speaks Casita's answer; the app shows "Shir via Shortcut".
2. Straight away, "Close Ford Garage": Siri says the door may still be opening and that nothing was sent. Ask again
   once the door has stopped and a few seconds have passed: it closes. Note how many seconds after the open it first
   worked.
3. With the door closed, "Close Ford Garage": Siri says the sensor says closed and nothing was sent.
4. The same for Toyota (only once Toyota's commands are on; before that Siri says it was refused and nothing moves).
5. Wi-Fi off, on cellular: open and close both doors again.
6. **Locked**, phone in a pocket or on a table: say each phrase. Note whether Siri runs it or asks to unlock. Repeat
   with the optional **Open App** step if you added it. Write down what happened: Apple doesn't document this for
   "Get Contents of URL".
7. Revoke that phone's Toyota close token: "Close Toyota Garage" answers that the key wasn't accepted; "Open Toyota
   Garage" and both Ford phrases still work.

## Threat model

| Risk | What happens | What limits it |
|---|---|---|
| **Siri on a locked phone** | Siri can run Shortcuts from the lock screen, and Apple doesn't document that "Get Contents of URL" requires unlocking: assume it doesn't. Anyone within earshot of the phone may be able to say a phrase, open or close. A bearer token can't tell | See *Locked phones* below. Casita adds no fake check for this |
| **Token copied** (screenshot, backup, shared Shortcut, iCloud sync of Shortcuts) | Whoever has it can open (or, with a close token, close) that token's door from anywhere until it expires or is revoked | One door and one action; 6 commands per 10 minutes per token; every use logged with the token's id; revoke at once with `casita-access`; 180-day default life. **Never share the Shortcut**: shared Shortcuts carry their headers |
| **Replay / repeated requests** | The token is the same on every request; resending opens again if the door is closed, and a close token closes again once the door has settled open | HTTPS only (nothing to capture in transit); `GarageService` refuses while a movement is unchecked, refuses when the door isn't closed, holds after a stuck move; per-token and per-client rate limits. No nonce: Shortcuts can't sign requests |
| **Compromised phone** | Same as a copied token, plus the browser's session | Revoke the token (`revoke-shortcut-token`) **and** the browser (`revoke-device`): they are separate credentials. `revoke-device` doesn't revoke tokens (nothing ties a token to a browser: a Shortcut sends only its token), but lists the member's active ones so you can pick the phone's by its label and id. Or revoke the member (`revoke-member`), which revokes both |
| **Closing onto someone** | A close token closes from anywhere, with nobody looking, like the app's Close from away | Only a door whose sensor has read not closed long enough to count as settled (including since Casita started, when it didn't see the door open), never one Casita knows may be moving; the opener's photo eyes and auto-reverse; a close that doesn't finish holds the door. A phone that shouldn't close gets only open tokens |
| **Other doors, other actions, admin** | Not reachable: the token's door and action are checked against the path; `checked` is refused; `/api/**` ignores tokens; `/admin/**` is local-only with its own secret, and Caddy answers 404 for it | Tests cover each |
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

**Recommendation:** decide per phone. Hands-free (no unlock) is what the token Shortcut does by default; it means
anyone who can talk to the phone may open that door. If that isn't acceptable, add **Open App** as the first step, or
turn Siri off on the lock screen.
Keep each token on one phone, never share the Shortcut, and revoke a token as soon as its phone is lost, sold or
repaired.

## Operations

- **Deploy:** an ordinary release (`deploy-release.sh --apply`); no settings change. Tokens only exist once someone
  creates one, so a release with this code and no tokens behaves exactly as before. Existing tokens keep their one
  action (open): to add closing, create close tokens (`shortcut-token ... ford close`), never edit a token.
- **Logs:** `journalctl -u casita | grep -i shortcut`: every command (token id, member, label, outcome, client address) and
  every refusal (reason, token id when known).
- **Activity:** commands appear in the app as "Shir via Shortcut asked to open Ford" (or Toyota).
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
