# Casita: who can use it, from which browsers

**A passkey says who. An enrolled browser key says which browser.** A session needs both, for the same family
member, on every request. Code: `home.casita.security.access`, wired in `SecurityConfig`.

Casita is on the public internet at `https://watsonbental.duckdns.org` (Caddy in front, docs/PUBLIC_HTTPS.md).
There is no VPN and no network identity any more: anyone can reach the sign-in page, and nobody gets further without
an invite, a passkey and that passkey's user verification.

## What identifies what (the honest version)

| Thing | What it proves | What it doesn't |
|---|---|---|
| **Passkey** (WebAuthn, `userVerification: required`) | the holder has the member's private key and just unlocked it (Face ID or the device passcode) | which physical device: iCloud Keychain syncs it to every Apple device on that Apple Account |
| **Browser key** (`__Host-casita-device` cookie) | this browser was the one that redeemed an invite for that member (or holds a copy of what it got) | the physical phone: it is a bearer secret in one browser's cookie jar. Copying the cookie copies it |
| **Invite** | the admin, on the server, chose to let this member enroll one more browser, in the next 15 minutes, once | anything after it's used |

So the replacement for "this tailnet node" is "this enrolled browser", **not** "this physical phone". Casita does not
claim device attestation or hardware binding: Apple doesn't offer attestation for synced passkeys, and there is no
browser fingerprinting. What limits where Casita can be used is: an invite per browser, a passkey with user
verification on every sign-in, and revocation of either at any time.

## The rules

- **One invite = one browser.** An invite is for one member and carries the intended label ("Shir's iPhone"). It is
  single use, lasts **15 minutes**, can be revoked, and only a SHA-256 of its token is stored. The token is never
  logged (Caddy's access log redacts it too), and the admin's answer never contains it.
- **Redeeming an invite gives the browser its key.** If the browser has no valid browser key, Casita mints one at
  redemption: `b-` + 128 random bits, with an HMAC-SHA256 under the server's `device.key`, in a cookie that is
  `Secure`, `HttpOnly`, `SameSite=Lax`, `Path=/`, host-only (`__Host-`), for 400 days, renewed on every sign-in.
  Over plain http (not through Caddy) nothing is minted and nothing is redeemed. Signing in never mints a key.
- **One browser key, one member.** An authorized browser key can't be enrolled again; a revoked one only for the same
  member, until the admin releases it. (Clearing website data gives a browser a new key, so this guards against
  mistakes, not against someone who controls that browser.)
- **An invite gives an enrollment-only session.** It can do one thing: set up a passkey, or use the member's existing
  synced passkey, **in the browser that redeemed the invite**. Doing so authorizes that browser key for that member.
  An enrollment session can't reach the garage, the API, or the home page.
- **User verification is required** for creating and using a passkey. On an iPhone that's Face ID, or the passcode.
- **An existing synced passkey is fine, but each browser still needs its own invite.** Shir's passkey syncs to her
  iPad, but the iPad's Safari gets nothing until an invite is redeemed there and she signs in there. A Home Screen web
  app has its own cookie jar, so it may need its own invite (see "Safari and the Home Screen app").
- **Every signed-in request is checked again** (`AccessCheckFilter`): passkey sign-in; member still in
  `casita.security.family` and active; the passkey used still exists and is theirs; the browser key is still
  authorized for them; and the request carries the same browser key the session signed in with. Any failure ends the
  session there and then: the API answers 401, pages go to sign-in.
- **Fail closed.** No verifiable browser key: no redemption, no passkey registration, no session. Security store
  unreachable: every request refused.
- **Administration happens on the server only** (`LocalAdmin`): a direct request to 127.0.0.1 with no proxy headers
  (`X-Forwarded-*`, `Forwarded`, `Via`, `X-Real-IP`), carrying `<data-dir>/admin.secret`. Caddy answers `/admin` with
  404 itself. `casita-access` does this, reached through IAP SSH.
- **No public registration.** Spring's `/ott/generate` does nothing; passkeys can be registered only inside a live
  enrollment session.
- **Rate limits** on the public sign-in, invite and passkey-setup endpoints (`AuthRateLimitFilter`): 30 requests per
  client address and 300 in total per 10 minutes by default (`casita.security.rate-limit.*`); beyond that, 429.
- CSRF protection (Spring's single-page-app mode), a strict content security policy, HSTS, and `Secure`, `HttpOnly`,
  `SameSite=Lax` session cookies are unchanged. Sessions live in memory and end after a week idle or on restart.

## How Casita knows the client and the browser

1. Caddy terminates HTTPS for `watsonbental.duckdns.org` and proxies to `http://127.0.0.1:8080`. It sets
   `X-Forwarded-For`, `-Proto` and `-Host` from the real connection and **ignores** any a client sent (no
   `trusted_proxies`); it drops client-sent `Forwarded`, `X-Real-IP`, `X-Casita-Admin` and the old `Tailscale-*`
   headers. `deploy/watson/test/caddy.test.sh` checks all of this against a real Caddy.
2. Casita listens on 127.0.0.1 only (the start-up guard refuses anything else on the server), and Tomcat accepts
   `X-Forwarded-*` only from 127.0.0.1 (`server.tomcat.remoteip.internal-proxies`). So `request.isSecure()` and the
   client address are Caddy's, as long as nothing else on the server sends Casita requests. Another local process could
   claim anything, but it would still need a member's passkey or a stolen session or browser-key cookie: a server
   compromise is out of scope (below).
3. `BrowserDeviceIdentity` reads the `__Host-casita-device` cookie, over HTTPS only, and accepts it only if its MAC
   verifies under `device.key` (constant-time). Anything else is "no device".

**What this trusts:** Caddy and every other process on the server, the `device.key` file, and Firestore. A
compromised server is out of scope (as in `APP.md`).

## Inviting a browser

On watson, through IAP SSH (`gcloud compute ssh watson --zone us-central1-a --tunnel-through-iap`):

```bash
casita-access invite hubby "Hubby's iPhone"
sudo cat /var/lib/casita/invites/i-1a2b3c4d.txt   # the link; send it privately; good for 15 minutes, once
```

On the phone, in Safari: open the link → *Sign in* → *Set up Face ID*. Or, if the phone already has the member's
passkey for `watsonbental.duckdns.org` through iCloud Keychain, use *Sign in with it here* instead. Either way, that
browser is now authorized for him; from then on he signs in with Face ID.

### Safari and the Home Screen app

A Home Screen web app keeps its own cookies. If the Home Screen app says it isn't set up, send a second invite and
open it inside the Home Screen app (or add Casita to the Home Screen only after enrolling, then sign in there; if
that browser has no key, it needs its own invite). This is the price of not having a network identity.

## Revoking

```bash
casita-access list                          # members, their browsers (device ids b-...) and passkeys, open invites
casita-access revoke-device b-1a2b...       # that browser
casita-access release-device b-1a2b...      # after revoking: lets another member's invite enroll it
casita-access revoke-passkey <credential>   # that passkey, on every device it synced to
casita-access revoke-member hubby           # all his browsers, passkeys and invites
casita-access revoke-invite i-1a2b3c4d      # unused, or an enrollment in progress
```

`revoke-invite` says exactly what happened; anything but `"revoked": true` prints `FAILED` and exits 1. Revocation
takes effect on the next request of every affected session, and is in Firestore before the command returns.

## Siri and Shortcuts

Shortcuts can't use passkeys or the browser key, so they get **Shortcut tokens**: per phone, hashed, kept in Firestore
with the rest of the security state, revocable, open Ford only, on their own `/shortcut/**` chain that never reads a cookie or session (`docs/SHORTCUTS.md`). A token is a
bearer secret and proves nothing about who holds the phone or whether it is unlocked.

```bash
casita-access shortcut-token shir "Shir's iPhone"   # the token is shown once
casita-access shortcut-tokens
casita-access revoke-shortcut-token st-1a2b3c4d5e6f
```

`revoke-member` also revokes that member's Shortcut tokens.

## Restarts, outages and the device key

Sessions live in memory: a restart signs everyone out (Face ID signs them back in). Members, passkeys, browser
authorizations and invitations are in Firestore. `device.key` lives in the data directory on the VM: **if it is lost
or replaced, every browser key stops verifying and every browser needs a new invite** (passkeys and members are
unaffected). Back it up with the VM's disk, or accept re-enrolling.

## Limits, honestly

- An enrolled browser key is a bearer secret. Someone with the phone unlocked and the member's passkey (Face ID or
  passcode) gets in; someone who steals only the cookie still needs the passkey and its user verification.
- iCloud Keychain syncs passkeys by design; the browser key is what limits where they work.
- No attestation (`attestation: none`), no fingerprinting, no claim of physical-device verification.
- Clearing Safari's website data removes the browser key: that browser needs a new invite.
- The old Tailscale-era device records (tailnet StableIDs) stay in Firestore, unused; old passkeys for
  `watson.<tailnet>.ts.net` stay too, and can be revoked with `revoke-passkey` once nobody needs the old name.
