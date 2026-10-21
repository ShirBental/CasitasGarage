package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import home.casita.garage.DoorCommandRejectedException;
import home.casita.meross.MerossException.Kind;
import tools.jackson.databind.json.JsonMapper;

/**
 * The home-network transport against a fake MSG200 on 127.0.0.1 that answers like the real one: one POST /config per
 * message, a reply signed with the account key carrying the request's message id. Ford is channel 1, Toyota channel 2.
 */
class MerossLocalTransportTest {

    private static final String KEY = "account-key";
    private static final String UUID = "msg200-uuid-0001";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC);

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Map<String, Object>> received = Collections.synchronizedList(new ArrayList<>());
    private HttpServer server;
    private volatile int fordOpen = 0, toyotaOpen = 0;
    private volatile String misbehave = "";
    private FakeApi api;

    @BeforeEach
    void startOpener() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/config", exchange -> {
            Map<String, Object> request = MerossCloudApi.map(json.readValue(exchange.getRequestBody().readAllBytes(), Map.class));
            received.add(request);
            if (misbehave.equals("slow")) {
                try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            int status = misbehave.equals("http500") ? 500 : 200;
            byte[] body = json.writeValueAsBytes(answer(request));
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        api = new FakeApi();
    }

    @AfterEach
    void stopOpener() {
        server.stop(0);
    }

    private Map<String, Object> answer(Map<String, Object> request) {
        Map<String, Object> rq = MerossCloudApi.map(request.get("header"));
        String method = String.valueOf(rq.get("method"));
        String messageId = misbehave.equals("otherMessage") ? "someone-elses-message" : String.valueOf(rq.get("messageId"));
        long ts = 1_790_000_000L;
        Map<String, Object> payload;
        if (misbehave.equals("error")) {
            method = "ERROR";
            payload = Map.of("error", Map.of("code", 5000));
        } else if (method.equals("GET")) {
            method = "GETACK";
            payload = Map.of("all", Map.of("digest", Map.of("garageDoor", List.of(
                    Map.of("channel", 1, "open", fordOpen, "doorEnable", 1),
                    Map.of("channel", 2, "open", toyotaOpen, "doorEnable", 1),
                    Map.of("channel", 3, "open", 1, "doorEnable", 0)))));
        } else {
            method = "SETACK";
            Map<String, Object> state = MerossCloudApi.map(MerossCloudApi.map(request.get("payload")).get("state"));
            payload = Map.of("state", Map.of("channel", state.get("channel"), "execute", 1, "open", 1 - (int) state.get("open")));
        }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("messageId", messageId);
        header.put("method", method);
        header.put("namespace", rq.get("namespace"));
        header.put("timestamp", ts);
        header.put("sign", MerossCrypto.messageSign(messageId, misbehave.equals("unsigned") ? "not-the-key" : KEY, ts));
        return Map.of("header", header, "payload", payload);
    }

    private URI opener() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private MerossLocalTransport transport(URI url, Duration timeout) {
        return new MerossLocalTransport(api, "family@example.com", "secret", url, timeout, CLOCK,
                HttpClient.newBuilder().connectTimeout(timeout).proxy(HttpClient.Builder.NO_PROXY).build());
    }

    private MerossDoor door(MerossLocalTransport t, int channel) {
        return new MerossDoor(t, UUID, channel, CommandMode.LIVE, Set.of(1, 2));
    }

    private List<Map<String, Object>> sets() {
        return received.stream().filter(r -> "SET".equals(MerossCloudApi.map(r.get("header")).get("method"))).toList();
    }

    private static Object channelOf(Map<String, Object> set) {
        return MerossCloudApi.map(MerossCloudApi.map(set.get("payload")).get("state")).get("channel");
    }

    /* ---------- reading and commanding both doors ---------- */

    @Test
    void readsFordAndToyotaFromTheirOwnChannels() throws Exception {
        fordOpen = 1;
        MerossLocalTransport t = transport(opener(), Duration.ofSeconds(2));
        assertThat(door(t, 1).read().open()).isTrue();
        assertThat(door(t, 2).read().open()).isFalse();
        Map<String, Object> header = MerossCloudApi.map(received.get(0).get("header"));
        assertThat(header).containsEntry("method", "GET").containsEntry("namespace", "Appliance.System.All").containsEntry("uuid", UUID);
        assertThat(MerossMessages.signedWith(header, KEY)).isTrue();
        assertThat(MerossCloudApi.map(received.get(0).get("payload"))).containsOnlyKeys("all");
        assertThat(api.logins).isEqualTo(1);                // signed in once, for the key; then only the opener
    }

    @Test
    void eachDoorsCommandIsOneSignedSetToItsOwnChannel() throws Exception {
        MerossLocalTransport t = transport(opener(), Duration.ofSeconds(2));
        door(t, 1).command(true);
        door(t, 2).command(true);
        assertThat(sets()).hasSize(2);
        assertThat(sets()).extracting(MerossLocalTransportTest::channelOf).containsExactly(1, 2);
        for (Map<String, Object> set : sets()) {
            Map<String, Object> header = MerossCloudApi.map(set.get("header"));
            assertThat(header).containsEntry("namespace", "Appliance.GarageDoor.State");
            assertThat(MerossMessages.signedWith(header, KEY)).isTrue();
            assertThat(MerossCloudApi.map(MerossCloudApi.map(set.get("payload")).get("state"))).containsEntry("open", 1);
        }
    }

    @Test
    void dryRunBuildsAndSignsButSendsNothing() throws Exception {
        MerossLocalTransport t = transport(opener(), Duration.ofSeconds(2));
        MerossDoor ford = new MerossDoor(t, UUID, 1, CommandMode.DRY_RUN, Set.of(1, 2));
        assertThatThrownBy(() -> ford.command(true)).isInstanceOf(DoorCommandRejectedException.class).hasMessageContaining("Dry run");
        assertThat(sets()).isEmpty();
    }

    /* ---------- failures: rejected (nothing sent) or unknown (checked later), never retried ---------- */

    @Test
    void noConnectionRejectsTheCommandBecauseNothingWasSent() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        MerossLocalTransport t = transport(URI.create("http://127.0.0.1:" + closedPort), Duration.ofSeconds(2));
        assertThatThrownBy(() -> t.setOnce(UUID, MerossDoor.NS_GARAGE_DOOR, Map.of("state", Map.of("channel", 1, "open", 1))))
                .isInstanceOf(DoorCommandRejectedException.class);
        assertThatThrownBy(() -> t.get(UUID, MerossDoor.NS_SYSTEM_ALL))
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
    }

    @Test
    void noAnswerIsAnUnknownOutcomeAndTheCommandIsSentExactlyOnce() {
        misbehave = "slow";
        MerossLocalTransport t = transport(opener(), Duration.ofMillis(400));
        assertThatThrownBy(() -> t.setOnce(UUID, MerossDoor.NS_GARAGE_DOOR, Map.of("state", Map.of("channel", 2, "open", 1))))
                .isNotInstanceOf(DoorCommandRejectedException.class)
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TIMEOUT));
        assertThat(sets()).hasSize(1);
    }

    @Test
    void anUnsignedAnswerIsRefused() {
        misbehave = "unsigned";
        MerossLocalTransport t = transport(opener(), Duration.ofSeconds(2));
        assertThatThrownBy(() -> t.get(UUID, MerossDoor.NS_SYSTEM_ALL))
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(Kind.PROTOCOL));
        assertThatThrownBy(() -> t.setOnce(UUID, MerossDoor.NS_GARAGE_DOOR, Map.of("state", Map.of("channel", 1, "open", 1))))
                .isNotInstanceOf(DoorCommandRejectedException.class).hasMessageContaining("outcome is unknown");
        assertThat(sets()).hasSize(1);
    }

    @Test
    void anAnswerToAnotherMessageIsRefused() {
        misbehave = "otherMessage";
        MerossLocalTransport t = transport(opener(), Duration.ofSeconds(2));
        assertThatThrownBy(() -> t.get(UUID, MerossDoor.NS_SYSTEM_ALL)).hasMessageContaining("another message");
    }

    @Test
    void httpErrorsAndDeviceErrorsAreNotAcknowledgements() {
        misbehave = "http500";
        MerossLocalTransport t = transport(opener(), Duration.ofSeconds(2));
        assertThatThrownBy(() -> t.setOnce(UUID, MerossDoor.NS_GARAGE_DOOR, Map.of("state", Map.of("channel", 1, "open", 1))))
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(Kind.PROTOCOL));
        misbehave = "error";
        assertThatThrownBy(() -> t.setOnce(UUID, MerossDoor.NS_GARAGE_DOOR, Map.of("state", Map.of("channel", 1, "open", 1))))
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(Kind.DEVICE_ERROR));
        assertThat(sets()).hasSize(2);                     // one per call, never a retry
    }

    @Test
    void aRefusedSignInRejectsTheCommandBeforeAnythingIsSent() {
        api.loginFailure = new MerossException(Kind.AUTH, "Wrong password");
        MerossLocalTransport t = transport(opener(), Duration.ofSeconds(2));
        assertThatThrownBy(() -> t.setOnce(UUID, MerossDoor.NS_GARAGE_DOOR, Map.of("state", Map.of("channel", 1, "open", 1))))
                .isInstanceOf(DoorCommandRejectedException.class);
        assertThat(received).isEmpty();
    }

    /* ---------- the address ---------- */

    @Test
    void onlyPlainHttpToAPrivateIpv4AddressIsAccepted() {
        for (String ok : List.of("http://192.168.1.50", "http://10.0.0.7:80", "http://172.16.4.2/", "http://172.31.255.1")) {
            MerossLinkProperties.checkLocalUrl(URI.create(ok));
        }
        for (String bad : List.of("https://192.168.1.50", "http://127.0.0.1", "http://8.8.8.8", "http://172.32.0.1",
                "http://opener.lan", "http://user@192.168.1.50", "http://192.168.1.50/config", "http://192.168.1.50?x=1",
                "http://100.64.0.1")) {
            assertThatThrownBy(() -> MerossLinkProperties.checkLocalUrl(URI.create(bad))).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new MerossLinkProperties(MerossLinkProperties.Transport.LOCAL, null))
                .hasMessageContaining("local-url");
        assertThat(new MerossLinkProperties(null, null).transport()).isEqualTo(MerossLinkProperties.Transport.CLOUD);
        assertThatThrownBy(() -> new MerossLocalTransport(api, "e", "p", URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1), CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static final class FakeApi extends MerossCloudApi {
        int logins;
        MerossException loginFailure;

        FakeApi() {
            super("http://unused.invalid", Duration.ofSeconds(1));
        }

        @Override
        MerossCredentials login(String email, String password) throws MerossException {
            logins++;
            if (loginFailure != null) throw loginFailure;
            return new MerossCredentials("user-1", KEY, "token", "https://iotx-us.meross.com", "mqtt-us.meross.com");
        }

        @Override
        List<MerossDeviceInfo> listDevices(MerossCredentials creds) {
            return List.of(new MerossDeviceInfo(UUID, "Garage", "msg200", true));
        }
    }
}
