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
