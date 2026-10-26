package home.casita.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import home.casita.security.access.DeviceKeys;
import home.casita.security.access.Invitations;
import home.casita.security.access.LocalAdmin;
import home.casita.security.store.SecurityStore;
import home.casita.security.access.TestBrowsers;

/**
 * Enrolling a phone through the reverse proxy, on a real Tomcat (MockMvc skips Tomcat's forwarded-header
 * handling). Caddy forwards https://<public name> to http://127.0.0.1:8080, keeping Host, and replacing any
 * client-sent X-Forwarded-For, -Proto and -Host with its own: the client's address, https and the public name
 * (deploy/watson/Caddyfile). Tomcat trusts those from 127.0.0.1 only.
 *
 * <p>The bug this guards against: Casita answered a successful invite sign-in with
 * {@code Location: http://127.0.0.1:8080/passkey}. Safari blocked that redirect (the page's
 * {@code form-action 'self'}), the page stayed, and a second tap re-sent a CSRF token the sign-in had
 * already rotated: 403. No CSRF or test helpers here: the real token from the page and the real cookies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "casita.security.data-dir=target/test-data/reverse-proxy",
        "casita.security.family=shir",
        "casita.security.rp-id=casita.example.org",
        "casita.security.allowed-origins=https://casita.example.org"})
@Import(TestBrowsers.Config.class)
class BehindReverseProxyTest {

    static final String NAME = "casita.example.org";
    /** The phone's address on the internet (a documentation address), as Caddy reports it. */
    static final String PHONE_ADDRESS = "203.0.113.10";
    static final String ORIGIN = "https://" + NAME;

    @LocalServerPort
    int port;
    @Autowired
    Invitations invitations;
    @Autowired
    SecurityStore store;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, String> cookies = new LinkedHashMap<>(); // what Safari would keep for the site

    /** A request as Caddy delivers it. */
    private HttpRequest.Builder proxied(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", NAME)
                .header("X-Forwarded-For", PHONE_ADDRESS);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        if (!cookies.isEmpty()) {
            request.header("Cookie", String.join("; ", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        for (String setCookie : response.headers().allValues("Set-Cookie")) {
            String[] nameValue = setCookie.split(";", 2)[0].split("=", 2);
            if (setCookie.contains("Expires=Thu, 01 Jan 1970")) cookies.remove(nameValue[0]);
            else cookies.put(nameValue[0], nameValue[1]);
        }
        return response;
    }

    private static HttpRequest.BodyPublisher form(String... pairs) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (body.length() > 0) body.append('&');
            body.append(pairs[i]).append('=').append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return HttpRequest.BodyPublishers.ofString(body.toString());
    }

    @Test
    void anInviteSignInBehindTheProxyRedirectsToTheRealSiteAndFaceIdSetupLoads() throws Exception {
        String token = invitations.create("shir", "Shir's iPhone").token();
        String link = ORIGIN + "/login/ott?token=" + token;

        // 1. The link opens Spring's token page; its CSRF cookie is Secure, as the site is https.
        HttpResponse<String> page = send(proxied("/login/ott?token=" + token).GET());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().allValues("Set-Cookie")).anyMatch(c -> c.startsWith("XSRF-TOKEN=") && c.contains("Secure"));
        Matcher hidden = Pattern.compile("name=\"_csrf\" type=\"hidden\" value=\"([^\"]+)\"").matcher(page.body());
        assertThat(hidden.find()).isTrue();

        // 2. Tapping Sign in: the redirect must stay on the real site, never http://127.0.0.1.
        HttpResponse<String> signIn = send(proxied("/login/ott")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", ORIGIN).header("Referer", link)
                .POST(form("token", token, "_csrf", hidden.group(1))));
        assertThat(signIn.statusCode()).isEqualTo(302);
        String location = signIn.headers().firstValue("Location").orElseThrow();
        assertThat(location).doesNotContain("127.0.0.1").isIn("/passkey", ORIGIN + "/passkey");
        assertThat(signIn.headers().allValues("Set-Cookie")).anyMatch(c -> c.startsWith("JSESSIONID=") && c.contains("Secure"));
        // This browser's new browser key: host-only, HTTPS-only, invisible to scripts, and the device the invite is for.
        String key = signIn.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith(DeviceKeys.COOKIE + "=")).findFirst().orElseThrow();
        assertThat(key).contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax").doesNotContain("Domain=");
        String deviceId = cookies.get(DeviceKeys.COOKIE).substring(0, cookies.get(DeviceKeys.COOKIE).indexOf('.'));
        assertThat(store.invitationsOf("shir")).anyMatch(i -> deviceId.equals(i.nodeId()));

        // 3. Following it, enrolling: the Face ID setup page.
        HttpResponse<String> setup = send(proxied("/passkey").GET());
        assertThat(setup.statusCode()).isEqualTo(200);
        assertThat(setup.body()).contains("Set up Face ID");
    }

    @Test
    void anInviteIsntRedeemedWhenTheDeviceCantBeVerified() throws Exception {
        Invitations.Created invite = invitations.create("shir", "Shir's iPhone");
        String token = invite.token();
        HttpResponse<String> page = send(proxied("/login/ott?token=" + token).GET());
        Matcher hidden = Pattern.compile("name=\"_csrf\" type=\"hidden\" value=\"([^\"]+)\"").matcher(page.body());
        assertThat(hidden.find()).isTrue();

        // Plain http, not through the proxy, Tailscale's identity header and all: the browser couldn't keep a browser key.
        HttpResponse<String> signIn = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login/ott"))
                .header("Tailscale-User-Login", "shir@example.com")
                .header("Content-Type", "application/x-www-form-urlencoded").header("Origin", ORIGIN)
                .POST(form("token", token, "_csrf", hidden.group(1))));
        assertThat(signIn.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith(DeviceKeys.COOKIE));

        assertThat(signIn.statusCode()).isEqualTo(302);
        assertThat(signIn.headers().firstValue("Location").orElseThrow()).isIn("/login?device-unverified", ORIGIN + "/login?device-unverified");
        assertThat(store.invitationById(invite.invitation().inviteId())).get()
                .extracting(SecurityStore.Invitation::status).isEqualTo(SecurityStore.InvitationStatus.PENDING); // still unused
    }

    @Test
    void theRedirectToSignInStaysOnTheRealSiteToo() throws Exception {
        HttpResponse<String> home = send(proxied("/").GET());

        assertThat(home.statusCode()).isEqualTo(302);
        assertThat(home.headers().firstValue("Location").orElseThrow()).doesNotContain("127.0.0.1").isIn("/login", ORIGIN + "/login");
    }

    @Test
    void administrationThroughTheProxyIsRefusedEvenWithTheSecret() throws Exception {
        String secret = java.nio.file.Files.readString(java.nio.file.Path.of("target/test-data/reverse-proxy/admin.secret")).trim();
        // As Caddy would forward it (if it didn't already answer /admin itself): Tomcat takes the client's address.
        HttpResponse<String> proxiedAdmin = send(proxied("/admin/access").header(LocalAdmin.HEADER, secret).GET());
        assertThat(proxiedAdmin.statusCode()).isEqualTo(403);
        // Straight to 127.0.0.1, as casita-access does: allowed.
        HttpResponse<String> local = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/admin/access"))
                .header(LocalAdmin.HEADER, secret).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(local.statusCode()).isEqualTo(200);
    }

    @Test
    void withoutTheProxyLocalUseStillWorks() throws Exception {
        HttpResponse<String> home = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).GET());

        assertThat(home.statusCode()).isEqualTo(302);
        assertThat(home.headers().firstValue("Location").orElseThrow()).isIn("/login", "http://127.0.0.1:" + port + "/login");
        // Plain http, no proxy: the CSRF cookie isn't marked Secure (it couldn't be sent back over http).
        assertThat(home.headers().allValues("Set-Cookie")).filteredOn(c -> c.startsWith("XSRF-TOKEN=")).hasSize(1).noneMatch(c -> c.contains("Secure"));
    }
}
