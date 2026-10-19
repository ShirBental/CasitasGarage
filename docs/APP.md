# Casita: the app

The family's garage app: one clear door status, one action, a short history. It follows the design
doc (`docs/DESIGN.md` on the `docs/design` branch): sections 3 (who can use Casita), 8 (API) and
9 (web app). This file records what is built, how it's secured, and what's still to do.

**Real door control through the app is not enabled.** With `casita.door=meross`, commands reach
Ford only when `casita.meross.command-mode` is `live`; it is `"off"` by default, and the app then
disables its action button. Turning it on waits for review.

## What's built

| Piece | Where |
|---|---|
| Passkey sign-in (Face ID) | `SecurityConfig`: Spring Security WebAuthn, Casita's own `/login` page using Spring's passkey script |
| Invites for a new phone | Spring Security one-time tokens; `InviteLinks` writes the link to a file on the server |
| Face ID setup | `/passkey` (Spring's script, Casita's page) |
| Passkey storage | `FilePasskeyStore`: `<data-dir>/passkeys.json`, public keys only, owner-only file |
| API | `GarageController`: `/api/doors`, `/api/status`, `/api/open`, `/api/close`, `/api/resume`, `/api/activity`, `/api/me` |
| Doors shown | `DoorRoster`: Ford (controlled by `GarageService`), Toyota shown with its control disabled until the server commands it. Channel 3 isn't shown |
| Web app | `src/main/resources/static`: `index.html`, `login.html`, `passkey.html`, plain JS and CSS, no build step |

## Signing in

1. **Invite.** On the server (it never leaves the machine):
   ```bash
   curl -X POST -d username=hubby http://127.0.0.1:8080/ott/generate
   cat data/invites/hubby.txt      # the link: single use, expires in 5 minutes (Spring's default)
   ```
   Send the link to him privately. Asking for an invite returns nothing to the caller, and a name
   outside `casita.security.family` gets nothing written.
2. **He opens it** on his iPhone and taps *Sign in*. That signs him in once and lands on *Set up
   Face ID*, which creates a passkey. Casita stores only its public key.
3. **From then on** he signs in with Face ID. iCloud Keychain syncs the passkey to his other Apple
   devices.

## Threat model

Casita is reachable only on our tailnet, so an attacker must first be a device on it (or hold one).

| Threat | What stops it |
|---|---|
| Someone on the internet | No public endpoint: Casita binds to 127.0.0.1; `tailscale serve` publishes it to the tailnet only |
| A tailnet device that isn't family | Every page and API call needs a signed-in family member (role `FAMILY`); `/api` answers 401, pages go to `/login` |
| Password guessing, phishing | There are no passwords. Passkeys are bound to Casita's host name (`rp-id`) and allowed origins, so a look-alike site can't use them |
| A stolen invite link | Single use and short-lived; written to an owner-only file on the server, never logged, never shown to whoever asked |
| Cross-site request forgery | CSRF tokens on every POST (Spring's single-page-app mode: `XSRF-TOKEN` cookie, `X-XSRF-TOKEN` header), plus `SameSite=Lax` session cookies |
| Injected script | Content-Security-Policy `script-src 'self'`, no inline script or style; pages build text with `textContent` only |
| Clickjacking | `frame-ancestors 'none'` and `X-Frame-Options: DENY` |
| A stolen session cookie | `HttpOnly` and `Secure`; ends after 7 idle days or a restart. Revocation (below) is still to do |
| An unlocked phone in the wrong hands | Accepted in the design (no extra Face ID per action). Explicit one-tap actions (never a toggle: *Open Ford* can only open), the activity log, and `GarageService`'s rules limit it |
| The server's disk | `data/` holds public keys and short-lived invite links only, owner-only; it is outside git |

Out of scope here: a compromised server or tailnet admin account, and the opener's physical safety
(photo eyes, auto-reverse).

## Sessions and cookies

- The session lives in memory: a restart signs everyone out, and Face ID signs them back in.
- `server.servlet.session.timeout: 7d` (idle) until the design's remember-me lands.
- Session cookie: `HttpOnly`, `Secure`, `SameSite=Lax`. Lax rather than Strict, so opening Casita
  from a notification or a Shortcut keeps you signed in; CSRF tokens protect every POST either way.
- `dev` profile only: the cookie isn't `Secure`, for plain `http://localhost`. Never behind Tailscale.

## Tailscale

```yaml
casita:
  security:
    rp-id: casita.<tailnet>.ts.net
    allowed-origins: [https://casita.<tailnet>.ts.net]
    family: [shir, hubby]
```

- `tailscale serve` gives Casita HTTPS on that name and forwards to `http://127.0.0.1:8080`, adding
  `X-Forwarded-Proto: https` and `X-Forwarded-Host`.
- Casita trusts those two headers **from 127.0.0.1 only** (`server.forward-headers-strategy: native`,
  `server.tomcat.remoteip.internal-proxies`). It listens on 127.0.0.1 alone, so only a local proxy can
  set them. With them, Casita knows it is `https://<name>.ts.net`: cookies are `Secure`, and redirects
  (which are also relative) never point at `http://127.0.0.1:8080`. Without this, a successful invite
  sign-in redirected the phone to `http://127.0.0.1:8080/passkey`; Safari blocked it, and a second
  tap of *Sign in* got 403 because the sign-in had already rotated the CSRF token.
- Passkeys need HTTPS and a fixed name. If the tailnet name changes, everyone enrolls again.
- An invite works once. If a sign-in shows an error page, ask for a fresh invite rather than
  tapping again.

## The app

Two doors, one house: Ford above, Toyota below, with equal weight, and Watson between them.

- **Each door** has its name, its state in large serif type, one short line, and one round control
  (72 pt, 66 on a small iPhone) on the right. States: *Closed* and *Not closed* ("Since 6:42 PM"),
  *Opening…* / *Closing…* ("About 6 seconds"), *Checking…*, *Unknown* ("The sensor can't confirm
  where Ford is."), *Needs a check* (held).
- **Which doors can be commanded comes from the server.** A door's control is enabled only when
  `/api/doors` reports it `controllable` and its control is `simulated` or `live`. Otherwise it is
  disabled and says why: *Toyota control not set up yet* (the server doesn't command Toyota), or
  *… control is off*. Today the server commands Ford only, so Toyota's control is in place but
  disabled. Toyota commands are separate work (per-door endpoints, each door run separately,
  Ford's safety rules), reviewed on their own; once the server reports Toyota controllable, its
  control works with no page change, and on a real door only when the server reports it live.
- **Watson** peeks over the hairline between the doors, chin on the line: the approved
  illustration (`casita.brand.illustration`, `static/brand/watson-home.webp`, 4:3, transparent),
  about half the screen wide and at most 220 pt (197 pt on a standard iPhone, 188 on an SE). It is
  fitted, never cropped, and never overlaps a door's status or control. Without it configured, the
  page shows no illustration and no placeholder.
- **Can't reach home:** "ET phone home" and "Can't reach home. Trying again…", with *Waiting for
  connection* in place of both doors. No stale status or history, nothing to press, nothing retried.
- **Measured versus estimated.** The sensor measures only closed / not closed, so the app never says
  *Open* as a state or how far up a door is. While a door moves, "About X seconds" and a dashed bar
  labelled *Estimated* come from the configured travel time, not the sensor.
- **One tap per door**, no hold and no confirmation. Safeguards:
  - each control calls one explicit endpoint for its door (`/api/open` or `/api/close` for Ford;
    `/api/doors/{door}/open` or `/close` once the server has per-door endpoints), never a toggle;
  - a door's control is disabled before its request leaves, until the server answers, and while the
    server reports that door moving or being checked; the other door stays usable;
  - the server refuses any command while one is unchecked (`BUSY`), and never sends toward the state
    the sensor already reads (`ALREADY`); simultaneous taps move the door once (`GarageControllerTest`);
  - a request that times out (10 s) or is lost is reported as "may or may not have arrived".
- **Success waits for the sensor.** "✓ Sensor confirmed closed" (or "not closed") appears under a
  door only once that door's check is in the history.
- **Held:** after a stuck or lost movement, *I've checked the door* appears under that door; it asks
  for confirmation and calls resume.
- **History** is one tap away: the last event is a row; tapping it opens a sheet with the rest.
- **Account:** a person icon (top right) opens a sheet: who's signed in (and on which device, once
  device access lands), sign out, and the family's line.
- **Mode tag:** *Practice door* (simulated), *Door control off*, *Rehearsal* (dry run).
- **App icon:** the approved sage Watson icon (`static/icons/`), in the manifest and as the
  `apple-touch-icon`. iOS fetches it before sign-in, so `/icons/**` must be public: that is in
  `SecurityConfig` (PR #8).
- **Look:** the approved palette, defined once at the top of `casita.css`: sage `#6B7F6B`,
  terracotta `#C65A3A` (only the active controls), warm cream `#F8F4ED`, deep pine `#2E4A3F`, soft
  sand `#D9C9B5`, plus darker shades where text needs AA contrast. Serif for states and headings,
  the system font for the rest. No cards, boxes or shadows.
- **iPhone:** safe areas on all sides, 44 pt+ targets, both doors on screen without scrolling on a
  375×667 iPhone, sheets from the bottom. *Reduce Motion* turns off transitions.

Run it on the practice door:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--casita.security.family=shir --casita.security.data-dir=target/dev-data"
```

### Words and the family's identity

- **`static/voice.js`** holds every line Casita says on the home screen; `app.js` holds only behaviour.
  The door's rules stay in `GarageService`. Copy never says "open" for a door the sensor only knows is
  not closed (`BrandTest` checks it).
- **`casita.brand`** in `application.yml` holds the family's own words, served to signed-in family only
  at `/api/brand`. The sign-in page stays generic.

  ```yaml
  casita:
    brand:
      household: wild ones   # "For the wild ones." in the account sheet; empty hides it
      companion: Watson      # the dog's name, for copy that mentions him
      about-line: ""         # one quiet line in the account sheet; empty hides it
      illustration: ""       # Watson's approved home-screen art, e.g. /brand/watson-home.png; empty: none
  ```

### Watson and the app icon (waiting for the artwork)

`static/watson.svg` is a placeholder; don't redraw it, replace it. When the hand-painted art arrives:

| File (in `static/`) | Size | Used for |
|---|---|---|
| `brand/watson-home.png` (or `.webp` / `.svg`), set as `casita.brand.illustration` | transparent background, about 600 px tall (200 pt at 3×) | home screen, resting on the divider |
| `watson.svg` (replace in place) | square, at least 264 × 264 | sign-in and Face ID pages |
| `icons/apple-touch-icon.png` | 180 × 180, no transparency | iPhone *Add to Home Screen* |
| `icons/icon-192.png`, `icons/icon-512.png` | 192 and 512 square | `manifest.webmanifest` |
| `icons/icon-maskable-512.png` | 512, art inside the middle 80% | Android/maskable |

Then add `<link rel="apple-touch-icon" href="/icons/apple-touch-icon.png">` to the pages, list the PNGs
in `manifest.webmanifest`, and add `/icons/**` to the public paths in `SecurityConfig` (the manifest
and icons are fetched without the session cookie). The manifest is already public and names only
"Casita".

Run it on the practice door:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--casita.security.family=shir --casita.security.data-dir=target/dev-data"
```

## Arrival notifications (design only, not built)

Goal: when someone drives home, Casita offers to open the garage. **It never opens on its own.**

1. **Signals, on the phone.** An iOS Shortcuts automation: *Arrive* at home (geofence) is the main
   trigger. *Bluetooth connected/disconnected* to the car is an extra signal, so walking past the
   house doesn't count. The automation needs both: arrival while connected to the car.
2. **The phone tells Casita.** The Shortcut calls `POST /api/arrival` with that phone's
   status-only Shortcut token (design doc, section 3: random 256-bit, stored hashed). The token can
   say "I'm arriving". It can't open or close.
3. **Casita answers with a question, not an action.** If Ford is closed and nothing is held, Casita
   sends a push notification: "Welcome home! Want me to open Ford?". Tapping it opens Casita's
   arrival sheet (`/?arrive=1`): *Open Ford* (one tap, as usual) or *Not now*.
4. **Rate limits.** One arrival prompt per person per 15 minutes; none while a movement is
   unchecked or held.

Needs first: the push service (ntfy or SNS, open in the design), Shortcut tokens, and real door
control through the app.

## Still to do

- **Remember-me and revocation** (design section 3): a 6-month signed cookie with a per-user secret,
  and `casita revoke` expiring sessions, rotating secrets and deleting tokens. Until then: remove a
  person from `family`, delete their entries in `passkeys.json`, and restart.
- **Parameter Store** for passkeys and secrets (build step 5), replacing `FilePasskeyStore`.
- **Push notifications** for every move and failure (`LoggingNotifier` only logs today).
- **Styling Spring's token page** (`/login/ott`): it works but looks like Spring's default.
- **Watson's illustration and the app icon**: the slots are ready (above); the art isn't.
- **Enabling real door control in the UI**: after review.
