#!/usr/bin/env bash
# Install a Casita release on watson and switch to it, with an automatic rollback if it doesn't come up healthy.
# Run on the VM as root (installed as casita-install-release by bootstrap-vm.sh):
#
#   sudo casita-install-release <dir with casita.jar, casita-access, RELEASE> <expected sha256 of casita.jar>
#
# The release directory comes from deploy-release.sh. Releases live in /opt/casita/releases/<id>; /opt/casita/current
# points at the running one and /opt/casita/previous at the one before. Nothing in /var/lib/casita is touched.
set -euo pipefail
src="${1:?release directory}"; want="${2:?expected sha256 of casita.jar}"
[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)" >&2; exit 1; }

got="$(sha256sum "$src/casita.jar" | cut -d' ' -f1)"
[ "$got" = "$want" ] || { echo "casita.jar sha256 is $got, expected $want: not installing" >&2; exit 1; }
id="$(cut -d' ' -f1 < "$src/RELEASE")"
case "$id" in *[!0-9a-f]*|"") echo "RELEASE must start with the commit id" >&2; exit 1 ;; esac

dest="/opt/casita/releases/$id"
install -d -m 0755 "$dest" "$dest/docs"
install -m 0644 "$src/casita.jar" "$dest/casita.jar"
install -m 0755 "$src/casita-access" "$dest/casita-access"
install -m 0644 "$src/RELEASE" "$dest/RELEASE"
[ -f "$src/WATSON_DEPLOYMENT_READINESS.md" ] && install -m 0644 "$src/WATSON_DEPLOYMENT_READINESS.md" "$dest/docs/"
echo "$want  casita.jar" > "$dest/casita.jar.sha256"

old="$(readlink -f /opt/casita/current 2>/dev/null || true)"
if [ -n "$old" ] && [ "$old" != "$dest" ]; then ln -sfn "$old" /opt/casita/previous; fi
ln -sfn "$dest" /opt/casita/current.new && mv -T /opt/casita/current.new /opt/casita/current
echo "current -> $dest (previous -> ${old:-none})"

systemctl restart casita.service
if /usr/local/sbin/casita-health --wait 90; then
  echo "Release $id is up."
else
  echo "Release $id did not come up healthy." >&2
  if [ -n "$old" ] && [ "$old" != "$dest" ]; then
    echo "Rolling back to $old" >&2
    /usr/local/sbin/casita-rollback
  fi
  exit 1
fi
