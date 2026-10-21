#!/usr/bin/env bash
# Switch watson back to the previous Casita release and restart. Run on the VM as root (casita-rollback).
# Only the jar changes: the data in /var/lib/casita and Firestore stay as they are (the schema is unchanged
# between releases of this candidate; a release that changes meta/schema needs its own rollback plan).
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "run as root (sudo)" >&2; exit 1; }
prev="$(readlink -f /opt/casita/previous 2>/dev/null || true)"
[ -n "$prev" ] && [ -f "$prev/casita.jar" ] || { echo "no previous release to roll back to" >&2; exit 1; }
cur="$(readlink -f /opt/casita/current)"
ln -sfn "$prev" /opt/casita/current.new && mv -T /opt/casita/current.new /opt/casita/current
ln -sfn "$cur" /opt/casita/previous
echo "current -> $prev (previous -> $cur)"
systemctl reset-failed casita.service || true
systemctl restart casita.service
/usr/local/sbin/casita-health --wait 90
