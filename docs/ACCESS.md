# Casita: who can use it, from which devices

**A passkey says who. The tailnet node says which device.** A session needs both, for the same family
member, on every request. Code: `home.casita.security.access`, wired in `SecurityConfig`.

## The rules

- **One invite = one device.** An invite is for one member and carries the intended device label
  ("Shir's iPhone"). It is single use, lasts **5 minutes**, can be revoked, and only a SHA-256 of its
  token is stored (in the security store: `docs/PERSISTENCE.md`). The token is never logged, and the
  admin's answer never contains it.
- **One node, one member.** A tailnet node is authorized for at most one member. An authorized node
  can't be enrolled again, and a revoked one only for the same member, until the admin releases it.
- **An invite gives an enrollment-only session.** That session can do one thing: set up a passkey, or
  use the member's existing synced passkey, **on the device that redeemed the invite**. Doing so
  authorizes that device (its tailnet node) for that member. An enrollment session can't reach the
  garage, the API, or the home page.
- **User verification is required** for creating and using a passkey (`userVerification: required`).
  On an iPhone that's Face ID, or the device passcode when Face ID isn't available. It proves someone
  unlocked that phone, not who held it.
- **An existing synced passkey is fine, but its device still needs its own invite.** iCloud Keychain
  syncs Shir's passkey to her iPad, so the iPad can sign in right away, but it gets nothing until an
  invite for it is redeemed there and she signs in there.
- **Every signed-in request is checked again** (`AccessCheckFilter`). It must be a passkey sign-in; the
  member must still be in `casita.security.family`; the passkey used must still exist and be theirs;
  the node must still be authorized for them; and the request must come from the node the session
  signed in on. Any failure ends the session there and then: the API answers 401, and pages go to sign-in.
- **Fail closed.** If Casita can't verify the device, the invite isn't redeemed (it stays unused, and
  the page says so), no passkey can be registered, and no session is accepted.
- **Administration happens on the server only** (`LocalAdmin`): a direct request to 127.0.0.1 with no
  proxy headers, carrying the secret from `<data-dir>/admin.secret`. `bin/casita-access` does this.
- Labels, node names, user agents and IP addresses are for people to read. None of them is proof
  of identity. There's no browser fingerprinting.

## How Casita knows the device

Verified against Tailscale 1.104 (`ipn/ipnlocal/serve.go`) and Go's `httputil.ReverseProxy`:

1. `tailscale serve` terminates HTTPS for `<name>.ts.net` and proxies to `http://127.0.0.1:8080`.
   Its proxy uses `Rewrite`, which **removes any client-sent** `Forwarded` and `X-Forwarded-*`
   headers. It then sets `X-Forwarded-For` to the WireGuard source address of the connection, sets
   `X-Forwarded-Proto: https`, and keeps `Host`.
2. Serve also **deletes any client-sent** `Tailscale-User-Login`, `-Name`, `-Profile-Pic` and
   `Tailscale-Funnel-Request`, along with `Tailscale-Headers-Info`. For a source node owned by a
   user (untagged), it then sets `Tailscale-User-Login` from tailscaled's WhoIs. Funnel requests get
   `Tailscale-Funnel-Request: ?1`.
3. Casita listens on 127.0.0.1 only. Tomcat accepts `X-Forwarded-*` only from 127.0.0.1
   (`server.tomcat.remoteip.internal-proxies`), so the remote address Casita sees is the one serve
   set, **as long as nothing else on the server sends Casita requests.** Any local process can connect to
   127.0.0.1 and claim a tailnet address in `X-Forwarded-For` along with a matching
   `Tailscale-User-Login`. Step 4 doesn't stop that: tailscaled vouches for the real node behind the
   *claimed* address. Such a process can therefore pose as any authorized device. It would still need
   that member's passkey (with Face ID or the passcode) or a stolen session cookie. A process like that
   means the server is compromised, which is out of scope (below); keep the server single-purpose.
4. `TailscaleDeviceIdentity` refuses the request when any of these holds:
   - it is a Funnel request;
   - the address isn't in 100.64.0.0/10 or fd7a:115c:a1e0::/48;
   - `Tailscale-User-Login` is missing;
   - tailscaled (`tailscale whois --json <address>`) doesn't know the address;
   - the node is tagged;
   - tailscaled's user differs from the header.

   Otherwise, the device is the node's **StableID**. Whois answers are cached for 30 seconds; it
   times out after 3 seconds, and any error means "unverified".

Settings: `casita.security.device-identity: tailscale` (the default) and `casita.security.tailscale-cli`
(on a Mac with the App Store app: `/Applications/Tailscale.app/Contents/MacOS/Tailscale`). The `dev`
profile uses `development`, which treats a direct request to `http://localhost` as "this computer". It
is refused for any `rp-id` other than `localhost`.

**What this trusts:** tailscaled and `tailscale serve` on the server, every other process on the
server (see step 3), and the tailnet's admin. A compromised server or tailnet admin account is out of
scope (as in `APP.md`).

## Inviting a device

```bash
bin/casita-access invite hubby "Hubby's iPhone"
cat data/invites/i-1a2b3c4d.txt          # the link; send it privately; good for 5 minutes, once
```

On the phone (Tailscale on): open the link → *Sign in* → *Set up Face ID*. Or, if the phone already
has the member's Casita passkey through iCloud Keychain, use *Sign in with it here* instead. Either
way, that phone is now authorized for him. Then he signs in with Face ID.

A Safari tab and a Home Screen app have separate cookies, but they are the same tailnet node. So
signing in from either one works once the device is authorized.

## Revoking

```bash
bin/casita-access list                         # members, their devices and passkeys, open invites
bin/casita-access revoke-device nAbC123CNTRL   # that device (its node id, from 'list')
bin/casita-access release-device nAbC123CNTRL  # after revoking: lets another member's invite enroll it
bin/casita-access revoke-passkey <credential>  # that passkey, on every device it synced to
bin/casita-access revoke-member hubby          # all his devices, passkeys and invites
bin/casita-access revoke-invite i-1a2b3c4d     # unused, or an enrollment in progress (see below)
```

`revoke-invite` says exactly what happened. `"revoked": true` (revoked, or already revoked) means the
link can't be used. Any other answer prints `FAILED` and exits 1, and nothing should be assumed:

- `already-enrolled`: the invite has already set up its device, so revoke the device instead;
- `not-found`: if the invite was created seconds ago and its link file is gone, run it again;
- an unreachable store: check with `list`.

The link file is deleted only once the link can't be used.

Revocation takes effect on the next request of every affected session, and it is on disk before the
command returns. A revoked device is kept, marked revoked, so a restart can't bring it back. To use it
again, it needs a new invite. Revoking a passkey removes it everywhere iCloud synced it, so the member
needs a new invite on a device and sets up a new passkey there.

## Restarts and outages

Sessions live in memory, so a restart signs everyone out (Face ID signs them back in). Members, passkeys,
device authorizations and invitations are in the security store (`data/security.json`, or DynamoDB on
AWS): a pending invite still works after a restart, an enrollment interrupted by one needs a new invite,
and revocations stay revoked. If the store can't be reached, every request is refused until it can.

## Limits, honestly

- A device is a tailnet node. If someone else uses an authorized phone, they still need to unlock it
  and pass the passkey's user verification.
- Removing a phone from the tailnet makes Casita refuse it, and so does re-adding it as a new node:
  the StableID changes, so it needs a new invite.
- A passkey can't be bound to one device: iCloud Keychain syncs it, by design. The node check is
  what limits devices.
- Passkey "attestation" isn't used (`attestation: none`): Apple doesn't provide it for synced passkeys.
