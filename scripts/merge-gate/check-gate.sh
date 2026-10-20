#!/usr/bin/env bash
# Go/no-go checks for merging one PR of the Casita stack (docs/MERGE_READINESS.md, "Merge gates").
# Read-only: it fetches, compares trees and greps; it never pushes, merges or talks to Meross.
#
#   scripts/merge-gate/check-gate.sh <candidate-ref> <validated-ref> [allowed-extra-path ...]
#
# candidate-ref: what main will be after the merge (e.g. origin/main once merged, or the PR's merge ref
#                fetched as refs/pull/N/merge). validated-ref: the PR head that was reviewed and tested.
# allowed-extra-path: files that may differ between them (docs/DESIGN.md when #1 merged first).
set -u
cand=${1:?candidate ref}; valid=${2:?validated ref}; shift 2
fail=0
no() { echo "NO-GO: $*"; fail=1; }
ok() { echo "ok:    $*"; }

git fetch -q origin '+refs/heads/*:refs/remotes/origin/*' || no "fetch failed"

# 1. The result is the tree that was validated, apart from the allowed files.
extra=$(git diff --name-only "$valid" "$cand" -- . $(printf ":(exclude)%s " "$@"))
[ -z "$extra" ] && ok "tree matches $valid (allowed extras: ${*:-none})" || no "tree differs from $valid in: $extra"

# 2. Hardware stays simulated and off by default.
yml=$(git show "$cand:src/main/resources/application.yml" 2>/dev/null)
if grep -qE '^  door:' <<<"$yml"; then   # absent before #3
  grep -qE '^  door: simulated$' <<<"$yml" && ok "casita.door: simulated" || no "casita.door is not 'simulated'"
fi
if grep -q 'command-mode' <<<"$yml"; then
  grep -qE '^    command-mode: "off"' <<<"$yml" && ok "command-mode: \"off\"" || no "command-mode is not \"off\""
  grep -qE '^    command-channels: \[1\]$' <<<"$yml" && ok "command-channels: [1]" || no "command-channels is not [1]"
fi
for f in config/meross.local.json .env data/security.json data/admin.secret; do
  git cat-file -e "$cand:$f" 2>/dev/null && no "$f is committed"
done
git grep -qE 'AKIA[0-9A-Z]{16}|BEGIN [A-Z ]*PRIVATE KEY|"private_key"' "$cand" -- . ':(exclude)scripts/merge-gate' \
  && no "credential-shaped string in tree" || ok "no credential-shaped strings"

exit $fail
