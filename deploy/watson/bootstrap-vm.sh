#!/usr/bin/env bash
# Prepare watson (Debian 12 on an e2-micro) to run Casita. Run on the VM as root, once; re-running is harmless.
#
#   sudo bash bootstrap-vm.sh
#
# Installs Java 21 (Eclipse Temurin JRE) and Tailscale from their signed apt repositories; creates the casita
# user, /opt/casita (releases), /var/lib/casita (data) and /etc/casita (settings); adds a 1 GiB swap file and
# journald limits; installs casita.service (not started) and the casita-access helper.
# It does NOT join the tailnet, start Tailscale Serve or start Casita: those are separate, deliberate steps.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)" >&2; exit 1; }
. /etc/os-release
[ "$VERSION_CODENAME" = "bookworm" ] || { echo "expected Debian 12 (bookworm), found $PRETTY_NAME" >&2; exit 1; }

echo "== packages: updates, unattended security upgrades, tools"
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get -y upgrade
DEBIAN_FRONTEND=noninteractive apt-get -y install ca-certificates curl gnupg jq unattended-upgrades

echo "== Java 21: Eclipse Temurin JRE from Adoptium's apt repository"
install -d -m 0755 /etc/apt/keyrings
curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor --yes -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" \
  > /etc/apt/sources.list.d/adoptium.list

echo "== Tailscale from its apt repository"
curl -fsSL https://pkgs.tailscale.com/stable/debian/bookworm.noarmor.gpg -o /usr/share/keyrings/tailscale-archive-keyring.gpg
curl -fsSL https://pkgs.tailscale.com/stable/debian/bookworm.tailscale-keyring.list -o /etc/apt/sources.list.d/tailscale.list

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get -y install temurin-21-jre tailscale
java -version 2>&1 | head -1
tailscale version | head -1

echo "== the casita user and its directories"
id casita >/dev/null 2>&1 || useradd --system --home-dir /var/lib/casita --shell /usr/sbin/nologin casita
install -d -o root -g root -m 0755 /opt/casita /opt/casita/releases
install -d -o casita -g casita -m 0700 /var/lib/casita
install -d -o root -g casita -m 0750 /etc/casita
if [ ! -e /etc/casita/casita.env ]; then
  install -o root -g casita -m 0640 "$here/casita.env.example" /etc/casita/casita.env
  echo "   /etc/casita/casita.env installed from the template: fill in its <...> before starting Casita"
fi

echo "== 1 GiB swap (an e2-micro has 1 GiB of memory; swap is a safety margin, not working memory)"
if [ ! -e /swapfile ]; then
  fallocate -l 1G /swapfile && chmod 600 /swapfile && mkswap /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
swapon --show | grep -q /swapfile || swapon /swapfile
echo 'vm.swappiness=10' > /etc/sysctl.d/90-casita.conf && sysctl -q --system

echo "== journald: keep logs, bounded (journalctl -u casita)"
install -d /etc/systemd/journald.conf.d
printf '[Journal]\nStorage=persistent\nSystemMaxUse=200M\n' > /etc/systemd/journald.conf.d/casita.conf
systemctl restart systemd-journald

echo "== casita.service (installed and enabled, not started) and the casita-access helper"
install -m 0644 "$here/casita.service" /etc/systemd/system/casita.service
install -m 0755 "$here/casita-access" /usr/local/bin/casita-access
install -m 0755 "$here/install-release.sh" /usr/local/sbin/casita-install-release
install -m 0755 "$here/rollback.sh" /usr/local/sbin/casita-rollback
install -m 0755 "$here/health.sh" /usr/local/sbin/casita-health
systemctl daemon-reload
systemctl enable casita.service

echo
echo "Bootstrap done. Next, deliberately:"
echo "  1. sudo tailscale up --hostname=$(hostname -s)    # sign in; then in the admin console disable key expiry for this node"
echo "  2. fill in /etc/casita/casita.env (tailnet name, family, project id)"
echo "  3. install a release: casita-install-release <casita.jar> <sha256>"
echo "  4. bash tailscale-serve.sh --apply"
