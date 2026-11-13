# Casita's Garage

Casita is our home's helper. This project lets the family see and move the garage door from
their iPhones, privately and safely.

- Runs on a small Compute Engine VM (watson), on public HTTPS at `watsonbental.duckdns.org` behind Caddy; passkeys and per-browser invites decide who gets in (docs/ACCESS.md, docs/PUBLIC_HTTPS.md).
- Talks to the Meross MSG100 garage door opener through Meross's cloud.
- Family members sign in with passkeys.

See [docs/DESIGN.md](docs/DESIGN.md) for how it works.

## Build

Needs Java 21 and Maven 3.9+.

```sh
mvn verify                 # build and run the tests
mvn spring-boot:run        # run locally on http://127.0.0.1:8080
```

CI builds and tests every pull request.

## The app

Family members sign in with Face ID (passkeys) and see Ford's status, one tap-to-act button and a
short history; Toyota is shown beside it, its control disabled until the server supports it. It runs on a practice door by default. See
[docs/APP.md](docs/APP.md) for the app, the threat model and what's still to do;
[docs/ACCESS.md](docs/ACCESS.md) for inviting and revoking devices (one invite per device, `bin/casita-access`);
[docs/PERSISTENCE.md](docs/PERSISTENCE.md) for the planned DynamoDB storage on AWS.

```sh
mvn spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--casita.security.family=shir --casita.security.data-dir=target/dev-data"
```

## API

Each door (Ford, Toyota) has its own `GarageService` and its own safety rules: its own movement, hold
and resume, and its own activity entries. One door moving, stuck or held never blocks the other.

| | |
|---|---|
| `GET /api/doors` | every door: `key`, `name`, `door` (state), `status`, `control` (`simulated`, `off`, `dry-run` or `live`) and `live` |
| `GET /api/doors/{door}` | one door (`ford`, `toyota`) |
| `POST /api/doors/{door}/open`, `/close` | one explicit command to one door; never a toggle |
| `POST /api/doors/{door}/resume` | after that door's movement ended stuck or lost: someone has checked it (sends nothing) |
| `GET /api/activity` | newest first, every door; `?door=toyota` for one |
| `GET /api/status`, `POST /api/open`, `/close`, `/resume` | the older single-door calls: Ford only |

Every call needs a signed-in family member (passkey sign-in, docs/APP.md), and every POST the CSRF token.
`live` is true only when a command moves the real door; the app must never suggest real control of a door
whose `live` is false. On the practice door (the default) both doors are simulated. With the real doors
(`casita.door=meross`), a door is sent commands only when `casita.meross.command-mode` allows and its channel
is in `casita.meross.command-channels`, the per-door switch: `[1]`, Ford only. Toyota (channel 2) stays off
until its supervised hardware test; see [docs/MEROSS.md](docs/MEROSS.md#toyota-channel-2).
