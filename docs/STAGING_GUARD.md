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
| On a Compute Engine VM, `casita.deployment` isn't `staging` (unset, empty, `local`, …) | The VM is recognised by its hardware: `/sys/class/dmi/id/product_name` reads `Google Compute Engine`. No property or environment variable can change that, so `CASITA_DEPLOYMENT=local` can't make the VM count as a laptop. If the file exists but can't be read, it counts as Compute Engine (fail closed) |
| `casita.deployment` is anything but empty, `local` or `staging` | No guessing: `production`, `Staging` and `stage` are refused everywhere |
| In staging, `casita.door` isn't exactly `simulated` | `meross`, `MEROSS`, `Simulated`, padded, empty or missing are all refused. (The door switch itself would accept `Simulated`; the guard is stricter on purpose) |
| In staging, `casita.meross.command-mode` isn't exactly `off` | `live`, `dry-run`, `OFF`, `false` (YAML's reading of a bare `off`), empty or missing are all refused |
| In staging, Meross credentials are present anywhere | `casita.meross.email`/`password` (e.g. `CASITA_MEROSS_EMAIL`), `MEROSS_EMAIL`/`MEROSS_PASSWORD` (the live tests' variables), or `config/meross.local.json` in the working directory |

Every reason is reported at once, in one message ending "See docs/STAGING_GUARD.md". When staging passes,
the log has one line:

```
staging guard: ok deployment=staging compute_engine=true door=simulated command_mode=off meross_credentials=absent
```

With `casita.door=simulated`, the Meross configuration (`MerossConfig`) isn't loaded at all. No
credentials are resolved, no session or MQTT client is created, and nothing contacts Meross. Ford and
Toyota are two separate simulated doors (`DoorControl.SIMULATED`).

**Local development is unchanged.** On a laptop (no DMI product name, or a different one) with
`casita.deployment` empty or `local`, the guard does nothing: the practice door, `casita.door=meross`,
dry runs and the opt-in live tests all work as before. That includes the iPhone preview.

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
  - Staging starts with Ford and Toyota both `SIMULATED`, and no Meross credentials, session or client beans.
  - Staging with `casita.door=meross` (even with credentials), with commands `live` or `dry-run`, with
    credentials, or with an unknown deployment: each fails to start.
  - With the guard unregistered, all seven refusals start instead; checked by hand on 2026-10-21.

## Verifying it on the GCP VM

Do this before the first real start of staging, and after any change to the unit, image or guard. It needs
no Meross account and moves nothing.

1. `cat /sys/class/dmi/id/product_name` prints `Google Compute Engine`.
2. The systemd unit sets `CASITA_DEPLOYMENT=staging` and `CASITA_DOOR=simulated`. It sets no `CASITA_MEROSS_*`
   or `MEROSS_*` variables, and there is no `config/meross.local.json` in the working directory.
3. **Negative checks.** Start each once by hand and confirm it exits with "Staging guard: Casita won't start":
   - `CASITA_DEPLOYMENT=` (unset)
   - `CASITA_DEPLOYMENT=local`
   - `CASITA_DOOR=meross`
   - `CASITA_MEROSS_COMMAND_MODE=live`
   - `CASITA_MEROSS_EMAIL=placeholder@example.com`
4. Start normally and find the `staging guard: ok …` line in `journalctl`.
5. Signed in on the staging site, `/api/doors` lists Ford and Toyota, both with `"control": "simulated"` and
   `"live": false`.

Record the date, image and commit with the results.
