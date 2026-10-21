# Ford and Toyota for real: watson in production

Production is watson running `casita.deployment=production`: Ford on channel 1 and Toyota on channel 2 of the
MSG200 (channel 3 is permanently disabled). Each door has its own GarageService: its own reading, movement, timer,
hold and activity. The rules that keep a door safe are the same for both:

- Read first; send only toward the opposite of a settled reading (never a "press" that could reverse a door).
- One movement per door at a time; repeated taps and parallel requests send one command.
- A command is sent once and never retried. If its outcome is unknown, the door shows Unknown
  (`UNCONFIRMED`) and is checked by reading after `travel + sensor lag + grace`.
- A door that didn't get there is held (`STUCK`/`LOST`) until someone resumes it; the other door is unaffected.
- `live` needs both `travel-times.<door>` and `sensor-lags.<door>`, measured on that door.

Every step below changes watson or the doors, so each one waits for explicit approval.

## 1. Release

From the Mac, a commit with this branch merged into the release branch:

```bash
deploy/watson/deploy-release.sh deploy/watson/watson.conf            # build (CI's mvn verify) and show the plan
deploy/watson/deploy-release.sh deploy/watson/watson.conf --apply    # copy through IAP and install (still staging)
```

Then copy the updated unit and scripts, and install them (the service keeps running as staging):

```bash
gcloud --project=bentals-casita compute scp --zone=us-central1-a --tunnel-through-iap \
  deploy/watson/{casita.service,health.sh,install-release.sh,check-guard.sh,door-watch.sh} watson:watson-setup/
gcloud --project=bentals-casita compute ssh watson --zone=us-central1-a --tunnel-through-iap --command='
  cd ~/watson-setup &&
  sudo install -m 0644 casita.service /etc/systemd/system/casita.service &&
  sudo install -m 0755 health.sh /usr/local/sbin/casita-health &&
  sudo install -m 0755 install-release.sh /usr/local/sbin/casita-install-release &&
  sudo install -m 0755 door-watch.sh /usr/local/sbin/casita-door-watch &&
  sudo systemctl daemon-reload'
```

## 2. The Meross account (root-only)

On watson (`gcloud compute ssh watson --zone=us-central1-a --tunnel-through-iap`). The password is typed, never
put on a command line or in shell history:

```bash
sudo install -m 0600 -o root -g root /dev/null /etc/casita/meross.env
read -r -p 'Meross email: ' e; read -r -s -p 'Meross password: ' p; echo
printf 'CASITA_MEROSS_EMAIL=%s\nCASITA_MEROSS_PASSWORD=%s\n' "$e" "$p" | sudo tee /etc/casita/meross.env >/dev/null
unset e p
sudo stat -c '%U %a' /etc/casita/meross.env      # root 600
```

## 3. casita.env: production, commands off

Edit `/etc/casita/casita.env` (`sudo nano /etc/casita/casita.env`), changing these lines and adding the rest:

```
CASITA_DEPLOYMENT=production
CASITA_DOOR=meross
CASITA_MEROSS_COMMANDMODE=off
CASITA_MEROSS_COMMANDCHANNELS=1,2
CASITA_MEROSS_TRANSPORT=cloud
```

Sign-in, Firestore, passkeys and devices are unchanged (same rp-id, same database). Then:

```bash
sudo bash ~/watson-setup/check-guard.sh      # Guard on watson (production): PASS; the service isn't touched
sudo systemctl restart casita && casita-health --wait 90
journalctl -u casita -n 50 --no-pager | grep -E 'production guard|meross event'
```

`casita-health` must show `production guard: ok ... door=meross command_mode=off command_channels=[1, 2]`.
Both doors now show their real state in the app (Closed, Open, or Unknown); every command is refused.

## 4. Measure each door (read-only)

```bash
sudo casita-door-watch 10 1     # 10 minutes, a reading every second; nothing is sent
```

While it runs, someone at the garage opens and closes each door with the wall button or remote, twice each,
noting the time the door starts and stops. For each door:

- **travel**: from the button press to the door stopping (Ford is about 20 s);
- **sensor lag**: from the door stopping to the line where its reading changes (`<- Ford changed`). Take the
  longest seen, rounded up (Toyota's is 25 s or more).

Add them to `casita.env` (whole seconds, a little above the longest measured):

```
CASITA_GARAGE_TRAVELTIMES_FORD=22s
CASITA_GARAGE_SENSORLAGS_FORD=<measured>s
CASITA_GARAGE_TRAVELTIMES_TOYOTA=<measured>s
CASITA_GARAGE_SENSORLAGS_TOYOTA=<measured, 25s or more>s
```

## 5. Dry run

`CASITA_MEROSS_COMMANDMODE=dry-run`, restart, `casita-health`. Tap Open on each door in the app: the activity says
"Dry run: ... checked and built ... but not sent", for channel 1 from Ford's card and channel 2 from Toyota's.
Nothing moves.

## 6. Live, one door at a time, someone at the garage

Start with Ford only: `CASITA_MEROSS_COMMANDCHANNELS=1`, `CASITA_MEROSS_COMMANDMODE=live`, restart,
`casita-health`. With a person watching the door and the doorway clear:

1. Ford Closed → tap Open: Opening… then Open after travel + lag + 2 s.
2. Ford Open → tap Close: Closing… then Closed.
3. Tap Open twice quickly: one command (the activity shows one).

Then `CASITA_MEROSS_COMMANDCHANNELS=1,2` and the same three for Toyota (expect Closed only after its long lag).
Last, open both at once: each finishes on its own timer.

To stop all commands at any time: `CASITA_MEROSS_COMMANDMODE=off` and `sudo systemctl restart casita`.

## The home-network path (optional)

The cloud path (`CASITA_MEROSS_TRANSPORT=cloud`) needs nothing at home and is the one proven on Ford. The local
path talks to the MSG200 straight over the home network, `POST http://<its address>/config`, signed with the account
key, and keeps working if Meross's MQTT broker is slow. watson reaches it only through Tailscale:

1. Router: a DHCP reservation for the MSG200 (e.g. 192.168.1.50).
2. An always-on Tailscale device at home (Apple TV, Raspberry Pi, Linux box) advertises only that address:
   `sudo tailscale set --advertise-routes=192.168.1.50/32`, then approve the route in the admin console.
3. The tailnet policy lets watson reach only port 80 there:
   `"grants": [{"src": ["<watson>"], "dst": ["192.168.1.50/32"], "ip": ["tcp:80"]}]`.
4. watson: `sudo tailscale set --accept-routes`.
5. Check read-only: `CASITA_MEROSS_TRANSPORT=local CASITA_MEROSS_LOCALURL=http://192.168.1.50` added to
   `/etc/casita/casita.env`, then `sudo casita-door-watch 1 2`: both doors read, with times in milliseconds.
   If the opener doesn't answer, remove the two lines and stay on cloud.
