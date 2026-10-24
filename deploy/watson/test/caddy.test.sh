#!/usr/bin/env bash
# The Caddyfile against a real Caddy, locally: what a client can and can't make Casita see through it.
#
#   CADDY=/path/to/caddy bash deploy/watson/test/caddy.test.sh
#
# Renders deploy/watson/Caddyfile for "localhost" (Caddy then uses its own local CA instead of Let's Encrypt), on
# unprivileged ports, with a stand-in for Casita on 127.0.0.1 that answers with the request it received. Needs caddy,
# python3 and curl; nothing leaves this machine.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
caddy="${CADDY:-$(command -v caddy || true)}"
[ -x "$caddy" ] || { echo "SKIP: no caddy (set CADDY=/path/to/caddy)"; exit 0; }
work="$(mktemp -d)"; pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null; done; wait 2>/dev/null; rm -rf "$work"; }
trap cleanup EXIT
H=18443; P=18080; U=18081

# Casita's stand-in: echoes method, path and headers as JSON, and counts what it saw.
cat > "$work/upstream.py" <<'PY'
import http.server, json, sys
class H(http.server.BaseHTTPRequestHandler):
    def handle_one(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n: self.rfile.read(n)
        body = json.dumps({"method": self.command, "path": self.path, "headers": {k: v for k, v in self.headers.items()}}).encode()
        with open(sys.argv[2], "a") as f: f.write(self.path + "\n")
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(body)))
        self.end_headers(); self.wfile.write(body)
    do_GET = do_POST = handle_one
    def log_message(self, *a): pass
http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY
python3 -I "$work/upstream.py" "$U" "$work/seen" & pids+=($!)

sed -e 's/{{HOST}}/localhost/g' -e "s/{{UPSTREAM}}/127.0.0.1:$U/g" -e "s#/var/log/caddy/casita-access.log#$work/access.log#" \
  "$here/../Caddyfile" > "$work/Caddyfile"
# Unprivileged ports, a private storage dir, no admin endpoint clash: global options added to the first block.
python3 -I - "$work/Caddyfile" "$P" "$H" <<'PY'
import sys
p, http_port, https_port = sys.argv[1:]
s = open(p).read()
s = s.replace("{\n\tservers {", "{\n\thttp_port %s\n\thttps_port %s\n\tadmin off\n\tskip_install_trust\n\tservers {" % (http_port, https_port), 1)
open(p, "w").write(s)
PY
XDG_DATA_HOME="$work/data" XDG_CONFIG_HOME="$work/config" "$caddy" run --config "$work/Caddyfile" --adapter caddyfile > "$work/caddy.log" 2>&1 & pids+=($!)
for _ in $(seq 1 50); do curl -sk --max-time 1 "https://localhost:$H/healthz" >/dev/null 2>&1 && break; sleep 0.2; done

fails=0; passes=0
check() { if eval "$2"; then passes=$((passes + 1)); echo "  ok   $1"; else fails=$((fails + 1)); echo "  FAIL $1"; fi; }
get() { curl -sk --max-time 5 "$@"; }
hdr() { python3 -I -c 'import json,sys; d=json.load(sys.stdin)["headers"]; print(d.get(sys.argv[1], "<absent>"))' "$1"; }

echo "Caddyfile, through a real Caddy ($("$caddy" version | cut -d' ' -f1))"
r="$(get "https://localhost:$H/healthz" -H 'X-Forwarded-For: 1.2.3.4' -H 'X-Forwarded-Host: evil.example' -H 'X-Forwarded-Proto: http')"
check "a client's X-Forwarded-For is replaced with its real address" 'case "$(hdr X-Forwarded-For <<<"$r")" in 127.0.0.1|::1) true ;; *) false ;; esac'
check "a client's X-Forwarded-Host is replaced with the site's own (host:port as requested)" '[ "$(hdr X-Forwarded-Host <<<"$r")" = "localhost:$H" ]'
check "a client's X-Forwarded-Proto is replaced with https" '[ "$(hdr X-Forwarded-Proto <<<"$r")" = https ]'

r="$(get "https://localhost:$H/api/doors" -H 'Tailscale-User-Login: shir@example.com' -H 'Tailscale-Funnel-Request: ?1' \
      -H 'X-Casita-Admin: guess' -H 'Forwarded: for=127.0.0.1' -H 'X-Real-IP: 127.0.0.1')"
for h in Tailscale-User-Login Tailscale-Funnel-Request X-Casita-Admin Forwarded X-Real-IP; do
  check "$h from a client never reaches Casita" '[ "$(hdr "$h" <<<"$r")" = "<absent>" ]'
done

: > "$work/seen"
for path in /admin /admin/access /admin/invites /Admin/access /ADMIN/access "/admin/../admin/access" "/%61dmin/access"; do
  code="$(get -o /dev/null -w '%{http_code}' --path-as-is "https://localhost:$H$path")"
  check "$path answers 404 at Caddy" '[ "$code" = 404 ]'
done
check "no /admin request reached Casita" '! grep -qi "admin" "$work/seen"'

code="$(head -c 300000 /dev/zero | get -o /dev/null -w '%{http_code}' -X POST --data-binary @- "https://localhost:$H/login/ott")"
check "a request body over 256 KB is refused (413)" '[ "$code" = 413 ]'

code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://localhost:$P/login")"
loc="$(curl -s -o /dev/null -w '%{redirect_url}' --max-time 5 "http://localhost:$P/login")"
check "plain http redirects to https" '[ "$code" = 308 ] && [ "${loc#https://localhost}" != "$loc" ]'

get "https://localhost:$H/login/ott?token=SECRET-INVITE-TOKEN&x=1" -H 'Cookie: JSESSIONID=SECRET-SESSION; __Host-casita-device=SECRET-KEY' \
  -H 'X-XSRF-TOKEN: SECRET-CSRF' >/dev/null
sleep 0.5
check "the access log was written" '[ -s "$work/access.log" ]'
check "the access log has no invite token, cookie or CSRF token" '! grep -q SECRET "$work/access.log"'
check "the access log shows the token as REDACTED" 'grep -q "token=REDACTED" "$work/access.log"'
check "Caddy doesn't announce itself" '[ -z "$(get -D - -o /dev/null "https://localhost:$H/healthz" | grep -i "^server:")" ]'

echo
echo "$passes passed, $fails failed"
[ $fails -eq 0 ] || { echo "--- caddy.log"; tail -20 "$work/caddy.log"; }
exit $((fails > 0))
