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
| Who and which device | `security.access`: a passkey for who, the tailnet node for which device; one invite per device; revocation. See `docs/ACCESS.md` |
| Invites and administration | `bin/casita-access` on the server (`/admin`, 127.0.0.1 plus a secret only); invite links go to owner-only files |
| Face ID setup | `/passkey` (Spring's script, Casita's page) |
| Security store | Members, passkeys (public keys only), device authorizations and invitations: `<data-dir>/security.json` (owner-only) for local development, DynamoDB on AWS (`docs/PERSISTENCE.md`) |
| API | `GarageController`: `/api/doors`, `/api/status`, `/api/open`, `/api/close`, `/api/resume`, `/api/activity`, `/api/me` |
| Doors shown | `DoorRoster`: Ford (controlled by `GarageService`), Toyota read-only. Channel 3 isn't shown |
| Web app | `src/main/resources/static`: `index.html`, `login.html`, `passkey.html`, plain JS and CSS, no build step |

## Signing in

Full rules and the Tailscale trust chain are in `docs/ACCESS.md`. In short:

1. **Invite one device.** On the server:
   ```bash
   bin/casita-access invite hubby "Hubby's iPhone"
   cat data/invites/i-….txt     # the link: single use, 5 minutes, never logged
   ```
   Send the link to him privately.
2. **He opens it on that iPhone** (Tailscale on) and taps *Sign in*. That gives a session that can only
   set up Face ID on that phone. He taps *Set up Face ID*, or *Sign in with it here* if his iCloud
   Keychain already has his Casita passkey. Either way, that phone is now authorized for him.
3. **From then on** he signs in with Face ID on that phone. Another device, even with the synced
   passkey, needs its own invite.

## Threat model

Casita is reachable only on our tailnet, so an attacker must first be a device on it (or hold one).

| Threat | What stops it |
|---|---|
| Someone on the internet | No public endpoint: Casita binds to 127.0.0.1; `tailscale serve` publishes it to the tailnet only |
| A tailnet device that isn't family | Every page and API call needs a passkey sign-in by a family member **on a device authorized for them**, checked again on every request; `/api` answers 401, pages go to `/login` |
| A family member's passkey on an unauthorized device | The device (tailnet node, from `tailscale serve` and tailscaled) must have been enrolled through its own invite |
| Password guessing, phishing | There are no passwords. Passkeys are bound to Casita's host name (`rp-id`) and allowed origins, so a look-alike site can't use them |
| A stolen invite link | Single use, 5 minutes, revocable; it only gives an enrollment session (no garage) for the device that redeems it; written to an owner-only file on the server, never logged |
| Cross-site request forgery | CSRF tokens on every POST (Spring's single-page-app mode: `XSRF-TOKEN` cookie, `X-XSRF-TOKEN` header), plus `SameSite=Lax` session cookies |
| Injected script | Content-Security-Policy `script-src 'self'`, no inline script or style; pages build text with `textContent` only |
| Clickjacking | `frame-ancestors 'none'` and `X-Frame-Options: DENY` |
| A stolen session cookie | `HttpOnly` and `Secure`; tied to the device it signed in on (a request from any other node ends it); ends on revocation, after 7 idle days, or at a restart |
| An unlocked phone in the wrong hands | Accepted in the design (no extra Face ID per action). Explicit one-tap actions (never a toggle: *Open Ford* can only open), the activity log, and `GarageService`'s rules limit it |
| The server's disk | `data/` holds public keys, device authorizations, short-lived invite links and the admin secret, owner-only; it is outside git |

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

One door, one clear status, one action.

- **Ford** is the focus: its name, the state in large serif type, and one short line. States:
  *Closed* and *Not closed* ("Since 6:42 PM"), *Opening…* / *Closing…* ("About 6 seconds"),
  *Checking…*, *Unknown* ("The sensor can't confirm where Ford is."), *Needs a check* (held).
- **Watson** rests on the divider between Ford's status and Toyota's row, about 200 pt tall on a
  standard iPhone and scaled down (never hidden) on smaller ones. The slot is
  `casita.brand.illustration`, a file in `static/brand/`; it stays empty until the approved artwork
  exists, and the status then sits centred, so nothing looks missing. The art is fitted, never
  cropped. If the approved pose doesn't rest on a line, its placement is adjusted to the art.
- **Can't reach home:** "ET phone home" and "Can't reach home. Trying again…", the button *Waiting
  for connection* (disabled). No stale status, Toyota or history, and nothing is retried on its own.
- **Measured versus estimated.** The sensor measures only closed / not closed, so the app never says
  *Open* or how far up a door is. While Ford moves, "About X seconds" and a dashed bar labelled
  *Estimated* come from `casita.garage.travel-time` (`/api/doors` `travelTimeSeconds`), not the sensor.
- **The action** is one tap on one button in the thumb zone: *Open Ford* when closed, *Close Ford*
  when it may close. Press-and-hold and any confirmation step are gone. Safeguards:
  - each button calls one explicit endpoint (`/api/open` or `/api/close`), never a toggle, so a
    repeated or late tap can't turn into the opposite command;
  - the button is disabled before the request leaves, until the server answers, and while the
    server reports the door moving or being checked;
  - the server refuses any command while one is unchecked (`BUSY`), and never sends toward the state
    the sensor already reads (`ALREADY`). Simultaneous taps from several phones move the door once
    (`GarageControllerTest`);
  - a request that times out (10 s) or is lost is reported as "may or may not have arrived", and
    nothing else is offered until the server's sensor check.
- **Success waits for the sensor.** "✓ Sensor confirmed closed" (or "not closed") appears only once
  `GarageService`'s check is in the history. An unconfirmed or timed-out command says so in one line.
- **Held:** after a stuck or lost movement the button becomes *I've checked the door*, which asks
  for confirmation and calls `/api/resume`.
- **Toyota:** one quiet line, "Toyota · Closed · view only". Never a button.
- **History** is one tap away: the last event is a row; tapping it opens a sheet with the rest.
- **Account:** a person icon (top right) opens a sheet: who's signed in (and on which device, once
  device access lands), sign out, and the family's line. Watson appears once, on the home screen.
- **Mode tag:** *Practice door* (simulated), *Door control off*, *Rehearsal* (dry run). In off and
  dry-run the button is disabled.
- **Look:** the approved palette, defined once at the top of `casita.css`: sage `#6B7F6B`,
  terracotta `#C65A3A` (only the action), warm cream `#F8F4ED`, deep pine `#2E4A3F`, soft sand
  `#D9C9B5`, plus darker shades where text needs AA contrast. Serif for the state and headings, the
  system font for the rest. No cards or shadows; Watson's illustration is the only picture.
- **iPhone:** safe areas on all sides, 44 pt+ targets, a 60 pt action button, sheets from the bottom.
  *Reduce Motion* turns off transitions.

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

- **Remember-me** (design section 3): a 6-month signed cookie. Revocation is built (`docs/ACCESS.md`).
- **AWS persistence**: DynamoDB for members, passkeys, device authorizations and activity, plus
  Parameter Store for secrets (`docs/PERSISTENCE.md`), replacing the files.
- **Push notifications** for every move and failure (`LoggingNotifier` only logs today).
- **Styling Spring's token page** (`/login/ott`): it works but looks like Spring's default.
- **Watson's illustration and the app icon**: the slots are ready (above); the art isn't.
- **Enabling real door control in the UI**: after review.
