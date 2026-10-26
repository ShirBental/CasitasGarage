#!/usr/bin/env python3
"""Checks what provision-gcp.sh found in the project against what watson needs. Read-only, standard library only.

    python3 -I provision_check.py <inventory dir>

The inventory directory holds what provision-gcp.sh read (NAME.json, NAME.absent, NAME.error, services.txt, ...), and
the settings come from the environment (WATSON_* variables, set by provision-gcp.sh from watson.conf). Prints one line
per finding:

    OK       a resource exists and matches
    CREATE   a resource doesn't exist yet: --apply creates it
    PROBLEM  a resource exists but doesn't match, or couldn't be read: --apply refuses to change anything
    NOTE     worth knowing, changes nothing

and writes <inventory dir>/state with name=present|absent|unknown for each resource. Exit status: 1 if any PROBLEM.
"""
import json
import os
import sys

inv = sys.argv[1]
C = {k[len("WATSON_"):].lower(): v for k, v in os.environ.items() if k.startswith("WATSON_")}
PERMS = sorted(C["perms"].split(","))
IAP_RANGE = "35.235.240.0/20"
FW_RULE = C["network"] + "-allow-iap-ssh"
# Public HTTPS (docs/PUBLIC_HTTPS.md, created by provision-public.sh, not here): tcp:80 and tcp:443 from anywhere, only
# to instances with the HTTPS tag. Port 80 is for certificate validation and the redirect to HTTPS; nothing else is open.
HTTPS_FW_RULE = C["network"] + "-allow-https"
HTTPS_TAG = C.get("https_tag") or "casita-https"
ROLE_NAME = "projects/%s/roles/%s" % (C["project_id"], C["role_id"])
SA_MEMBER = "serviceAccount:" + C["sa_email"]

lines, state = [], {}


def out(kind, text):
    lines.append("%-8s %s" % (kind, text))


def base(url):
    return (url or "").rstrip("/").split("/")[-1]


def read(name):
    """(status, data): 'present' with parsed JSON, 'absent', 'error' with the error's first line, or 'unchecked'."""
    p = os.path.join(inv, name)
    if os.path.exists(p + ".json"):
        with open(p + ".json") as f:
            text = f.read().strip()
        return "present", (json.loads(text) if text else None)
    if os.path.exists(p + ".absent"):
        return "absent", None
    if os.path.exists(p + ".error"):
        with open(p + ".error") as f:
            first = next((l.strip() for l in f if l.strip()), "unknown error")
        return "error", first[:300]
    return "unchecked", None


def services():
    p = os.path.join(inv, "services.txt")
    if not os.path.exists(p):
        return None
    with open(p) as f:
        return {l.strip() for l in f if l.strip()}


enabled = services()
if enabled is None:
    out("PROBLEM", "Couldn't list the project's enabled APIs, so nothing could be checked.")
    state.update(dict.fromkeys(["sa", "role", "binding", "firestore", "schema", "rules", "network", "subnet", "firewall", "vm"], "unknown"))
else:
    missing = [a for a in C["apis"].split(",") if a not in enabled]
    for a in C["apis"].split(","):
        out("OK" if a in enabled else "CREATE", "API %s %s" % (a, "enabled" if a in enabled else "not enabled: --apply enables it"))
    state["apis_missing"] = ",".join(missing)


def api(name):
    return enabled is not None and name in enabled


def unreadable(key, label, status, data):
    if status == "error":
        out("PROBLEM", "Couldn't read %s: %s" % (label, data))
        state[key] = "unknown"
        return True
    return False


# ---------- service account ----------
if api("iam.googleapis.com"):
    s, sa = read("sa")
    if not unreadable("sa", "the service account", s, sa):
        if s == "absent":
            out("CREATE", "Service account %s" % C["sa_email"])
            state["sa"] = "absent"
        else:
            state["sa"] = "present"
            if sa.get("email") != C["sa_email"]:
                out("PROBLEM", "Service account answers as %s, expected %s." % (sa.get("email"), C["sa_email"]))
            elif sa.get("disabled"):
                out("PROBLEM", "Service account %s is disabled." % C["sa_email"])
            else:
                out("OK", "Service account %s" % C["sa_email"])
            ks, keys = read("sa-keys")
            if not unreadable("sa-keys", "the service account's keys", ks, keys):
                user_keys = [k for k in (keys or []) if k.get("keyType", "USER_MANAGED") == "USER_MANAGED"]
                if user_keys:
                    out("PROBLEM", "Service account %s has %d user-managed key(s); none are needed or allowed. "
                        "Delete them deliberately." % (C["sa_email"], len(user_keys)))
                elif ks == "present":
                    out("OK", "No user-managed keys on %s" % C["sa_email"])
            ps_, pol = read("sa-policy")
            if not unreadable("sa-policy", "who may use the service account", ps_, pol):
                for b in (pol or {}).get("bindings", []):
                    public = [m for m in b.get("members", []) if m in ("allUsers", "allAuthenticatedUsers")]
                    if public:
                        out("PROBLEM", "%s on the service account is granted to %s." % (b.get("role"), ", ".join(public)))
                    else:
                        out("NOTE", "On the service account itself: %s for %s" % (b.get("role"), ", ".join(b.get("members", []))))

    # ---------- custom role ----------
    s, role = read("role")
    if not unreadable("role", "the custom role", s, role):
        if s == "absent":
            out("CREATE", "Custom role %s with exactly: %s" % (ROLE_NAME, ", ".join(PERMS)))
            state["role"] = "absent"
        else:
            state["role"] = "present"
            got = sorted(role.get("includedPermissions", []))
            if role.get("deleted"):
                out("PROBLEM", "Custom role %s is deleted (GCP keeps it for a while). Undelete or replace it deliberately; "
                    "this script won't." % ROLE_NAME)
            elif role.get("stage") == "DISABLED":
                out("PROBLEM", "Custom role %s is disabled." % ROLE_NAME)
            elif got != PERMS:
                extra = sorted(set(got) - set(PERMS))
                lacking = sorted(set(PERMS) - set(got))
                out("PROBLEM", "Custom role %s has different permissions. Extra: %s. Missing: %s. Fix it deliberately; "
                    "this script won't change a role." % (ROLE_NAME, ", ".join(extra) or "none", ", ".join(lacking) or "none"))
            else:
                out("OK", "Custom role %s: exactly the %d intended permissions, no delete" % (ROLE_NAME, len(PERMS)))
else:
    for key, what in (("sa", "Service account %s" % C["sa_email"]), ("role", "Custom role %s" % ROLE_NAME)):
        out("CREATE", "%s (IAM API not enabled: checked again after --apply enables it)" % what)
        state[key] = "absent"

# ---------- project IAM ----------
if api("cloudresourcemanager.googleapis.com"):
    s, pol = read("project-policy")
    if not unreadable("binding", "the project's IAM policy", s, pol):
        roles, conditional = set(), set()
        for b in (pol or {}).get("bindings", []):
            members = b.get("members", [])
            public = [m for m in members if m in ("allUsers", "allAuthenticatedUsers")]
            if public:
                out("PROBLEM", "The project grants %s to %s." % (b.get("role"), ", ".join(public)))
            if SA_MEMBER in members:
                (conditional if b.get("condition") else roles).add(b.get("role"))
        others = sorted((roles | conditional) - {ROLE_NAME})
        if others:
            out("PROBLEM", "%s also holds %s at project level. Its only role must be %s (a broader role could "
                "allow deletes). Remove them deliberately." % (C["sa_email"], ", ".join(others), ROLE_NAME))
        if ROLE_NAME in conditional:
            out("PROBLEM", "%s holds %s only under a condition; expected an unconditional binding." % (C["sa_email"], ROLE_NAME))
        if ROLE_NAME in roles:
            out("OK", "Project binding: %s -> %s (and nothing else)" % (C["sa_email"], ROLE_NAME) if not others else
                "Project binding: %s -> %s" % (C["sa_email"], ROLE_NAME))
            state["binding"] = "present"
        else:
            out("CREATE", "Project binding: %s -> %s" % (C["sa_email"], ROLE_NAME))
            state["binding"] = "absent"
        default_sa = [b.get("role") for b in (pol or {}).get("bindings", [])
                      if any(m.endswith("-compute@developer.gserviceaccount.com") for m in b.get("members", []))]
        if default_sa:
            out("NOTE", "The Compute Engine default service account holds %s; watson doesn't use it." % ", ".join(sorted(default_sa)))
        out("NOTE", "Folder and organization IAM, if any, isn't visible here: gate G3 (check-g3.sh on the VM) is the proof.")
else:
    out("CREATE", "Project binding: %s -> %s (Resource Manager API not enabled: checked after --apply)" % (C["sa_email"], ROLE_NAME))
    state["binding"] = "absent"

# ---------- Firestore ----------
if api("firestore.googleapis.com"):
    s, db = read("firestore")
    if not unreadable("firestore", "the Firestore database", s, db):
        if s == "absent":
            out("CREATE", "Firestore (default): Native mode, %s, delete protection on, point-in-time recovery on" % C["firestore_location"])
            state["firestore"] = "absent"
            state["schema"] = "absent"
        else:
            state["firestore"] = "present"
            bad = []
            if db.get("locationId") != C["firestore_location"]:
                bad.append("location %s, expected %s (it can't be changed)" % (db.get("locationId"), C["firestore_location"]))
            if db.get("type") != "FIRESTORE_NATIVE":
                bad.append("mode %s, expected FIRESTORE_NATIVE" % db.get("type"))
            if db.get("deleteProtectionState") != "DELETE_PROTECTION_ENABLED":
                bad.append("delete protection %s, expected DELETE_PROTECTION_ENABLED" % db.get("deleteProtectionState"))
            if db.get("pointInTimeRecoveryEnablement") != "POINT_IN_TIME_RECOVERY_ENABLED":
                bad.append("point-in-time recovery %s, expected POINT_IN_TIME_RECOVERY_ENABLED (enable it deliberately: gcloud "
                           "firestore databases update --database='(default)' --enable-pitr; billed, docs/FIRESTORE.md)"
                           % (db.get("pointInTimeRecoveryEnablement") or "unset"))
            if bad:
                out("PROBLEM", "Firestore (default): " + "; ".join(bad) + ".")
            else:
                out("OK", "Firestore (default): Native mode, %s, delete protection on, point-in-time recovery on" % C["firestore_location"])
            ss, doc = read("schema")
            if ss == "present":
                version = ((doc or {}).get("fields", {}).get("schemaVersion", {}) or {}).get("integerValue")
                if version == "1":
                    out("OK", "Schema marker meta/schema: schemaVersion 1")
                    state["schema"] = "present"
                else:
                    out("PROBLEM", "meta/schema exists with schemaVersion %s, expected 1. This script won't overwrite it." % version)
                    state["schema"] = "unknown"
            elif ss == "absent":
                out("CREATE", "Schema marker meta/schema {schemaVersion: 1} (create-only: never overwrites)")
                state["schema"] = "absent"
            elif ss == "error":
                out("PROBLEM", "Couldn't read meta/schema: %s" % doc)
                state["schema"] = "unknown"
            else:
                out("NOTE", "meta/schema not checked (no access token): --apply checks it before writing")
                state["schema"] = "unknown"
else:
    out("CREATE", "Firestore (default): Native mode, %s, delete protection on, point-in-time recovery on (Firestore API not enabled: "
        "checked after --apply)"
        % C["firestore_location"])
    out("CREATE", "Schema marker meta/schema {schemaVersion: 1} (create-only)")
    state["firestore"] = state["schema"] = "absent"

# ---------- Firestore Security Rules ----------
def normalized(text):
    """Rules text without comments and with whitespace collapsed: a reformatted file compares equal, a changed rule doesn't."""
    kept = [l.split("//", 1)[0] for l in (text or "").splitlines()]
    return " ".join(" ".join(kept).split())


with open(C["rules_file"]) as f:
    WANT_RULES = normalized(f.read())

if api("firebaserules.googleapis.com"):
    s, release = read("rules-release")
    if not unreadable("rules", "the Firestore rules release", s, release):
        if s == "absent":
            out("CREATE", "Firestore Security Rules: deny all (firestore.rules), released as cloud.firestore (create-only)")
            state["rules"] = "absent"
        elif s == "unchecked":
            out("NOTE", "Firestore Security Rules not checked (no access token): --apply checks them before writing")
            state["rules"] = "unknown"
        else:
            rs, ruleset = read("rules-ruleset")
            if not unreadable("rules", "the released Firestore ruleset", rs, ruleset):
                files = ((ruleset or {}).get("source") or {}).get("files") or []
                got = normalized("\n".join(f.get("content", "") for f in files))
                if rs == "present" and got == WANT_RULES:
                    out("OK", "Firestore Security Rules: deny all, as firestore.rules")
                    state["rules"] = "present"
                else:
                    out("PROBLEM", "Firestore Security Rules released as cloud.firestore (%s) differ from firestore.rules. This script "
                        "won't replace them: review them in the console, then release firestore.rules deliberately."
                        % (release or {}).get("rulesetName", "?"))
                    state["rules"] = "unknown"
else:
    out("CREATE", "Firestore Security Rules: deny all (firestore.rules) (Firebase Rules API not enabled: checked after --apply)")
    state["rules"] = "absent"

# ---------- network, firewall, VM ----------
if api("compute.googleapis.com"):
    s, net = read("network")
    if not unreadable("network", "network " + C["network"], s, net):
        if s == "absent":
            out("CREATE", "Network %s (custom subnets)" % C["network"])
            state["network"] = "absent"
        else:
            state["network"] = "present"
            if net.get("autoCreateSubnetworks") or "IPv4Range" in net:
                out("PROBLEM", "Network %s isn't a custom-mode VPC (auto subnets or legacy)." % C["network"])
            else:
                out("OK", "Network %s (custom subnets)" % C["network"])

    s, sub = read("subnet")
    if not unreadable("subnet", "subnet " + C["subnet"], s, sub):
        if s == "absent":
            out("CREATE", "Subnet %s %s in %s on %s" % (C["subnet"], C["subnet_range"], C["region"], C["network"]))
            state["subnet"] = "absent"
        else:
            state["subnet"] = "present"
            bad = []
            if base(sub.get("network")) != C["network"]:
                bad.append("on network %s, expected %s" % (base(sub.get("network")), C["network"]))
            if sub.get("ipCidrRange") != C["subnet_range"]:
                bad.append("range %s, expected %s" % (sub.get("ipCidrRange"), C["subnet_range"]))
            if base(sub.get("region")) != C["region"]:
                bad.append("region %s, expected %s" % (base(sub.get("region")), C["region"]))
            if bad:
                out("PROBLEM", "Subnet %s: %s." % (C["subnet"], "; ".join(bad)))
            else:
                out("OK", "Subnet %s %s in %s on %s" % (C["subnet"], C["subnet_range"], C["region"], C["network"]))
                if not sub.get("privateIpGoogleAccess"):
                    out("NOTE", "Subnet %s has Private Google Access off (not needed while the VM has an external address)." % C["subnet"])

    s, rules = read("firewalls")
    if not unreadable("firewall", "firewall rules", s, rules):
        rules = rules or []
        state["firewall"] = "absent"
        for r in rules:
            net_of = base(r.get("network"))
            if r.get("name") == FW_RULE and net_of != C["network"]:
                out("PROBLEM", "Firewall rule %s exists on network %s, not %s." % (FW_RULE, net_of, C["network"]))
                state["firewall"] = "unknown"
            if net_of != C["network"]:
                if (net_of == "default" and r.get("direction") == "INGRESS" and "0.0.0.0/0" in r.get("sourceRanges", [])
                        and r.get("allowed") and not r.get("disabled")):
                    out("NOTE", "Network default: %s admits %s from anywhere (watson isn't on default)."
                        % (r.get("name"), ",".join("%s:%s" % (a.get("IPProtocol"), "/".join(a.get("ports", ["all"])))
                                                   for a in r.get("allowed", []))))
                continue
            if r.get("direction", "INGRESS") != "INGRESS" or not r.get("allowed"):
                continue  # egress, or a deny rule: can't open anything
            if r.get("disabled"):
                out("NOTE", "Firewall rule %s on %s is disabled." % (r.get("name"), C["network"]))
                continue
            allowed = sorted("%s:%s" % (a.get("IPProtocol"), ",".join(a.get("ports", ["all"]))) for a in r["allowed"])
            if r.get("name") == HTTPS_FW_RULE:
                exact = (r.get("sourceRanges") == ["0.0.0.0/0"] and allowed == ["tcp:443", "tcp:80"] and not r.get("sourceTags")
                         and not r.get("sourceServiceAccounts") and r.get("targetTags") == [HTTPS_TAG]
                         and not r.get("targetServiceAccounts"))
                if exact:
                    out("OK", "Firewall %s: tcp:80,443 from anywhere, to tag %s only (public HTTPS)" % (HTTPS_FW_RULE, HTTPS_TAG))
                else:
                    out("PROBLEM", "Firewall %s isn't tcp:80,443 to tag %s only (sources %s, allows %s, targets %s). Fix it deliberately."
                        % (HTTPS_FW_RULE, HTTPS_TAG, ",".join(r.get("sourceRanges", [])) or "-", " ".join(allowed),
                           ",".join(r.get("targetTags", [])) or "every instance"))
            elif r.get("name") == FW_RULE:
                exact = (r.get("sourceRanges") == [IAP_RANGE] and allowed == ["tcp:22"] and not r.get("sourceTags")
                         and not r.get("sourceServiceAccounts"))
                if exact:
                    out("OK", "Firewall %s: tcp:22 from %s only" % (FW_RULE, IAP_RANGE))
                    state["firewall"] = "present"
                else:
                    out("PROBLEM", "Firewall %s isn't tcp:22 from %s only (sources %s, allows %s). Fix it deliberately."
                        % (FW_RULE, IAP_RANGE, ",".join(r.get("sourceRanges", [])) or "-", " ".join(allowed)))
                    state["firewall"] = "unknown"
            else:
                out("PROBLEM", "Unexpected inbound rule %s on %s (sources %s, allows %s): only %s and %s may admit traffic."
                    % (r.get("name"), C["network"], ",".join(r.get("sourceRanges", [])) or "-", " ".join(allowed), FW_RULE, HTTPS_FW_RULE))
        if state["firewall"] == "absent":
            out("CREATE", "Firewall %s on %s: tcp:22 from %s (IAP) only" % (FW_RULE, C["network"], IAP_RANGE))

    s, vms = read("instances")
    if not unreadable("vm", "VM instances", s, vms):
        named = [v for v in (vms or []) if v.get("name") == C["vm_name"]]
        zones = sorted(base(v.get("zone")) for v in named)
        if not named:
            out("CREATE", "VM %s in %s: e2-micro, 30 GB pd-standard, Debian 12, on %s/%s, as %s"
                % (C["vm_name"], C["zone"], C["network"], C["subnet"], C["sa_email"]))
            state["vm"] = "absent"
        elif zones != [C["zone"]]:
            out("PROBLEM", "A VM named %s already exists in %s, so this script won't create another in %s. "
                "Remove or rename it deliberately, or point watson.conf at it after checking it."
                % (C["vm_name"], ", ".join(zones), C["zone"]))
            state["vm"] = "unknown"
        else:
            state["vm"] = "present"
            v, bad = named[0], []
            if base(v.get("machineType")) != "e2-micro":
                bad.append("machine type %s, expected e2-micro" % base(v.get("machineType")))
            nics = v.get("networkInterfaces", [])
            if len(nics) != 1 or base(nics[0].get("network")) != C["network"] or base(nics[0].get("subnetwork")) != C["subnet"]:
                bad.append("network interfaces %s, expected only %s/%s"
                           % (["%s/%s" % (base(n.get("network")), base(n.get("subnetwork"))) for n in nics], C["network"], C["subnet"]))
            sas = [a.get("email") for a in v.get("serviceAccounts", [])]
            if sas != [C["sa_email"]]:
                bad.append("service account %s, expected %s" % (sas or "none", C["sa_email"]))
            meta = {i.get("key"): str(i.get("value", "")).upper() for i in (v.get("metadata", {}) or {}).get("items", [])}
            for k in ("enable-oslogin", "block-project-ssh-keys"):
                if meta.get(k) != "TRUE":
                    bad.append("metadata %s is %s, expected TRUE" % (k, meta.get(k, "unset")))
            boot = [d for d in v.get("disks", []) if d.get("boot")]
            ds, disk = read("boot-disk")
            if not boot:
                bad.append("no boot disk")
            elif ds == "present":
                if base(disk.get("type")) != "pd-standard":
                    bad.append("boot disk type %s, expected pd-standard (the Always Free disk)" % base(disk.get("type")))
                if int(disk.get("sizeGb", 0)) > 30:
                    bad.append("boot disk %s GB, more than the 30 GB Always Free" % disk.get("sizeGb"))
            else:
                bad.append("boot disk couldn't be read (%s)" % (disk or ds))
            if len(v.get("disks", [])) > 1:
                out("NOTE", "VM %s has %d disks; only the boot disk is expected." % (C["vm_name"], len(v.get("disks", []))))
            if bad:
                out("PROBLEM", "VM %s in %s: %s." % (C["vm_name"], C["zone"], "; ".join(bad)))
            else:
                out("OK", "VM %s in %s: e2-micro, pd-standard boot disk, only %s/%s, as %s, OS Login, project keys blocked"
                    % (C["vm_name"], C["zone"], C["network"], C["subnet"], C["sa_email"]))
            if not (v.get("shieldedInstanceConfig") or {}).get("enableSecureBoot"):
                out("NOTE", "VM %s has Secure Boot off." % C["vm_name"])
else:
    for key, what in (("network", "Network %s" % C["network"]), ("subnet", "Subnet %s" % C["subnet"]),
                      ("firewall", "Firewall %s: tcp:22 from %s only" % (FW_RULE, IAP_RANGE)),
                      ("vm", "VM %s in %s" % (C["vm_name"], C["zone"]))):
        out("CREATE", "%s (Compute Engine API not enabled: checked after --apply enables it)" % what)
        state[key] = "absent"

print("\n".join(lines))
with open(os.path.join(inv, "state"), "w") as f:
    for k, v in state.items():
        f.write("%s=%s\n" % (k, v))
sys.exit(1 if any(l.startswith("PROBLEM") for l in lines) else 0)
