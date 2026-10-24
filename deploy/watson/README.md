# deploy/watson: the GCP server

Everything needed to run Casita on a server called `watson`: one e2-micro Compute Engine VM, the project's
`(default)` Firestore database, and Caddy for public HTTPS at `watsonbental.duckdns.org` (Casita itself listens on
127.0.0.1 only). The move from Tailscale to public HTTPS, step by step with rollback, is `docs/PUBLIC_HTTPS.md`.
Switching watson to the real doors (production) is in `LIVE_DOORS.md`. The original runbook, costs, gates and
checklist are in `docs/WATSON_DEPLOYMENT_READINESS.md` (written for the Tailscale setup).

Every script that changes something is a dry run unless it is given `--apply`, and each says what it would do.

| File | Where it runs | What it does |
|---|---|---|
| `watson.conf.example` | your Mac | Project, region, names. Copy to `watson.conf` (git-ignored) |
| `provision-gcp.sh` | your Mac (gcloud as owner) | APIs, the service account and its no-delete role, Firestore and its schema marker, the private network, the VM. The dry run is read-only; both modes stop on any drift from `watson.conf` before changing anything, and nothing that exists is ever changed |
| `provision_check.py` | (used by `provision-gcp.sh`) | The checks: what was read, against what watson needs |
| `test/provision-gcp.test.sh` | anywhere | `provision-gcp.sh` against a fake gcloud and curl: plans, duplicate VMs, drift, no token printed |
| `bootstrap-vm.sh` | watson, as root | Java 21, the casita user and directories, swap, journald, `casita.service` (enabled, not started) |
| `casita.env.example` | watson: `/etc/casita/casita.env` | Casita's settings. Staging refuses to start until its placeholders are filled in |
| `casita.service` | watson | The systemd unit: sandboxed, memory-capped, restarts on failure |
| `provision-public.sh` | Cloud Shell or your Mac (gcloud) | Reserves the VM's current external address in place, opens tcp:80,443 to tag `casita-https` only, tags the VM; refuses if anything opens 8080 or IAP SSH is missing |
| `duckdns-update.sh` | watson, as root | Points `<name>.duckdns.org` at the VM's own external address; the token stays in a root-only file, never on a command line; optional daily timer |
| `Caddyfile` | watson: `/etc/caddy/Caddyfile` | The public HTTPS site: certificate, `/admin` 404, client `X-Forwarded-*` replaced, secrets redacted from the access log |
| `public-https.sh` | watson, as root | Installs Caddy, renders the Caddyfile for a host and a loopback upstream, validates, reloads, waits for a trusted `https://<host>/healthz` |
| `casita-public.service`, `public-preview.sh` | watson, as root | The temporary public preview: the new release as staging (simulated doors) on 127.0.0.1:8081, same data and Firestore, for enrolling on the new name before cutover |
| `cutover-public.sh` | watson, as root | Moves `casita.service` to the new release, public name and browser keys; saves everything first; rolls back by itself if unhealthy |
| `rollback-public.sh` | watson, as root | Undoes the cutover: old settings, unit and release, Tailscale path back (until `remove-tailscale.sh`) |
| `remove-tailscale.sh` | watson, as root, over IAP | Resets Serve and Funnel, logs out, purges tailscaled; refuses unless the cutover is done, healthy, cloud transport, and the session is IAP |
| `test/caddy.test.sh` | anywhere with caddy | The Caddyfile against a real Caddy: spoofed proxy headers, `/admin`, body limit, redirect, log redaction |
| `test/public-scripts.test.sh` | anywhere | `provision-public.sh` and `duckdns-update.sh` against fakes: plans, refusals, the token never on a command line |
| `check-g3.sh` | watson | Gate G3: the service account can read Firestore but can't delete |
| `check-g6.sh` | watson | Gate G6: real Firestore latency from the VM (read-only), and Casita's own server times |
| `check-guard.sh` | watson, as root | The guard on the VM (staging or production): each unsafe override must refuse to start (the service isn't touched) |
| `door-watch.sh` | watson (`casita-door-watch`), as root | Read-only: Ford's and Toyota's readings every 2 s, to measure travel times and sensor lags while someone uses the wall button |
| `LIVE_DOORS.md` | | Production: the Meross account, both doors, the home-network route, and the supervised first tests |
| `deploy-release.sh` | your Mac or Cloud Shell | CI build of a clean commit, then copy and install through IAP (`--preview`: start it as the public preview instead) |
| `install-release.sh` | watson (`casita-install-release`) | Checks the jar's SHA-256 and that `casita.env` says staging (simulated, commands off) or production (meross, with a root-only 0600 `meross.env`), each setting exactly once; switches releases; a failed restart or health check rolls back to the earlier release, or on a first deployment stops and disables the service and removes `current` |
| `test/install-release.test.sh` | Linux | `install-release.sh` on a scratch filesystem with fake systemctl and health: success, rollback, first-deployment failure, refused settings |
| `rollback.sh` | watson (`casita-rollback`) | Back to the previous release |
| `health.sh` | watson (`casita-health`) | Service, `/healthz`, the staging or production guard's line, 8080 on loopback only, Caddy and the public `/healthz`, memory |
| `casita-access` | watson | `bin/casita-access` as the casita user, with the server's data directory |
