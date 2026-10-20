import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

import home.casita.security.access.SoftAuthenticator;

/**
 * Benchmark driver for Casita (simulation only). Talks to Casita on 127.0.0.1 the way tailscale serve does:
 * X-Forwarded-For (a tailnet address), X-Forwarded-Proto https, X-Forwarded-Host, Tailscale-User-Login.
 * Enrolls two phones through real invites and real WebAuthn (SoftAuthenticator: P-256, ES256), then runs a
 * two-user workload against the two simulated doors. Every request is written to a CSV.
 *
 * args: baseUrl dataDir durationSeconds pollMs commandEverySeconds signInEverySeconds out.csv
 */
public class Driver {
    static final String HOST = "casita.test";
    static HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();
    static PrintWriter csv;
    static String base;
    static Path data;
    static volatile boolean running = true;
    static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    record Phone(String member, String address, String login, String label) {}

    static final class Jar {
        final Map<String, String> cookies = new ConcurrentHashMap<>();
        String header() {
            StringJoiner j = new StringJoiner("; ");
            cookies.forEach((k, v) -> j.add(k + "=" + v));
            return j.toString();
        }
        void take(HttpResponse<?> r) {
            for (String sc : r.headers().allValues("set-cookie")) {
                String nv = sc.split(";", 2)[0];
                int eq = nv.indexOf('=');
                String name = nv.substring(0, eq), value = nv.substring(eq + 1);
                if (value.isEmpty() || sc.toLowerCase().contains("max-age=0")) cookies.remove(name);
                else cookies.put(name, value);
            }
        }
    }

    static synchronized void record(String phase, String who, String label, int status, long micros, long bytes) {
        csv.printf("%d,%s,%s,%s,%d,%.3f,%d%n", System.currentTimeMillis(), phase, who, label, status, micros / 1000.0, bytes);
    }

    static HttpResponse<String> call(String phase, Phone p, Jar jar, String method, String path, String label,
                                     String contentType, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
        if (p != null) {
            b.header("X-Forwarded-For", p.address()).header("X-Forwarded-Proto", "https")
             .header("X-Forwarded-Host", HOST).header("Tailscale-User-Login", p.login());
        }
        if (jar != null && !jar.cookies.isEmpty()) b.header("Cookie", jar.header());
        if (jar != null && !method.equals("GET") && jar.cookies.containsKey("XSRF-TOKEN"))
            b.header("X-XSRF-TOKEN", java.net.URLDecoder.decode(jar.cookies.get("XSRF-TOKEN"), StandardCharsets.UTF_8));
        b.header("Accept", "application/json, text/html, */*");
        if (body != null) {
            b.header("Content-Type", contentType);
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else b.method(method, HttpRequest.BodyPublishers.noBody());
        long t0 = System.nanoTime();
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        long us = (System.nanoTime() - t0) / 1000;
        if (jar != null) jar.take(r);
        String l = label;
        if (label.startsWith("cmd")) { Matcher m = Pattern.compile("\"outcome\":\"([A-Z_]+)\"").matcher(r.body()); if (m.find()) l = label + ":" + m.group(1); }
        record(phase, p == null ? "admin" : p.member(), l, r.statusCode(), us, r.body().length());
        return r;
    }

    static String adminInvite(Phone p) throws Exception {
        String secret = Files.readString(data.resolve("admin.secret")).trim();
        String form = "member=" + URLEncoder.encode(p.member(), StandardCharsets.UTF_8) + "&label=" + URLEncoder.encode(p.label(), StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/admin/invites")).header("X-Casita-Admin", secret)
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
        long t0 = System.nanoTime();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        record("setup", "admin", "admin_invite", r.statusCode(), (System.nanoTime() - t0) / 1000, r.body().length());
        Matcher m = Pattern.compile("\"id\":\"(i-[0-9a-f]{8})\"").matcher(r.body());
        if (!m.find()) throw new IllegalStateException("invite failed: " + r.statusCode() + " " + r.body());
        String link = Files.readString(data.resolve("invites").resolve(m.group(1) + ".txt")).trim();
        return link.substring(link.indexOf("token=") + 6);
    }

    static void expect(HttpResponse<String> r, int status, String what) {
        if (r.statusCode() != status) throw new IllegalStateException(what + ": " + r.statusCode() + " " + r.body() + " " + r.headers().map());
    }

    /** Invite, redeem, register a real passkey: the phone's device is authorized. */
    static void enroll(Phone p, SoftAuthenticator key) throws Exception {
        String token = adminInvite(p);
        Jar jar = new Jar();
        call("setup", p, jar, "GET", "/login", "page_login", null, null);
        HttpResponse<String> r = call("setup", p, jar, "POST", "/login/ott", "redeem_invite", "application/x-www-form-urlencoded",
                "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
        expect(r, 302, "redeem");
        r = call("setup", p, jar, "POST", "/webauthn/register/options", "register_options", null, null);
        expect(r, 200, "register options");
        String body = key.register(r.body(), "iCloud Keychain");
        r = call("setup", p, jar, "POST", "/webauthn/register", "register_passkey", "application/json", body);
        expect(r, 200, "register");
    }

    /** A Face ID sign-in on the authorized phone, then the home page and what it loads. */
    static Jar signIn(String phase, Phone p, SoftAuthenticator key) throws Exception {
        Jar jar = new Jar();
        call(phase, p, jar, "GET", "/login", "page_login", null, null);
        HttpResponse<String> r = call(phase, p, jar, "POST", "/webauthn/authenticate/options", "signin_options", null, null);
        expect(r, 200, "authenticate options");
        r = call(phase, p, jar, "POST", "/login/webauthn", "signin_assertion", "application/json", key.assertion(r.body()));
        expect(r, 200, "sign in");
        for (String path : List.of("/", "/app.js", "/common.js", "/casita.css", "/brand/watson-home.webp", "/manifest.webmanifest", "/icons/apple-touch-icon.png"))
            call(phase, p, jar, "GET", path, "asset", null, null);
        expect(call(phase, p, jar, "GET", "/api/me", "api_me", null, null), 200, "me");
        call(phase, p, jar, "GET", "/api/activity", "api_activity", null, null);
        return jar;
    }

    static void user(Phone p, SoftAuthenticator key, long endAt, long pollMs, long cmdEvery, long signInEvery, int seed) {
        Random rnd = new Random(seed);
        try {
            Jar jar = signIn("work", p, key);
            long nextCmd = System.currentTimeMillis() + cmdEvery * 1000 / 2 + rnd.nextInt(5000);
            long nextSignIn = System.currentTimeMillis() + signInEvery * 1000;
            long fastUntil = 0;
            String[] doors = {"Ford", "Toyota"};
            int k = seed;
            while (running && System.currentTimeMillis() < endAt) {
                long now = System.currentTimeMillis();
                if (now >= nextSignIn) {
                    call("work", p, jar, "POST", "/logout", "logout", null, null);
                    jar = signIn("work", p, key);
                    nextSignIn = now + signInEvery * 1000;
                }
                HttpResponse<String> doorsR = call("work", p, jar, "GET", "/api/doors", "poll_doors", null, null);
                if (doorsR.statusCode() != 200) { System.err.println(p.member() + " poll " + doorsR.statusCode()); jar = signIn("work", p, key); }
                if (now >= nextCmd) {
                    String door = doors[k++ % 2];
                    String state = "";
                    for (tools.jackson.databind.JsonNode d : JSON.readTree(doorsR.body()).path("doors"))
                        if (d.path("name").asText().equals(door)) state = d.path("door").asText();
                    String action = state.equals("CLOSED") ? "open" : "close";   // NOT_CLOSED (or anything else): close
                    call("work", p, jar, "POST", "/api/doors/" + door + "/" + action, "cmd_" + action, null, null);
                    fastUntil = now + 15000;
                    nextCmd = now + cmdEvery * 1000;
                }
                Thread.sleep(now < fastUntil ? 1000 : pollMs);
            }
        } catch (Exception e) {
            System.err.println(p.member() + " failed: " + e);
            e.printStackTrace();
        }
    }

    public static void main(String[] a) throws Exception {
        base = a[0];
        data = Path.of(a[1]);
        long duration = Long.parseLong(a[2]), pollMs = Long.parseLong(a[3]), cmdEvery = Long.parseLong(a[4]), signInEvery = Long.parseLong(a[5]);
        csv = new PrintWriter(Files.newBufferedWriter(Path.of(a[6])), true);
        csv.println("epoch_ms,phase,who,label,status,ms,bytes");
        Phone shir = new Phone("shir", "100.64.0.1", "shir@example.com", "Shir's iPhone");
        Phone hubby = new Phone("hubby", "100.64.0.2", "hubby@example.com", "Hubby's iPhone");
        Map<String, SoftAuthenticator> keys = new HashMap<>();
        for (Phone p : List.of(shir, hubby)) {
            SoftAuthenticator key = new SoftAuthenticator();
            key.origin = "https://" + HOST;
            enroll(p, key);
            keys.put(p.member(), key);
        }
        System.out.println("enrolled both phones");
        // Negative checks: an unknown tailnet address, and a session cookie replayed from the other phone.
        Phone stranger = new Phone("stranger", "100.64.0.9", "eve@example.com", "x");
        call("checks", stranger, new Jar(), "GET", "/api/doors", "deny_unknown_node", null, null);
        Jar shirJar = signIn("checks", shir, keys.get("shir"));
        Phone shirCookieOnHubbyPhone = new Phone("shir-on-hubby", hubby.address(), hubby.login(), "x");
        call("checks", shirCookieOnHubbyPhone, shirJar, "GET", "/api/doors", "deny_cookie_other_node", null, null);

        long endAt = System.currentTimeMillis() + duration * 1000;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> user(shir, keys.get("shir"), endAt, pollMs, cmdEvery, signInEvery, 1));
        pool.submit(() -> user(hubby, keys.get("hubby"), endAt, pollMs, cmdEvery, signInEvery, 2));
        pool.shutdown();
        pool.awaitTermination(duration + 120, TimeUnit.SECONDS);

        // Burst: 20 concurrent polls and 4 simultaneous taps on one door, from both phones.
        Jar sj = signIn("burst", shir, keys.get("shir")), hj = signIn("burst", hubby, keys.get("hubby"));
        ExecutorService burst = Executors.newFixedThreadPool(20);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            final int n = i;
            fs.add(burst.submit(() -> call("burst", n % 2 == 0 ? shir : hubby, n % 2 == 0 ? sj : hj, "GET", "/api/doors", "poll_doors", null, null)));
        }
        for (Future<?> f : fs) f.get();
        fs.clear();
        Thread.sleep(14000);   // let both doors settle (10 s travel + 2 s verify), so exactly one tap can move Ford
        for (int i = 0; i < 4; i++) {
            final int n = i;
            fs.add(burst.submit(() -> call("burst", n % 2 == 0 ? shir : hubby, n % 2 == 0 ? sj : hj, "POST", "/api/doors/Ford/open", "cmd_race", null, null)));
        }
        for (Future<?> f : fs) f.get();
        burst.shutdown();
        csv.close();
        System.out.println("done");
    }
}
