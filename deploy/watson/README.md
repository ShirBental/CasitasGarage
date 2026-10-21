# deploy/watson: the GCP staging server

Everything needed to run Casita as a **simulation-only** staging server called `watson`: one e2-micro
Compute Engine VM, the project's `(default)` Firestore database, and Tailscale Serve for private HTTPS. Casita
has no public endpoint. The runbook, costs, gates and checklist are in `docs/WATSON_DEPLOYMENT_READINESS.md`.

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
| `check-guard.sh` | watson, as root | The staging guard on the VM: each unsafe override must refuse to start (the service isn't touched) |
| `deploy-release.sh` | your Mac | CI build of a clean commit, then copy and install through IAP |
| `install-release.sh` | watson (`casita-install-release`) | Checks the jar's SHA-256, switches releases, rolls back by itself if unhealthy |
| `rollback.sh` | watson (`casita-rollback`) | Back to the previous release |
| `health.sh` | watson (`casita-health`) | Service, `/healthz`, the staging guard's line, Funnel off, memory |
| `casita-access` | watson | `bin/casita-access` as the casita user, with the server's data directory |
