# deploy/watson: the GCP server

Everything needed to run Casita as a **simulation-only** staging server called `watson`: one e2-micro
Compute Engine VM, the project's `(default)` Firestore database, and Tailscale Serve for private HTTPS. Casita
has no public endpoint. Switching watson to the real doors (production) is in `LIVE_DOORS.md`. The runbook, costs, gates and checklist are in `docs/WATSON_DEPLOYMENT_READINESS.md`.

Every script that changes something is a dry run unless it is given `--apply`, and each says what it would do.

| File | Where it runs | What it does |
|---|---|---|
| `watson.conf.example` | your Mac | Project, region, names. Copy to `watson.conf` (git-ignored) |
| `provision-gcp.sh` | your Mac (gcloud as owner) | APIs, the service account and its no-delete role, Firestore and its schema marker, the private network, the VM. The dry run is read-only; both modes stop on any drift from `watson.conf` before changing anything, and nothing that exists is ever changed |
| `provision_check.py` | (used by `provision-gcp.sh`) | The checks: what was read, against what watson needs |
| `test/provision-gcp.test.sh` | anywhere | `provision-gcp.sh` against a fake gcloud and curl: plans, duplicate VMs, drift, no token printed |
| `bootstrap-vm.sh` | watson, as root | Java 21, Tailscale, the casita user and directories, swap, journald, `casita.service` (enabled, not started) |
| `casita.env.example` | watson: `/etc/casita/casita.env` | Casita's settings. Staging refuses to start until its placeholders are filled in |
| `casita.service` | watson | The systemd unit: sandboxed, memory-capped, restarts on failure |
| `tailscale-serve.sh` | watson, as root | `https://watson.<tailnet>.ts.net` → `http://127.0.0.1:8080`, after checking the names agree |
| `check-g3.sh` | watson | Gate G3: the service account can read Firestore but can't delete |
| `check-g6.sh` | watson | Gate G6: real Firestore latency from the VM (read-only), and Casita's own server times |
| `check-guard.sh` | watson, as root | The guard on the VM (staging or production): each unsafe override must refuse to start (the service isn't touched) |
| `door-watch.sh` | watson (`casita-door-watch`), as root | Read-only: Ford's and Toyota's readings every 2 s, to measure travel times and sensor lags while someone uses the wall button |
| `LIVE_DOORS.md` | | Production: the Meross account, both doors, the home-network route, and the supervised first tests |
| `deploy-release.sh` | your Mac | CI build of a clean commit, then copy and install through IAP |
| `install-release.sh` | watson (`casita-install-release`) | Checks the jar's SHA-256 and that `casita.env` says staging (simulated, commands off) or production (meross, with a root-only 0600 `meross.env`), each setting exactly once; switches releases; a failed restart or health check rolls back to the earlier release, or on a first deployment stops and disables the service and removes `current` |
| `test/install-release.test.sh` | Linux | `install-release.sh` on a scratch filesystem with fake systemctl and health: success, rollback, first-deployment failure, refused settings |
| `rollback.sh` | watson (`casita-rollback`) | Back to the previous release |
| `health.sh` | watson (`casita-health`) | Service, `/healthz`, the staging or production guard's line, Funnel off, memory |
| `casita-access` | watson | `bin/casita-access` as the casita user, with the server's data directory |
