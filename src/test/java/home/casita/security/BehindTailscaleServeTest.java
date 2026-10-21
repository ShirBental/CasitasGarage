package home.casita.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Enrolling a phone through tailscale serve, on a real Tomcat (MockMvc skips Tomcat's forwarded-header
 * handling). tailscale serve forwards https://<name>.ts.net to http://127.0.0.1:8080, replacing Host with
 * the backend's and adding X-Forwarded-Proto and X-Forwarded-Host, as Go's reverse proxy does.
 *
 * <p>The bug this guards against: Casita answered a successful invite sign-in with
 * {@code Location: http://127.0.0.1:8080/passkey}. Safari blocked that redirect (the page's
 * {@code form-action 'self'}), the page stayed, and a second tap re-sent a CSRF token the sign-in had
 * already rotated: 403. No CSRF or test helpers here: the real token from the page and the real cookies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "casita.security.data-dir=target/test-data/tailscale",
        "casita.security.family=shir",
        "casita.security.rp-id=macbook-air.example-tailnet.ts.net",
        "casita.security.allowed-origins=https://macbook-air.example-tailnet.ts.net"})
class BehindTailscaleServeTest {

    static final String NAME = "macbook-air.example-tailnet.ts.net";
    static final String ORIGIN = "https://" + NAME;

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, String> cookies = new LinkedHashMap<>(); // what Safari would keep for the site

    /** A request as tailscale serve delivers it. */
    private HttpRequest.Builder proxied(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", NAME)
                .header("X-Forwarded-For", "100.101.102.103");
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
        send(proxied("/ott/generate").header("Content-Type", "application/x-www-form-urlencoded").POST(form("username", "shir")));
        String link = Files.readString(Path.of("target/test-data/tailscale/invites/shir.txt")).trim();
        assertThat(link).startsWith(ORIGIN + "/login/ott?token=");
        String token = link.substring(link.indexOf("token=") + "token=".length());
        cookies.clear(); // the phone starts fresh

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

        // 3. Following it, signed in: the Face ID setup page.
        HttpResponse<String> setup = send(proxied("/passkey").GET());
        assertThat(setup.statusCode()).isEqualTo(200);
        assertThat(setup.body()).contains("Set up Face ID");
    }

    @Test
    void theRedirectToSignInStaysOnTheRealSiteToo() throws Exception {
        HttpResponse<String> home = send(proxied("/").GET());

        assertThat(home.statusCode()).isEqualTo(302);
        assertThat(home.headers().firstValue("Location").orElseThrow()).doesNotContain("127.0.0.1").isIn("/login", ORIGIN + "/login");
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
