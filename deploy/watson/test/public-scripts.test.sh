#!/usr/bin/env bash
# provision-public.sh and duckdns-update.sh against fakes: what they read, what they change, and that the DuckDNS
# token never reaches a command line or the output.
#
#   bash deploy/watson/test/public-scripts.test.sh
set -uo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin" "$work/state"
fails=0; passes=0; name=""
scenario() { name="$1"; FAILED=no; }
bad() { echo "  FAIL $name: $*"; FAILED=yes; fails=$((fails + 1)); }
done_scenario() { [ "$FAILED" = no ] && { passes=$((passes + 1)); echo "  ok   $name"; }; }
expect_code() { [ "$code" = "$1" ] || bad "exit $code, expected $1"; }
expect_out() { grep -qF -- "$1" <<<"$out" || bad "output lacks: $1"; }
expect_no_out() { ! grep -qF -- "$1" <<<"$out" || bad "output has: $1"; }

# ---------- fakes ----------
# gcloud: list commands print $FAKE_STATE/<words>.json; anything else is a change, logged as MUTATE.
cat > "$work/bin/gcloud" <<'SH'
#!/usr/bin/env bash
words=(); for a in "$@"; do case "$a" in --*) ;; *) words+=("$a") ;; esac; done
key="$(IFS=_; echo "${words[*]}")"
case "$key" in
  compute_instances_list|compute_firewall-rules_list|compute_addresses_list) cat "$FAKE_STATE/$key.json" ;;
  *) echo "MUTATE gcloud $*" >> "$FAKE_LOG" ;;
esac
SH
# curl: the metadata server answers the VM's address; a DuckDNS update records its argv and stdin.
cat > "$work/bin/curl" <<'SH'
#!/usr/bin/env bash
echo "ARGV $*" >> "$FAKE_LOG"
case "$*" in *169.254.169.254*) echo "${FAKE_IP:-34.10.20.30}"; exit 0 ;; esac
case "$*" in *"--config -"*) stdin="$(cat)"; echo "STDIN $stdin" >> "$FAKE_LOG"; echo "${FAKE_DUCK:-OK}"; exit 0 ;; esac
exit 7
SH
cat > "$work/bin/getent" <<'SH'
#!/usr/bin/env bash
[ -n "${FAKE_DNS:-}" ] && echo "$FAKE_DNS STREAM $2"
SH
cat > "$work/bin/id" <<'SH'
#!/usr/bin/env bash
[ "${1:-}" = "-u" ] && { echo 0; exit 0; }; exec /usr/bin/id "$@"
SH
cat > "$work/bin/chown" <<'SH'
#!/usr/bin/env bash
exit 0
SH
chmod +x "$work/bin/"*
log="$work/calls.log"
run() { : > "$log"; out="$(PATH="$work/bin:$PATH" FAKE_STATE="$work/state" FAKE_LOG="$log" "$@" 2>&1)"; code=$?; }
mutations() { grep '^MUTATE' "$log" || true; }

cat > "$work/watson.conf" <<'C'
PROJECT_ID=bentals-casita
VM_NAME=watson
NETWORK=watson-net
STATIC_IP_NAME=watson-ip
HTTPS_TAG=casita-https
C
vm() {  # vm TAGS: watson in us-central1-a with an ephemeral 34.10.20.30
  cat > "$work/state/compute_instances_list.json" <<J
[{"name":"watson","zone":"x/zones/us-central1-a","status":"RUNNING","tags":{"items":[$1]},
  "networkInterfaces":[{"network":"x/global/networks/watson-net","accessConfigs":[{"natIP":"34.10.20.30","networkTier":"PREMIUM"}]}]}]
J
}
iap='{"name":"watson-net-allow-iap-ssh","network":"x/global/networks/watson-net","direction":"INGRESS","sourceRanges":["35.235.240.0/20"],"allowed":[{"IPProtocol":"tcp","ports":["22"]}]}'
https='{"name":"watson-net-allow-https","network":"x/global/networks/watson-net","direction":"INGRESS","sourceRanges":["0.0.0.0/0"],"allowed":[{"IPProtocol":"tcp","ports":["80"]},{"IPProtocol":"tcp","ports":["443"]}],"targetTags":["casita-https"]}'
rules() { echo "[$1]" > "$work/state/compute_firewall-rules_list.json"; }
addresses() { echo "[$1]" > "$work/state/compute_addresses_list.json"; }

echo "provision-public.sh and duckdns-update.sh, against fakes"

scenario "fresh: dry run plans the address, the rule and the tag, changes nothing"
vm ''; rules "$iap"; addresses ''
run bash "$here/provision-public.sh" "$work/watson.conf"
expect_code 0; [ -z "$(mutations)" ] || bad "changed something"
expect_out "external address 34.10.20.30 is ephemeral: reserve it in place as watson-ip (same address)"
expect_out "firewall watson-net-allow-https: admit tcp:80,443 from anywhere to instances tagged casita-https"
expect_out "add tag casita-https to watson"
expect_out "DRY RUN"
done_scenario

scenario "fresh, --apply: promotes the same address, opens only 80 and 443 to the tag, tags the VM"
run bash "$here/provision-public.sh" "$work/watson.conf" --apply
expect_code 0
m="$(mutations)"
grep -q "addresses create watson-ip --region=us-central1 --addresses=34.10.20.30 --network-tier=PREMIUM" <<<"$m" || bad "no in-place reservation: $m"
grep -q "firewall-rules create watson-net-allow-https --network=watson-net --direction=INGRESS --action=allow --rules=tcp:80,tcp:443 --source-ranges=0.0.0.0/0 --target-tags=casita-https" <<<"$m" || bad "rule not as expected: $m"
grep -q "instances add-tags watson --zone=us-central1-a --tags=casita-https" <<<"$m" || bad "no tag: $m"
[ "$(wc -l <<<"$m")" -eq 3 ] || bad "expected exactly 3 changes: $m"
done_scenario

scenario "already done: everything OK, nothing changed"
vm '"casita-https"'; rules "$iap,$https"; addresses '{"name":"watson-ip","address":"34.10.20.30","status":"IN_USE","networkTier":"PREMIUM"}'
run bash "$here/provision-public.sh" "$work/watson.conf" --apply
expect_code 0; [ -z "$(mutations)" ] || bad "changed something"; expect_out "Nothing to change"; expect_no_out "CHANGE"
done_scenario

scenario "a rule opening 8080 to the internet: refused, nothing changed"
vm ''; rules "$iap"',{"name":"watson-net-allow-app","network":"x/global/networks/watson-net","direction":"INGRESS","sourceRanges":["0.0.0.0/0"],"allowed":[{"IPProtocol":"tcp","ports":["8000-9000"]}]}'; addresses ''
run bash "$here/provision-public.sh" "$work/watson.conf" --apply
expect_code 1; [ -z "$(mutations)" ] || bad "changed something"; expect_out "admits port 8080"; expect_out "STOPPED"
done_scenario

scenario "no IAP rule: refused (administration would be lost)"
vm ''; rules ""; addresses ''
run bash "$here/provision-public.sh" "$work/watson.conf" --apply
expect_code 1; [ -z "$(mutations)" ] || bad "changed something"; expect_out "no rule admits tcp:22 from 35.235.240.0/20"
done_scenario

scenario "an HTTPS rule open to every instance: refused"
vm ''; rules "$iap,${https/,\"targetTags\":\[\"casita-https\"\]/}"; addresses ''
run bash "$here/provision-public.sh" "$work/watson.conf" --apply
expect_code 1; [ -z "$(mutations)" ] || bad "changed something"; expect_out "isn't exactly tcp:80,443"
done_scenario

TOKEN=0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0
tokfile="$work/duckdns.token"

scenario "duckdns: the token file must exist; dry run says what it would do"
run env DUCKDNS_TOKEN_FILE="$tokfile" FAKE_DNS=1.2.3.4 bash "$here/duckdns-update.sh" --host watsonbental.duckdns.org
expect_code 0; expect_out "This VM's external address: 34.10.20.30"; expect_out "token: MISSING"; expect_out "DRY RUN"
done_scenario

scenario "duckdns --apply: sends the VM's own address, token only on stdin, never printed"
printf '%s\n' "$TOKEN" > "$tokfile"; chmod 600 "$tokfile"
run env DUCKDNS_TOKEN_FILE="$tokfile" FAKE_DNS=34.10.20.30 bash "$here/duckdns-update.sh" --host watsonbental.duckdns.org --apply
expect_code 0; expect_out "DuckDNS: OK (watsonbental.duckdns.org -> 34.10.20.30)"; expect_out "resolves to 34.10.20.30"
grep -q "^STDIN url = \"https://www.duckdns.org/update?domains=watsonbental&token=$TOKEN&ip=34.10.20.30\"" "$log" || bad "update not as expected"
grep '^ARGV' "$log" | grep -q "$TOKEN" && bad "the token was on a command line"
grep -q "$TOKEN" <<<"$out" && bad "the token was printed"
done_scenario

scenario "duckdns: a KO answer fails, and the token still isn't printed"
run env DUCKDNS_TOKEN_FILE="$tokfile" FAKE_DUCK=KO bash "$here/duckdns-update.sh" --host watsonbental.duckdns.org --apply
expect_code 1; expect_out "KO"; grep -q "$TOKEN" <<<"$out" && bad "the token was printed"
done_scenario

scenario "duckdns: a token file readable by others is refused"
chmod 644 "$tokfile"
run env DUCKDNS_TOKEN_FILE="$tokfile" bash "$here/duckdns-update.sh" --host watsonbental.duckdns.org --apply
expect_code 1; expect_out "must be mode 600"; ! grep -q '^STDIN' "$log" || bad "sent anyway"
chmod 600 "$tokfile"
done_scenario

scenario "duckdns: only <name>.duckdns.org names"
run env DUCKDNS_TOKEN_FILE="$tokfile" bash "$here/duckdns-update.sh" --host watson.example.org --apply
expect_code 2; ! grep -q '^STDIN' "$log" || bad "sent anyway"
done_scenario

echo
echo "$passes passed, $fails failed"
exit $((fails > 0))
