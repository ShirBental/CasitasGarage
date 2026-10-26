# Staging never drives the real garage

Casita's staging server runs on a GCP Compute Engine VM (`watson`). It must never move Ford or Toyota.
`StagingGuard` makes that a start-up rule. Without it, staging would be one environment variable
(`CASITA_DOOR=meross`) plus credentials away from the real doors.

## The rule

`StagingGuard` is a Spring Boot `EnvironmentPostProcessor`, registered in `META-INF/spring.factories`. It
runs after every property source is loaded (`application.yml`, environment variables, command line, and
`SPRING_APPLICATION_JSON`), before any bean is created. It checks the **resolved** values, so it sees
exactly what the app would use. Casita refuses to start when:

| Condition | Why |
|---|---|
| On a Compute Engine VM, `casita.deployment` isn't `staging` or `production` (unset, empty, `local`, …) | The VM is recognised by its hardware: `/sys/class/dmi/id/product_name` reads `Google Compute Engine`. No property or environment variable can change that, so `CASITA_DEPLOYMENT=local` can't make the VM count as a laptop. If the file exists but can't be read, it counts as Compute Engine (fail closed) |
| `casita.deployment` is anything but empty, `local`, `staging` or `production` | No guessing: `prod`, `Production`, `Staging` and `stage` are refused everywhere |
| In staging, `casita.door` isn't exactly `simulated` | `meross`, `MEROSS`, `Simulated`, padded, empty or missing are all refused. (The door switch itself would accept `Simulated`; the guard is stricter on purpose) |
| In staging, `casita.meross.command-mode` isn't exactly `off` | `live`, `dry-run`, `OFF`, `false` (YAML's reading of a bare `off`), empty or missing are all refused |
| In staging, Meross credentials are present anywhere | `casita.meross.email`/`password` (e.g. `CASITA_MEROSS_EMAIL`), `MEROSS_EMAIL`/`MEROSS_PASSWORD` (the live tests' variables), or `config/meross.local.json` in the working directory |
| In staging, `casita.security.rp-id` isn't the server's public host name | It must be a lower-case DNS name with at least two labels and a letters-only top-level domain (watson: `watsonbental.duckdns.org`). `localhost`, an address, a placeholder such as `<PUBLIC_HOST>`, upper case or a trailing dot are refused. Passkeys are bound to this name, so it must be right before anyone enrolls |
| In staging, `casita.security.allowed-origins` isn't exactly `https://<rp-id>` | No other origin, no port, no `http`, no trailing slash |
| In staging, `casita.security.family` is empty, `device-identity` isn't `browser`, or `data-dir` isn't absolute | Who may sign in must be named; browsers are verified by the browser key their invite gave them (`tailscale` and `development` are refused); the admin secret, invite links and `device.key` live in a known place (`/var/lib/casita`) |
| In staging, `server.address` isn't the loopback address | Casita must listen on `127.0.0.1` only: Caddy is the only way in. `0.0.0.0`, the VM's own address or unset are refused |
| In staging, `casita.storage.type` isn't exactly `firestore`, or an emulator is configured | `casita.storage.firestore.emulator-host` or `FIRESTORE_EMULATOR_HOST`: staging's security store is the real, authenticated Firestore |
| In staging, session cookies aren't `Secure`, or the `dev` profile is active | The `dev` profile is for plain `http://localhost` only |

Every reason is reported at once, in one message ending "See docs/STAGING_GUARD.md". When staging passes,
the log has one line:

```
staging guard: ok deployment=staging compute_engine=true door=simulated command_mode=off meross_credentials=absent storage=firestore rp_id=watsonbental.duckdns.org
```

With `casita.door=simulated`, the Meross configuration (`MerossConfig`) isn't loaded at all. No
credentials are resolved, no session or MQTT client is created, and nothing contacts Meross. Ford and
Toyota are two separate simulated doors (`DoorControl.SIMULATED`).

**Local development is unchanged.** On a laptop (no DMI product name, or a different one) with
`casita.deployment` empty or `local`, the guard does nothing: the practice door, `casita.door=meross`,
dry runs and the opt-in live tests all work as before. That includes the iPhone preview.

## Environment variables

Spring binds `casita.meross.command-mode` from `CASITA_MEROSS_COMMANDMODE` (dots become underscores, dashes
are dropped); `deploy/watson/casita.env.example` lists every name staging uses. The guard reads each setting
both the way `@ConditionalOnProperty` reads it and the way `@ConfigurationProperties` binds it, and refuses a
setting the two would read differently. `StagingEnvironmentVariablesTest` starts Casita in its own JVM with
the template's variables and one unsafe override at a time, including `SPRING_APPLICATION_JSON` and
`JAVA_TOOL_OPTIONS=-D…`. Each is refused, and the template as filled in starts past the guard.

## What it doesn't do

- It doesn't protect a non-GCP machine that is deliberately set to `local`. That is a laptop by definition.
- A production deployment on Compute Engine isn't possible yet: on a VM only `staging` starts. Allowing
  production there will be a deliberate code change, made together with H1 and H3 (travel times and
  reading lag).
- It can be removed only by changing the code (deleting the class or its `spring.factories` entry). Spring
  Boot has no property that turns off an `EnvironmentPostProcessor`. Review any change to either file as a
  safety change.

## Tests

- **`StagingGuardTest`:** every rule above, one configuration at a time.
  - Each refused value of `casita.door`, `command-mode` and `casita.deployment`.
  - Credentials in each place.
  - Compute Engine with every non-staging deployment.
  - Hardware detection from a fake `product_name`, including an unreadable one.
  - The refusal thrown by `postProcessEnvironment`.
  - Local development untouched, even with Meross settings present.
- **`StagingStartupTest`:** the whole app started through the registered guard. Command-line arguments
  stand in for the VM's environment variables (same precedence over `application.yml`).
  - Staging starts with Ford and Toyota both `SIMULATED`, no Meross credentials, session or client beans,
    sign-in bound to `casita.example.org`, and the Firestore store (the emulator, injected as a
    bean so staging's own settings name none).
  - Each of these fails to start: staging with `casita.door=meross` (even with credentials), with commands
    `live` or `dry-run`, with credentials, with the placeholder host, with only `casita.deployment=staging`
    (the localhost defaults), with a file store or an emulator, with the `dev` profile, or with an unknown
    deployment.
  - With the guard unregistered, all seven refusals start instead; checked by hand on 2026-10-21.
- **`StagingEnvironmentVariablesTest`:** see "Environment variables" above.

## Verifying it on the GCP VM

Do this before the first real start of staging, and after any change to the unit, image or guard. It needs
no Meross account and moves nothing.

1. `cat /sys/class/dmi/id/product_name` prints `Google Compute Engine`.
2. `/etc/casita/casita.env` is `deploy/watson/casita.env.example` with its placeholders filled in: it sets
   `CASITA_DEPLOYMENT=staging`, `CASITA_DOOR=simulated` and `CASITA_MEROSS_COMMANDMODE=off`, and no Meross
   credentials or `MEROSS_*` variables. There is no `config/meross.local.json` under `/var/lib/casita`.
3. **Negative checks.** Start each once by hand and confirm it exits with "Staging guard: Casita won't start":
   - `CASITA_DEPLOYMENT=` (unset)
   - `CASITA_DEPLOYMENT=local`
   - `CASITA_DOOR=meross`
   - `CASITA_MEROSS_COMMANDMODE=live`
   - `CASITA_MEROSS_EMAIL=placeholder@example.com`
4. Start normally and find the `staging guard: ok …` line in `journalctl`.
5. Signed in on the staging site, `/api/doors` lists Ford and Toyota, both with `"control": "simulated"` and
   `"live": false`.

Record the date, image and commit with the results.

## Production: the real doors

`casita.deployment=production` is the only way the real doors run on watson. It keeps staging's sign-in and
storage rules (rp-id, origins, family, browser device identity, loopback listener, Firestore, Secure cookies, no `dev`
profile) and replaces the simulation rules with these. Casita refuses to start when, in production:

| Condition | Why |
|---|---|
| `casita.door` isn't exactly `meross` | Production is the real opener, nothing else |
| `casita.meross.command-mode` isn't exactly `off`, `dry-run` or `live` | Each phase is a deliberate setting |
| `casita.meross.doors` isn't exactly `1: Ford, 2: Toyota` | Channel 3 is permanently disabled (MerossProperties refuses it too) |
| `casita.meross.command-channels` names anything but 1 and 2 | Only Ford and Toyota can be commanded |
| `casita.meross.transport` isn't `cloud` or `local`, or `local` without a plain `http://<RFC 1918 IPv4>` `local-url` | Commands go to a literal private address, never a name or a public host |
| `casita.meross.email` or `password` is missing, or `MEROSS_EMAIL`/`MEROSS_PASSWORD` or `config/meross.local.json` is present | The account comes only from `/etc/casita/meross.env` (root-only, 0600) |
| In `live`, a commanded door lacks `casita.garage.travel-times.<door>` or `sensor-lags.<door>` | H1/H3: a door moves for real only on timings measured on it |

When production passes, the log has one line (checked by `casita-health`):

```
production guard: ok deployment=production compute_engine=true door=meross command_mode=off command_channels=[1, 2] transport=cloud storage=firestore rp_id=watsonbental.duckdns.org
```

The steps and the first supervised tests are in `deploy/watson/LIVE_DOORS.md`.
