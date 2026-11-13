#!/usr/bin/env bash
# The GCP side of public HTTPS for watson (docs/PUBLIC_HTTPS.md). Run where gcloud is signed in with the project
# (Cloud Shell works from a phone). Reads first; changes only what is missing.
#
#   deploy/watson/provision-public.sh deploy/watson/watson.conf            # inspect: what exists, what it would change
#   deploy/watson/provision-public.sh deploy/watson/watson.conf --apply    # make those changes
#
# What it makes sure of:
#   1. the VM's current external IPv4 address is reserved as a static address (STATIC_IP_NAME), keeping the same
#      address: an ephemeral one is promoted in place, never replaced;
#   2. one firewall rule, <NETWORK>-allow-https, admits tcp:80 and tcp:443 from anywhere to instances tagged
#      HTTPS_TAG, and only those ports (80: certificate validation and the redirect to HTTPS);
#   3. the VM carries HTTPS_TAG.
# It refuses (changes nothing) if any rule on the network already admits port 8080 or all ports from the internet, or
# if the IAP SSH rule (administration) is missing. It never touches the IAP rule, the VM's disks, OS Login or IAM.
set -euo pipefail
conf="${1:?usage: provision-public.sh <watson.conf> [--apply]}"; mode="${2:-}"
case "$mode" in ""|--apply) ;; *) echo "usage: provision-public.sh <watson.conf> [--apply]" >&2; exit 2 ;; esac
# shellcheck source=/dev/null
. "$conf"
: "${PROJECT_ID:?}" "${VM_NAME:?}" "${NETWORK:?}"
STATIC_IP_NAME="${STATIC_IP_NAME:-watson-ip}"; HTTPS_TAG="${HTTPS_TAG:-casita-https}"
HTTPS_RULE="$NETWORK-allow-https"
command -v gcloud >/dev/null || { echo "gcloud isn't installed" >&2; exit 2; }
export CLOUDSDK_CORE_DISABLE_PROMPTS=1
G=(gcloud --project="$PROJECT_ID" --quiet)

inv="$(mktemp -d)"; trap 'rm -rf "$inv"' EXIT
"${G[@]}" compute instances list --filter="name=($VM_NAME)" --format=json > "$inv/vms.json"
"${G[@]}" compute firewall-rules list --format=json > "$inv/rules.json"
"${G[@]}" compute addresses list --format=json > "$inv/addresses.json"

# The plan, from what exists: one line per finding, and the commands to run with --apply (as a NUL-free shell list).
python3 -I - "$inv" "$VM_NAME" "$NETWORK" "$STATIC_IP_NAME" "$HTTPS_TAG" "$HTTPS_RULE" > "$inv/plan" <<'PY'
import json, os, sys, shlex
inv, vm_name, network, ip_name, tag, rule_name = sys.argv[1:]
load = lambda n: json.load(open(os.path.join(inv, n + ".json"))) or []
base = lambda u: (u or "").rstrip("/").split("/")[-1]
vms = [v for v in load("vms") if v.get("name") == vm_name]
findings, cmds, problems = [], [], 0
def say(kind, text):
    global problems
    findings.append("%-8s %s" % (kind, text))
    if kind == "PROBLEM": problems += 1
if len(vms) != 1:
    say("PROBLEM", "found %d VMs named %s" % (len(vms), vm_name))
    print("\n".join(findings)); print("#PROBLEMS %d" % problems); sys.exit(0)
vm = vms[0]
zone = base(vm["zone"]); region = zone.rsplit("-", 1)[0]
nic = vm["networkInterfaces"][0]
access = (nic.get("accessConfigs") or [{}])[0]
ip, tier = access.get("natIP"), access.get("networkTier", "PREMIUM")
say("OK", "VM %s in %s, status %s, network %s" % (vm_name, zone, vm.get("status"), base(nic.get("network"))))
if base(nic.get("network")) != network:
    say("PROBLEM", "the VM is on network %s, watson.conf says %s" % (base(nic.get("network")), network))
if not ip:
    say("PROBLEM", "the VM has no external IPv4 address (public HTTPS needs one)")
else:
    owned = [a for a in load("addresses") if a.get("address") == ip]
    if owned:
        a = owned[0]
        say("OK", "external address %s is reserved (static) as %s (%s, %s)" % (ip, a.get("name"), a.get("status"), a.get("networkTier", tier)))
    else:
        say("CHANGE", "external address %s is ephemeral: reserve it in place as %s (same address)" % (ip, ip_name))
        cmds.append(["compute", "addresses", "create", ip_name, "--region=" + region, "--addresses=" + ip, "--network-tier=" + tier,
                     "--description=watson's public address (Casita, %s)" % vm_name])
rules = [r for r in load("rules") if base(r.get("network")) == network and r.get("direction", "INGRESS") == "INGRESS"
         and r.get("allowed") and not r.get("disabled")]
iap = [r for r in rules if r.get("sourceRanges") == ["35.235.240.0/20"] and
       any(a.get("IPProtocol") == "tcp" and "22" in a.get("ports", []) for a in r["allowed"])]
say("OK" if iap else "PROBLEM", "IAP SSH (administration): %s" % (", ".join(r["name"] for r in iap) or "no rule admits tcp:22 from 35.235.240.0/20"))
def opens(r, port):
    for a in r["allowed"]:
        if a.get("IPProtocol") not in ("tcp", "all"): continue
        ports = a.get("ports")
        if not ports: return True
        for p in ports:
            lo, _, hi = p.partition("-")
            if int(lo) <= port <= int(hi or lo): return True
    return False
for r in rules:
    public = "0.0.0.0/0" in r.get("sourceRanges", [])
    if public and opens(r, 8080):
        say("PROBLEM", "rule %s admits port 8080 (or every port) from the internet: Casita's own port must stay closed" % r["name"])
existing = [r for r in load("rules") if r.get("name") == rule_name]
want_allowed = ["tcp:443", "tcp:80"]
if existing:
    r = existing[0]
    allowed = sorted("%s:%s" % (a.get("IPProtocol"), ",".join(a.get("ports", ["all"]))) for a in r.get("allowed", []))
    exact = (base(r.get("network")) == network and r.get("sourceRanges") == ["0.0.0.0/0"] and allowed == want_allowed
             and r.get("targetTags") == [tag] and not r.get("disabled"))
    say("OK" if exact else "PROBLEM", "firewall %s: %s" % (rule_name, "tcp:80,443 to tag %s only" % tag if exact
        else "exists but isn't exactly tcp:80,443 from anywhere to tag %s (fix or delete it deliberately)" % tag))
else:
    say("CHANGE", "firewall %s: admit tcp:80,443 from anywhere to instances tagged %s" % (rule_name, tag))
    cmds.append(["compute", "firewall-rules", "create", rule_name, "--network=" + network, "--direction=INGRESS", "--action=allow",
                 "--rules=tcp:80,tcp:443", "--source-ranges=0.0.0.0/0", "--target-tags=" + tag,
                 "--description=Public HTTPS for Casita (Caddy on watson): 443, and 80 for ACME and the redirect"])
tags = (vm.get("tags") or {}).get("items", [])
if tag in tags:
    say("OK", "VM tags: %s" % ", ".join(tags))
else:
    say("CHANGE", "add tag %s to %s (tags now: %s)" % (tag, vm_name, ", ".join(tags) or "none"))
    cmds.append(["compute", "instances", "add-tags", vm_name, "--zone=" + zone, "--tags=" + tag])
print("\n".join(findings))
print("#PROBLEMS %d" % problems)
for c in cmds: print("#CMD " + " ".join(shlex.quote(x) for x in c))
print("#IP %s" % (ip or ""))
PY

grep -v '^#' "$inv/plan"
problems="$(sed -n 's/^#PROBLEMS //p' "$inv/plan")"
ip="$(sed -n 's/^#IP //p' "$inv/plan")"
mapfile -t cmds < <(sed -n 's/^#CMD //p' "$inv/plan")
echo
if [ "$problems" != 0 ]; then echo "STOPPED: $problems problem(s) above; nothing was changed."; exit 1; fi
if [ ${#cmds[@]} -eq 0 ]; then echo "Nothing to change. Next: point DNS at $ip (duckdns-update.sh on the VM)."; exit 0; fi
for c in "${cmds[@]}"; do echo "  \$ gcloud --project=$PROJECT_ID $c"; done
if [ "$mode" != --apply ]; then echo "DRY RUN: nothing changed. Add --apply."; exit 0; fi
for c in "${cmds[@]}"; do
  eval "set -- $c"
  "${G[@]}" "$@"
done
echo "Done. Run it again without --apply: every line should be OK. Next: point DNS at $ip (duckdns-update.sh on the VM)."
