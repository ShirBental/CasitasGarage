# Casita on public HTTPS, without Tailscale

From `https://watson.<tailnet>.ts.net` (Tailscale Serve, tailnet identity) to **`https://watsonbental.duckdns.org`**
(Caddy, a publicly trusted certificate, passkeys and enrolled browser keys). The access model is in `docs/ACCESS.md`.

Everything below can be done from a phone: Cloud Shell in the Google Cloud console for the `gcloud` and build steps, and
an IAP SSH session from Cloud Shell for the VM steps. Each script shows what it would do first and changes things only
with `--apply`. Nothing here sends a door command; the preview runs simulated doors only.

```
 phone (any network) ──HTTPS 443──▶ watson: Caddy ──http──▶ 127.0.0.1:8080 casita.service ──▶ Firestore, Meross cloud
                      (80: ACME + redirect)      (8081: the preview, until cutover)
 you ──gcloud compute ssh --tunnel-through-iap──▶ watson:22 (administration: casita-access, these scripts)
```

## What changes, what doesn't

| | Before | After |
|---|---|---|
| Reaching Casita | Tailscale on the phone, `watson.<tailnet>.ts.net` | any browser, `watsonbental.duckdns.org` |
| TLS | Tailscale Serve's certificate | Caddy, Let's Encrypt (auto-renewed) |
| "Which device" | tailnet node StableID via `tailscale whois` | browser key from the invite (`__Host-casita-device`) |
| Passkeys | bound to `watson.<tailnet>.ts.net` | bound to `watsonbental.duckdns.org` (**everyone enrolls again**) |
| Members, permissions, revocation | Firestore | unchanged, same Firestore |
| Administration | IAP SSH + `casita-access` | unchanged |
| Open ports | 22 from IAP only | 22 from IAP; 80 and 443 from anywhere (tag `casita-https`); 8080 never |

## Steps

### 0. Cloud Shell

```bash
gcloud config set project bentals-casita
git clone https://github.com/ShirBental/CasitasGarage.git && cd CasitasGarage   # private: `gh auth login` first
git checkout feat/public-https
cp deploy/watson/watson.conf.example deploy/watson/watson.conf
# edit watson.conf: PROJECT_ID=bentals-casita REGION=us-central1 ZONE=us-central1-a FIRESTORE_LOCATION=<as provisioned>
java -version   # needs 21 for the build
curl -fsSL -o ~/cloud-firestore-emulator-v1.22.0.jar \
  https://storage.googleapis.com/firebase-preview-drop/emulator/cloud-firestore-emulator-v1.22.0.jar
```

### 1. Check what watson actually runs (read-only)

```bash
gcloud compute ssh watson --zone us-central1-a --tunnel-through-iap --command \
  'cat /opt/casita/current/RELEASE; systemctl is-active casita; sudo grep -E "^CASITA_(DEPLOYMENT|DOOR|MEROSS_COMMANDMODE|MEROSS_TRANSPORT|SECURITY_RPID|SECURITY_DEVICEIDENTITY)=" /etc/casita/casita.env; tailscale serve status'
git merge-base --is-ancestor <commit from RELEASE> HEAD && echo "deployed code is in this branch"
```

This branch starts from `release/watson-staging-rc` at `c4ffacf`. **If the deployed commit isn't an ancestor of
`HEAD`, stop**: watson runs code this branch doesn't have, and it must be merged in first. If
`CASITA_MEROSS_TRANSPORT=local`, the doors depend on the tailnet's subnet route: keep Tailscale until that changes
(`remove-tailscale.sh` refuses anyway).

### 2. Network: static address, ports 80 and 443

```bash
deploy/watson/provision-public.sh deploy/watson/watson.conf           # read-only: what exists, what it would change
deploy/watson/provision-public.sh deploy/watson/watson.conf --apply
```

It reserves the VM's **current** external address in place (an ephemeral address is promoted, so the address stays
the same), adds `watson-net-allow-https` (tcp:80,443 from anywhere, only to tag `casita-https`) and tags the VM. It
refuses if anything already opens 8080 or if the IAP SSH rule is missing. `provision-gcp.sh` accepts exactly this rule.

### 3. Build the release and start it as the public preview

```bash
deploy/watson/deploy-release.sh deploy/watson/watson.conf --apply --preview
```

The CI build (`mvn verify` with the Firestore emulator), then the release goes to `~/casita-release-<commit>` on watson
and `public-preview.sh` starts it as `casita-public.service`: staging (simulated doors; the guard refuses anything
else), `127.0.0.1:8081`, passkeys on `watsonbental.duckdns.org`, same data directory and Firestore as
`casita.service`. The live service and Tailscale path are untouched.

### 4. DNS and Caddy (on watson)

```bash
gcloud compute ssh watson --zone us-central1-a --tunnel-through-iap
cd ~/casita-release-*/deploy        # the newest one
sudo bash duckdns-update.sh --set-token                                        # paste the token here, not in any chat
sudo bash duckdns-update.sh --host watsonbental.duckdns.org --apply             # sets it to this VM's own address
sudo bash duckdns-update.sh --host watsonbental.duckdns.org --install-timer     # re-asserts it daily and at boot
sudo bash public-https.sh --host watsonbental.duckdns.org --upstream 127.0.0.1:8081 --apply
```

The DuckDNS token is on duckdns.org when signed in. The script reads it without echo into `/etc/casita/duckdns.token`
(root, 0600) and sends it only on curl's stdin. It always sends the VM's external address from the metadata server,
never your phone's or Cloud Shell's. `public-https.sh` refuses until the name resolves to the VM, then installs Caddy,
gets the certificate and waits for `https://watsonbental.duckdns.org/healthz` to answer `ok`.

### 5. Enroll on the new name (this is what keeps you from being locked out)

```bash
casita-access-public invite shir "Shir's iPhone"
sudo cat /var/lib/casita/invites/i-*.txt     # the newest one; 15 minutes, once
```

On the iPhone, **Tailscale off, on cellular**: open the link in Safari → *Sign in* → *Set up Face ID* → sign in. You
should see the practice doors (simulated). Then invite the others the same way (`hubby`, ...). Existing passkeys for
the old name don't carry over; each person sets up a new one. Members and permissions are the same records.

### 6. Cutover

```bash
sudo bash cutover-public.sh --host watsonbental.duckdns.org            # checks; shows the settings change
sudo bash cutover-public.sh --host watsonbental.duckdns.org --apply
```

It refuses unless Caddy serves the name with a trusted certificate, the preview runs this release, and at least one
member already has an enrolled browser on the new name. It saves the old settings, unit, Caddyfile and release in
`/etc/casita/cutover/`, then: rewrites `casita.env` (public name, `device-identity=browser`, loopback), installs the
new release into `casita.service` (production, real doors, same Meross settings), points Caddy at 8080, stops the
preview. If the new service isn't healthy it puts everything back by itself. After this, sign in again on the phone
(a restart ends sessions).

### 7. Remove Tailscale (after a day or two on the new name)

```bash
gcloud compute ssh watson --zone us-central1-a --tunnel-through-iap     # must be IAP, not the tailnet
sudo bash ~/casita-release-*/deploy/remove-tailscale.sh            # checks
sudo bash ~/casita-release-*/deploy/remove-tailscale.sh --apply
```

Resets Serve and Funnel, logs the node out, purges `tailscaled` and its apt source, removes the preview. Then delete
`watson` in the Tailscale admin console if it still shows, and the old passkeys with `casita-access revoke-passkey`.

## Rollback

| When | How |
|---|---|
| Before cutover | Nothing to undo for the live service. Stop the preview: `sudo bash public-preview.sh --stop`; take the name offline: `sudo systemctl disable --now caddy`; the firewall rule can stay or be deleted (`gcloud compute firewall-rules delete watson-net-allow-https`). |
| After cutover, before step 7 | `sudo bash rollback-public.sh --apply` (add `--stop-caddy` to take the public name offline): old settings, old unit, old release, and the Tailscale path is back, since Serve was never reset. Passkeys enrolled on the new name stay in Firestore for next time. |
| After step 7 | Tailscale is gone; going back means reinstalling it (`bootstrap-vm.sh` from `release/watson-staging-rc`) and then `rollback-public.sh`. Keep step 7 until the new path has proven itself. |

`casita-rollback` (the jar only) is not a rollback across the cutover: the old release can't start with the new
settings. Use `rollback-public.sh`.

## Checks along the way

- `casita-health` on watson: the service, `/healthz`, the guard's line, port 8080 on loopback only, Caddy, and (after
  cutover) `https://watsonbental.duckdns.org/healthz` with a trusted certificate.
- From the phone without Tailscale: `https://watsonbental.duckdns.org/healthz` says `ok`; `/api/doors` without a
  session says 401; `/admin/access` says 404.
- In the repo: `mvn verify` (CI), `deploy/watson/test/caddy.test.sh` (real Caddy, proxy headers, `/admin`, log
  redaction), `deploy/watson/test/public-scripts.test.sh`, `provision-gcp.test.sh`, `install-release.test.sh`.
