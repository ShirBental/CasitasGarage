package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import home.casita.meross.MerossException.Kind;
import tools.jackson.databind.json.JsonMapper;

/** The HTTP client against a local fake of Meross's API that checks every request signature. */
class MerossCloudApiTest {

    /** A raw HTTP answer, for non-JSON, error statuses and slow replies. */
    record Raw(int status, String body, long delayMillis) {}

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private String baseUrl;
    private final List<Map<String, Object>> requests = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();
    private final AtomicInteger badRequests = new AtomicInteger();
    private Function<String, Object> responder;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        assertThat(badRequests).as("requests with a bad signature or missing headers").hasValue(0);
    }

    @SuppressWarnings("unchecked")
    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            Map<String, Object> body = json.readValue(ex.getRequestBody().readAllBytes(), Map.class);
            String params = (String) body.get("params");
            String expectedSign = MerossCrypto.httpSign(((Number) body.get("timestamp")).longValue(),
                    (String) body.get("nonce"), params);
            if (!expectedSign.equals(body.get("sign")) || !"meross".equals(ex.getRequestHeaders().getFirst("vender"))) {
                badRequests.incrementAndGet();
            }
            authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
            requests.add(json.readValue(Base64.getDecoder().decode(params), Map.class));

            Object answer = responder.apply(ex.getRequestURI().getPath());
            int status = 200;
            byte[] out;
            if (answer instanceof Raw raw) {
                if (raw.delayMillis() > 0) sleep(raw.delayMillis());
                status = raw.status();
                out = raw.body().getBytes(StandardCharsets.UTF_8);
            } else {
                out = json.writeValueAsBytes(answer);
            }
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        }
    }

    @Test
    void signsInWithAHashedPassword() throws Exception {
        responder = path -> ok(Map.of("userid", "123456", "email", "shir@example.com", "key", "k", "token", "t",
                "domain", baseUrl, "mqttDomain", "mqtt-us-4.meross.com"));

        MerossCredentials creds = api().login("shir@example.com", "hunter2");

        assertThat(creds.userId()).isEqualTo("123456");
        assertThat(creds.mqttHost()).isEqualTo("mqtt-us-4.meross.com");
        assertThat(requests.get(0))
                .containsEntry("email", "shir@example.com")
                .containsEntry("password", MerossCrypto.md5Hex("hunter2"))
                .containsEntry("encryption", 1);
        assertThat(authHeaders.get(0)).isEqualTo("Basic");
        assertThat(creds.toString()).doesNotContain("token").doesNotContain("key=");
    }

    @Test
    void followsARegionRedirectOnce() throws Exception {
        int[] calls = {0};
        responder = path -> ++calls[0] == 1
                ? Map.of("apiStatus", 1030, "info", "Bad domain", "data", Map.of("domain", baseUrl + "/eu"))
                : ok(Map.of("userid", "1", "key", "k", "token", "t", "domain", baseUrl + "/eu", "mqttDomain", "mqtt-eu.meross.com"));

        MerossCredentials creds = api().login("shir@example.com", "hunter2");

        assertThat(creds.apiBaseUrl()).isEqualTo(baseUrl + "/eu");
        assertThat(requests).hasSize(2);
    }

    @Test
    void doesntFollowASecondRedirect() {
        responder = path -> Map.of("apiStatus", 1030, "info", "Bad domain", "data", Map.of("domain", baseUrl));

        assertKind(() -> api().login("shir@example.com", "hunter2"), Kind.PROTOCOL);
        assertThat(requests).hasSize(2);
    }

    @Test
    void aRefusedSignInIsAnAuthFailure() {
        responder = path -> Map.of("apiStatus", 1004, "info", "Wrong email or password", "data", Map.of());

        assertThatThrownBy(() -> api().login("shir@example.com", "nope"))
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(Kind.AUTH))
                .hasMessageContaining("1004")
                .hasMessageContaining("Wrong email or password");
    }

    @Test
    void twoFactorIsAnAuthFailure() {
        responder = path -> Map.of("apiStatus", 1033, "info", "MFA code required", "data", Map.of());

        assertThatThrownBy(() -> api().login("shir@example.com", "hunter2"))
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(Kind.AUTH))
                .hasMessageContaining("two-factor");
    }

    @Test
    void refusesWithoutCredentialsAndSendsNothing() {
        assertKind(() -> api().login(null, null), Kind.AUTH);
        assertThat(requests).isEmpty();
    }

    @Test
    void aReplyMissingTheKeyIsAProtocolError() {
        responder = path -> ok(Map.of("userid", "1", "token", "t", "mqttDomain", "mqtt.example"));

        assertKind(() -> api().login("shir@example.com", "hunter2"), Kind.PROTOCOL);
    }

    @Test
    void anHttpErrorMeansUnavailable() {
        responder = path -> new Raw(503, "", 0);

        assertKind(() -> api().login("shir@example.com", "hunter2"), Kind.UNAVAILABLE);
    }

    @Test
    void notJsonIsAProtocolError() {
        responder = path -> new Raw(200, "<html>maintenance</html>", 0);

        assertKind(() -> api().login("shir@example.com", "hunter2"), Kind.PROTOCOL);
    }

    @Test
    void aSlowServerTimesOut() {
        responder = path -> new Raw(200, "{}", 1500);

        assertKind(() -> new MerossCloudApi(baseUrl, Duration.ofMillis(300)).login("shir@example.com", "hunter2"), Kind.TIMEOUT);
    }

    @Test
    void anUnreachableServerIsUnavailable() {
        // Nothing listens on port 1.
        assertKind(() -> new MerossCloudApi("http://127.0.0.1:1", Duration.ofSeconds(2)).login("shir@example.com", "hunter2"),
                Kind.UNAVAILABLE);
    }

    @Test
    void listsDevicesWithTheToken() throws Exception {
        responder = path -> Map.of("apiStatus", 0, "data", List.of(
                Map.of("uuid", "door-1", "devName", "Garage", "deviceType", "msg100", "onlineStatus", 1),
                Map.of("uuid", "plug-1", "devName", "Lamp", "deviceType", "mss310", "onlineStatus", 2)));

        List<MerossDeviceInfo> devices = api().listDevices(new MerossCredentials("1", "k", "tok", baseUrl, "mqtt"));

        assertThat(devices).containsExactly(
                new MerossDeviceInfo("door-1", "Garage", "msg100", true),
                new MerossDeviceInfo("plug-1", "Lamp", "mss310", false));
        assertThat(authHeaders.get(0)).isEqualTo("Basic tok");
    }

    @Test
    void aRefusedDeviceListIsAnAuthFailure() {
        responder = path -> Map.of("apiStatus", 1022, "info", "Token expired", "data", Map.of());

        assertKind(() -> api().listDevices(new MerossCredentials("1", "k", "tok", baseUrl, "mqtt")), Kind.AUTH);
    }

    private MerossCloudApi api() {
        return new MerossCloudApi(baseUrl, Duration.ofSeconds(5));
    }

    private static Map<String, Object> ok(Map<String, Object> data) {
        return Map.of("apiStatus", 0, "data", data);
    }

    private static void assertKind(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Kind kind) {
        assertThatThrownBy(call).isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
