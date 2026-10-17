# Casita's Garage

Casita is our home's helper. This project lets the family see and move the garage door from
their iPhones, privately and safely.

- Runs on a small AWS EC2 server, reachable only over Tailscale.
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
short history; Toyota is shown read-only. It runs on a practice door by default. See
[docs/APP.md](docs/APP.md) for enrolling a phone, the threat model and what's still to do.

```sh
mvn spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--casita.security.family=shir --casita.security.data-dir=target/dev-data"
```

## API

`GET /api/status`, `POST /api/open`, `POST /api/close`, `GET /api/activity` (design doc, section 8),
over `GarageService` and its safety rules, plus `POST /api/resume`: after a movement ends stuck or lost,
every command is held until someone has checked the door and resumes. Every call needs a signed-in
family member (passkey sign-in, docs/APP.md). With the real door (`casita.door=meross`), commands still
reach it only when `casita.meross.command-mode` allows, and only for Ford (channel 1).
See [docs/MEROSS.md](docs/MEROSS.md).
