# Polling and caching

How the home screen keeps the doors' status current without wasting requests (each signed-in request
costs Firestore reads once persistence moves there), and how the browser caches the app's files.

## Polling (`static/poller.js`, used by `app.js`)

| Situation | Polls `/api/doors` + `/api/activity` |
|---|---|
| Shown, someone using it | every 5 s |
| A door opening, closing or being checked; or the 30 s after a command | every 1 s |
| Nobody has touched the page for 3 minutes (idle) | every 60 s; a door seen moving: every 5 s |
| First touch after an idle spell | straight away, then every 5 s |
| Hidden (`visibilitychange`), or put away (`pagehide`) | never |
| Shown again (`visibilitychange`, `pageshow` from the back/forward cache) | straight away, then as above |
| A poll failed or took over 10 s | retry after 2, 4, 8, 16, 32, 60, 60… s, each jittered to between half and all of that; the first success goes back to the normal rhythm |

- **One request at a time.** Anything asking for a refresh while a poll is out (a command finishing, the
  page being shown, a touch after idle) queues exactly one more poll, right after it.
- **A failure is never a status.** A failed or timed-out poll shows "ET phone home" with no doors, exactly as
  before. A poll that answers after its time-out is aborted and thrown away, so an old answer never
  overwrites a newer one. Retries no longer hammer the server every second while it's down.
- **Watson** is unchanged: excited only while the server reports a door opening or closing. Slower polling
  while idle means he may notice someone else's movement up to a minute late; he never guesses.
- **How stale the screen can be:** while shown, at most 5 s (60 s when nobody has touched it for 3 minutes).
  Any touch or showing the page again refreshes immediately.

Tests: `src/test/resources/js/poller.test.js` (20 scenarios with a fake clock: visibility, idle, dedup,
backoff, jitter, time-outs), run by `PollerScriptTest` under GraalJS, a test-only Maven dependency, so the
build still needs only a JDK and Maven (no Node).

## Caching (`PagesConfig`, `PagesController`)

| Response | `Cache-Control` |
|---|---|
| Scripts, stylesheet, pictures, icons by their hashed URL, e.g. `/app-<md5>.js`, `/brand/watson-idle-<md5>.webp` | `max-age=31536000, public, immutable` |
| Pages (`/`, `/login`, `/passkey`, `/webauthn/register`, the `.html` names) and `/manifest.webmanifest` | `no-cache`, with an ETag (a reload is a 304) |
| The same files by their plain names (`/app.js`) | `no-cache` (revalidated by Last-Modified) |
| `/api/**`, sign-in, passkeys, invites, sign-out, errors, redirects to sign-in | `no-cache, no-store, max-age=0, must-revalidate` (Spring Security's default, unchanged) |

- The hash is of the file's content (Spring's `VersionResourceResolver`). The pages are rewritten at
  startup to link the hashed URLs, `/api/brand` gives Watson's pictures by theirs, and the stylesheet's link
  to the app icon is rewritten too (its hash covers the icon, so a new icon changes the stylesheet's URL).
  A deploy that changes any file, new Watson artwork included, changes its URL; an old hash answers 404,
  never a stale file.
- Who may fetch what is unchanged: the files that are public by their plain names (the sign-in and passkey
  pages' scripts and stylesheet, Watson's sign-in drawing, the app icons) are public by their hashed names
  too, and only those files; everything else still needs a signed-in family member. All security headers are
  unchanged.
- Tests: `CacheHeadersTest` (every category above, 304 revalidation, stale hash, public versus signed-in);
  `PublicFilesTest` (a signed-out and an enrolling phone load everything the passkey page links, Add to
  Home Screen icons load without a session, and no other file, door or API path does).

## Measured traffic

Headless Chrome 155 driven over the DevTools protocol against both builds (`casita.door=simulated`, one
signed-in session, a fresh profile), bytes as received over the network (headers and bodies). "Hidden" is
emulated by setting `document.hidden` and firing `visibilitychange`, which runs the same code as a real tab
switch. The "after" `/api/activity` answers were about 180 bytes larger only because that server's history
had one simulated command in it.

| Home screen | Before (0da64c0) | After |
|---|---|---|
| Cold load | 16 requests, 192.5 KB | 16 requests, 200.3 KB |
| Warm reload | 15 requests, 191.8 KB | 8 requests, 5.5 KB (304 for the page and manifest, every file from cache) |
| A minute shown, in use | 24 requests, 16.8 KB | 24 requests, 19.0 KB (unchanged rhythm) |
| A minute shown, idle (3+ min untouched) | 24 requests, 16.7 KB | 2 requests, 1.6 KB |
| A minute hidden | 2 requests, 1.4 KB (one poll already scheduled) | 0 requests |
| Shown again | 1 poll straight away | 1 poll straight away |

A screen left open and untouched all day: before about 34,560 API requests a day; after about 2,880.
The cold load is a little larger because `poller.js` is a new file; the old `/favicon.ico` redirect to the
sign-in page is gone (the home screen now links its icon).
