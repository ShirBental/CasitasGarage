package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import home.casita.garage.DoorException;
import tools.jackson.databind.json.JsonMapper;

/** Runs the HTTP client against a local fake of Meross's API that checks every signature. */
class MerossCloudApiTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private String baseUrl;
    private final List<Map<String, Object>> requests = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();
    private Function<String, Map<String, Object>> responder;

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
    }

    @SuppressWarnings("unchecked")
    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            Map<String, Object> body = json.readValue(ex.getRequestBody().readAllBytes(), Map.class);
            String params = (String) body.get("params");
            String expectedSign = MerossCrypto.httpSign(((Number) body.get("timestamp")).longValue(),
                    (String) body.get("nonce"), params);
            assertThat(body.get("sign")).isEqualTo(expectedSign);
            assertThat(ex.getRequestHeaders().getFirst("vender")).isEqualTo("meross");
            authHeaders.add(ex.getRequestHeaders().getFirst("Authorization"));
            requests.add(json.readValue(Base64.getDecoder().decode(params), Map.class));

            byte[] out = json.writeValueAsBytes(responder.apply(ex.getRequestURI().getPath()));
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        }
    }

    @Test
    void signsInWithAHashedPassword() throws Exception {
        responder = path -> Map.of("apiStatus", 0, "data", Map.of(
                "userid", "123456", "email", "shir@example.com", "key", "k", "token", "t",
                "domain", baseUrl, "mqttDomain", "mqtt-us-4.meross.com"));

        MerossCredentials creds = api().login("shir@example.com", "hunter2");

        assertThat(creds.userId()).isEqualTo("123456");
        assertThat(creds.mqttHost()).isEqualTo("mqtt-us-4.meross.com");
        assertThat(requests.get(0))
                .containsEntry("email", "shir@example.com")
                .containsEntry("password", MerossCrypto.md5Hex("hunter2"))
                .containsEntry("encryption", 1);
        assertThat(authHeaders.get(0)).isEqualTo("Basic");
        assertThat(creds.toString()).doesNotContain("k,").doesNotContain("token=");
    }

    @Test
    void followsARegionRedirectOnce() throws Exception {
        String[] calls = {""};
        responder = path -> {
            calls[0] += "x";
            return calls[0].length() == 1
                    ? Map.of("apiStatus", 1030, "info", "Bad domain", "data", Map.of("domain", baseUrl + "/eu"))
                    : Map.of("apiStatus", 0, "data", Map.of("userid", "1", "key", "k", "token", "t",
                            "domain", baseUrl + "/eu", "mqttDomain", "mqtt-eu.meross.com"));
        };

        MerossCredentials creds = api().login("shir@example.com", "hunter2");

        assertThat(creds.apiBaseUrl()).isEqualTo(baseUrl + "/eu");
        assertThat(requests).hasSize(2);
    }

    @Test
    void explainsAFailedSignIn() {
        responder = path -> Map.of("apiStatus", 1004, "info", "Wrong email or password", "data", Map.of());

        assertThatThrownBy(() -> api().login("shir@example.com", "nope"))
                .isInstanceOf(DoorException.class)
                .hasMessageContaining("1004")
                .hasMessageContaining("Wrong email or password");
    }

    @Test
    void refusesWithoutCredentials() {
        assertThatThrownBy(() -> api().login(null, null))
                .isInstanceOf(DoorException.class)
                .hasMessageContaining("aren't configured");
        assertThat(requests).isEmpty();
    }

    @Test
    void listsDevicesWithTheToken() throws Exception {
        responder = path -> Map.of("apiStatus", 0, "data", List.of(
                Map.of("uuid", "door-1", "devName", "Garage", "deviceType", "msg100", "onlineStatus", 1),
                Map.of("uuid", "plug-1", "devName", "Lamp", "deviceType", "mss310", "onlineStatus", 2)));
        MerossCredentials creds = new MerossCredentials("1", "k", "tok", baseUrl, "mqtt");

        List<MerossDeviceInfo> devices = api().listDevices(creds);

        assertThat(devices).containsExactly(
                new MerossDeviceInfo("door-1", "Garage", "msg100", true),
                new MerossDeviceInfo("plug-1", "Lamp", "mss310", false));
        assertThat(authHeaders.get(0)).isEqualTo("Basic tok");
    }

    private MerossCloudApi api() {
        return new MerossCloudApi(baseUrl, Duration.ofSeconds(5));
    }
}
