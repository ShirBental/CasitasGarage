package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import home.casita.garage.DoorCommandRejectedException;
import home.casita.meross.MerossException.Kind;
import tools.jackson.databind.json.JsonMapper;

/**
 * The session lifecycle with a fake Meross HTTP API and a fake MQTT connection. These show the
 * session's own logic; the HiveMQ adapter and real Meross behavior are not covered here.
 */
class MerossSessionTest {

    private static final String EMAIL = "shir@example.com";
    private static final String PASSWORD = "hunter2-secret";
    private static final String USER_ID = "424242";
    private static final String KEY = "KEY-very-secret";
    private static final String TOKEN = "TOKEN-very-secret";
    private static final String DOOR = "door-1";
    private static final String NS = MerossDoor.NS_GARAGE_DOOR;
    private static final Duration TIMEOUT = Duration.ofMillis(300);

    private final JsonMapper json = JsonMapper.builder().build();
    private FakeApi api;
    private FakeConnector connector;
    private MutableClock clock;
    private MerossSession session;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        api = new FakeApi();
        connector = new FakeConnector();
        clock = new MutableClock(Instant.parse("2026-10-15T22:00:00Z"));
        session = new MerossSession(api, connector,
                new MerossProperties("https://iotx-us.meross.com", EMAIL, PASSWORD, null, 0, TIMEOUT, CommandMode.OFF, Map.of(), java.util.Set.of()),
                clock, Runnable::run);
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger("home.casita.meross")).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger("home.casita.meross")).detachAppender(logs);
        session.destroy();
    }

    /* ---------- sign-in, connection, subscription ---------- */

    @Test
    void theFirstReadSignsInConnectsAndSubscribes() throws Exception {
        connector.nextResponder = door(Map.of("state", List.of(Map.of("channel", 0, "open", 0))));

        Map<String, Object> payload = session.get(DOOR, NS);

        assertThat(payload).containsKey("state");
        assertThat(api.logins).isEqualTo(1);
        FakeConnection conn = connector.only();
        assertThat(conn.settings.host()).isEqualTo("mqtt-us-4.meross.com");
        assertThat(conn.settings.port()).isEqualTo(443);
        assertThat(conn.settings.clientId()).matches("app:[0-9a-f]{32}");
        assertThat(conn.settings.username()).isEqualTo(USER_ID);
        assertThat(new String(conn.settings.password(), StandardCharsets.UTF_8)).isEqualTo(MerossCrypto.mqttPassword(USER_ID, KEY));
        String appId = conn.settings.clientId().substring("app:".length());
        assertThat(conn.subscriptions).containsOnlyKeys("/app/" + USER_ID + "-" + appId + "/subscribe", "/app/" + USER_ID + "/subscribe");
    }

    @Test
    void readsAreSignedGetsWithQos1ToTheDevicesTopic() throws Exception {
        connector.nextResponder = door(Map.of("state", List.of()));

        session.get(DOOR, NS);

        Published p = connector.only().published.get(0);
        assertThat(p.topic).isEqualTo("/appliance/" + DOOR + "/subscribe");
        assertThat(p.atMostOnce).isFalse();
        Map<String, Object> header = MerossCloudApi.map(p.message.get("header"));
        assertThat(header).containsEntry("method", "GET").containsEntry("namespace", NS)
                .containsEntry("uuid", DOOR).containsEntry("payloadVersion", 1);
        assertThat(((Number) header.get("timestamp")).longValue()).isEqualTo(clock.instant().getEpochSecond());
        assertThat(String.valueOf(header.get("from"))).startsWith("/app/" + USER_ID + "-");
        assertThat(header.get("sign")).isEqualTo(MerossCrypto.messageSign(
                String.valueOf(header.get("messageId")), KEY, clock.instant().getEpochSecond()));
    }

    @Test
    void reusesTheSignInAndConnection() throws Exception {
        connector.nextResponder = door(Map.of("state", List.of()));

        session.get(DOOR, NS);
        session.get(DOOR, NS);

        assertThat(api.logins).isEqualTo(1);
        assertThat(connector.created).hasSize(1);
        assertThat(connector.only().published).hasSize(2);
    }

    /* ---------- replies ---------- */

    @Test
    void aReplyWithABadSignatureIsIgnored() {
        connector.nextResponder = request -> {
            Map<String, Object> reply = signedReply(request, "GETACK", Map.of("state", List.of()));
            MerossCloudApi.map(reply.get("header")).put("sign", "0".repeat(32));
            return reply;
        };

        assertKind(() -> session.get(DOOR, NS), Kind.TIMEOUT);
        assertThat(logText()).contains("reason=signature_bad")
                .contains("outcome=timeout").contains("published=yes acked=yes reply=none received_meanwhile={bad_signature=1}");
    }

    /* ---------- dry run ---------- */

    @Test
    void aDryRunBuildsAndSignsTheSetButPublishesNothing() throws Exception {
        connector.nextResponder = door(Map.of("state", List.of()));
        session.get(DOOR, NS); // connected
        int before = connector.only().published.size();

        String built = session.dryRunSet(DOOR, NS, Map.of("state", Map.of("channel", 1, "open", 1, "uuid", DOOR)));

        assertThat(connector.only().published).hasSize(before);
        assertThat(built).contains("SET " + NS).contains("payload keys [state]").contains("signed").doesNotContain(KEY);
        assertThat(logText()).contains("event=command outcome=dry_run namespace=" + NS).doesNotContain(KEY);
    }

    @Test
    void aDryRunNeedsAConnectedSessionLikeARealCommand() {
        assertThatThrownBy(() -> session.dryRunSet(DOOR, NS, Map.of("state", Map.of())))
                .isInstanceOf(DoorCommandRejectedException.class);
        assertThat(connector.created).isEmpty(); // it never signs in or connects on the spot
    }

    /* ---------- diagnosing a read that times out ---------- */

    @Test
    void aSilentDeviceTimesOutAndTheLogSaysThePublishWasAcknowledged() {
        connector.nextResponder = request -> null;

        assertKind(() -> session.get(DOOR, NS), Kind.TIMEOUT);

        assertThat(logText()).contains("event=read outcome=timeout namespace=" + NS)
                .contains("published=yes acked=yes reply=none received_meanwhile={}");
    }

    @Test
    void aPublishTheBrokerNeverAcknowledgesIsReportedAsSuch() throws Exception {
        connector.nextResponder = door(Map.of("state", List.of()));
        session.get(DOOR, NS); // connected
        connector.only().publishMode = PublishMode.NEVER_COMPLETES;

        assertKind(() -> session.get(DOOR, NS), Kind.TIMEOUT);

        assertThat(logText()).contains("published=yes acked=no reply=none (the broker never acknowledged the publish)");
    }

    @Test
    void aReplyForAnotherRequestIsReportedUnmatched() {
        connector.nextResponder = request -> {
            Map<String, Object> reply = signedReply(request, "GETACK", Map.of("state", List.of()));
            Map<String, Object> header = MerossCloudApi.map(reply.get("header"));
            String other = "f".repeat(32);
            long timestamp = ((Number) header.get("timestamp")).longValue();
            header.put("messageId", other);
            header.put("sign", MerossCrypto.messageSign(other, KEY, timestamp));
            return reply;
        };

        assertKind(() -> session.get(DOOR, NS), Kind.TIMEOUT);

        assertThat(logText()).contains("event=reply_ignored topic=REPLY correlation=unmatched method=GETACK namespace=" + NS + " mid=ffffffff")
                .contains("received_meanwhile={unmatched=1}");
    }

    @Test
    void aReplyThatArrivesAfterTheTimeoutIsRecognisedAsLate() {
        connector.nextResponder = request -> null;
        assertKind(() -> session.get(DOOR, NS), Kind.TIMEOUT);
        FakeConnection conn = connector.only();
        Map<String, Object> request = conn.published.get(0).message();
        String from = String.valueOf(MerossCloudApi.map(request.get("header")).get("from"));

        conn.subscriptions.get(from).accept(json.writeValueAsBytes(signedReply(request, "GETACK", Map.of("state", List.of()))));

        String mid = MerossSession.shortId(String.valueOf(MerossCloudApi.map(request.get("header")).get("messageId")));
        assertThat(logText()).contains("correlation=late method=GETACK namespace=" + NS + " mid=" + mid);
    }

    @Test
    void aReplyOnThePushTopicIsTracedButNotUsed() {
        connector.nextResponder = request -> {
            FakeConnection conn = connector.created.get(0);
            Consumer<byte[]> push = conn.subscriptions.get("/app/" + USER_ID + "/subscribe");
            push.accept(json.writeValueAsBytes(signedReply(request, "GETACK", Map.of("state", List.of()))));
            return null;
        };

        assertKind(() -> session.get(DOOR, NS), Kind.TIMEOUT);

        assertThat(logText()).contains("event=message_on_push_topic correlation=pending_request method=GETACK namespace=" + NS)
                .contains("received_meanwhile={push=1}");
    }

    @Test
    void unreadableMessagesAreDroppedAndCounted() {
        connector.nextResponder = request -> {
            Consumer<byte[]> reply = connector.created.get(0).subscriptions.get(String.valueOf(MerossCloudApi.map(request.get("header")).get("from")));
            reply.accept("not json".getBytes(StandardCharsets.UTF_8));
            return null;
        };

        assertKind(() -> session.get(DOOR, NS), Kind.TIMEOUT);

        assertThat(logText()).contains("reason=unreadable").contains("received_meanwhile={unreadable=1}");
    }

    @Test
    void theDebugTraceFollowsOneExchangeWithoutSecretsUuidsOrPayloadValues() throws Exception {
        Logger sessionLog = (Logger) LoggerFactory.getLogger(MerossSession.class);
        ch.qos.logback.classic.Level previous = sessionLog.getLevel();
        sessionLog.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            String device = "1912089283245425180234298f1a2b3c";
            connector.nextResponder = door(Map.of("state", List.of(Map.of("channel", 1, "open", 0, "note", "SENTINEL-VALUE"))));

            session.get(device, NS);

            String mid = MerossSession.shortId(String.valueOf(MerossCloudApi.map(connector.only().published.get(0).message().get("header")).get("messageId")));
            assertThat(logText())
                    .contains("event=request_published method=GET namespace=" + NS + " mid=" + mid + " qos=1 payload_keys=[]")
                    .contains("event=publish_acked method=GET namespace=" + NS + " mid=" + mid)
                    .contains("event=reply topic=REPLY correlation=matched method=GETACK namespace=" + NS + " mid=" + mid + " sign=ok")
                    .doesNotContain(KEY).doesNotContain(TOKEN).doesNotContain(PASSWORD).doesNotContain(EMAIL)
                    .doesNotContain(device).doesNotContain("/app/" + USER_ID).doesNotContain("SENTINEL-VALUE");
        } finally {
            sessionLog.setLevel(previous);
        }
    }

    @Test
    void anErrorReplyIsReportedByCodeNotByPayload() {
        connector.nextResponder = request -> signedReply(request, "ERROR", Map.of("error", Map.of("code", 5000, "detail", "SENTINEL-DETAIL")));

        assertThatThrownBy(() -> session.get(DOOR, NS)).hasMessageContaining("error code 5000").hasMessageNotContaining("SENTINEL");
        assertThat(logText()).contains("error_code=5000").doesNotContain("SENTINEL");
    }

    @Test
    void aDeviceErrorIsClassified() {
        connector.nextResponder = request -> signedReply(request, "ERROR", Map.of("error", Map.of("code", 5000)));

        assertKind(() -> session.get(DOOR, NS), Kind.DEVICE_ERROR);
    }

    @Test
    void noReplyIsATimeout() {
        connector.nextResponder = request -> null;

        assertKind(() -> session.get(DOOR, NS), Kind.TIMEOUT);
    }

    /* ---------- failures and backoff ---------- */

    @Test
    void aRefusedSignInBacksOffWithoutHammeringMeross() throws Exception {
        api.loginFailure = new MerossException(Kind.AUTH, "Meross sign-in refused (apiStatus 1004): Wrong password");

        assertKind(() -> session.get(DOOR, NS), Kind.AUTH);
        assertKind(() -> session.get(DOOR, NS), Kind.UNAVAILABLE); // waiting out the backoff
        assertThat(api.logins).isEqualTo(1);

        clock.advance(MerossSession.AUTH_BACKOFF);
        api.loginFailure = null;
        connector.nextResponder = door(Map.of("state", List.of()));
        assertThat(session.devices()).isEmpty();
        assertThat(api.logins).isEqualTo(2);
    }

    @Test
    void networkFailuresBackOffExponentially() {
        connector.nextConnect = () -> CompletableFuture.failedFuture(new IOException("connection refused"));

        assertKind(() -> session.get(DOOR, NS), Kind.UNAVAILABLE);
        assertThat(connector.created).hasSize(1);
        assertThat(connector.only().closed).isTrue();

        clock.advance(Duration.ofSeconds(4));
        assertKind(() -> session.get(DOOR, NS), Kind.UNAVAILABLE);
        assertThat(connector.created).hasSize(1); // still backing off

        clock.advance(Duration.ofSeconds(1)); // 5 s
        assertKind(() -> session.get(DOOR, NS), Kind.UNAVAILABLE);
        assertThat(connector.created).hasSize(2);

        clock.advance(Duration.ofSeconds(5)); // second backoff is 10 s
        assertKind(() -> session.get(DOOR, NS), Kind.UNAVAILABLE);
        assertThat(connector.created).hasSize(2);

        clock.advance(Duration.ofSeconds(5));
        connector.nextConnect = () -> CompletableFuture.completedFuture(null);
        connector.nextResponder = door(Map.of("state", List.of()));
        assertThat(sessionGet()).isNotNull();
        assertThat(connector.created).hasSize(3);
        assertThat(api.logins).isEqualTo(1); // the sign-in was fine all along
    }

    @Test
    void backoffIsCapped() {
        assertThat(MerossSession.backoff(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(MerossSession.backoff(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(MerossSession.backoff(20)).isEqualTo(MerossSession.MAX_BACKOFF);
    }

    @Test
    void aConnectTimeoutClosesTheConnection() {
        connector.nextConnect = CompletableFuture::new; // never completes

        assertKind(() -> session.get(DOOR, NS), Kind.TIMEOUT);
        assertThat(connector.only().closed).isTrue();
    }

    @Test
    void theBrokerRefusingCredentialsForcesANewSignIn() {
        connector.nextConnect = () -> CompletableFuture.failedFuture(new MqttConnection.AuthRejected("bad password"));

        assertKind(() -> session.get(DOOR, NS), Kind.AUTH);

        clock.advance(MerossSession.AUTH_BACKOFF);
        connector.nextConnect = () -> CompletableFuture.completedFuture(null);
        connector.nextResponder = door(Map.of("state", List.of()));
        assertThat(sessionGet()).isNotNull();
        assertThat(api.logins).isEqualTo(2);
    }

    @Test
    void aRefusedDeviceListDropsTheSignIn() throws Exception {
        api.devicesFailure = new MerossException(Kind.AUTH, "Meross device list refused (apiStatus 1022): Token expired");

        assertKind(() -> session.devices(), Kind.AUTH);

        clock.advance(MerossSession.AUTH_BACKOFF);
        api.devicesFailure = null;
        assertThat(session.devices()).isEmpty();
        assertThat(api.logins).isEqualTo(2);
    }

    /* ---------- reconnection ---------- */

    @Test
    void aNetworkDropKeepsTheSignInAndRecoversWhenTheClientReconnects() throws Exception {
        connector.nextResponder = door(Map.of("state", List.of()));
        session.get(DOOR, NS);
        FakeConnection conn = connector.only();

        conn.drop(false);
        assertKind(() -> session.get(DOOR, NS), Kind.UNAVAILABLE);

        conn.reconnect(); // the MQTT client reconnects and restores the subscription itself
        session.get(DOOR, NS);
        assertThat(api.logins).isEqualTo(1);
        assertThat(connector.created).hasSize(1);
        assertThat(conn.closed).isFalse();
    }

    @Test
    void credentialsRefusedOnReconnectForceANewSignIn() throws Exception {
        connector.nextResponder = door(Map.of("state", List.of()));
        session.get(DOOR, NS);
        FakeConnection first = connector.only();

        first.drop(true);
        assertThat(first.closed).isTrue();

        clock.advance(MerossSession.AUTH_BACKOFF);
        session.get(DOOR, NS);
        assertThat(api.logins).isEqualTo(2);
        assertThat(connector.created).hasSize(2);
    }

    /* ---------- commands (the door's command switch is tested in MerossDoorTest) ---------- */

    @Test
    void aCommandWithoutALiveConnectionIsRejectedAndNothingIsSent() {
        assertThatThrownBy(() -> session.setOnce(DOOR, NS, openPayload())).isInstanceOf(DoorCommandRejectedException.class);
        assertThat(api.logins).isZero(); // doesn't sign in on the spot to send a command
        assertThat(connector.created).isEmpty();
    }

    @Test
    void anAcknowledgedCommandIsSentOnceWithQos0() throws Exception {
        connectWithAReadFirst();
        FakeConnection conn = connector.only();
        conn.responder = request -> signedReply(request, "SETACK", Map.of());

        session.setOnce(DOOR, NS, openPayload());

        List<Published> sets = conn.sets();
        assertThat(sets).hasSize(1);
        assertThat(sets.get(0).atMostOnce).isTrue();
        assertThat(logText()).contains("event=command outcome=acknowledged");
    }

    @Test
    void aCommandWithNoReplyIsUnknownAndNotRetried() throws Exception {
        connectWithAReadFirst();
        FakeConnection conn = connector.only();
        conn.responder = request -> null;

        assertThatThrownBy(() -> session.setOnce(DOOR, NS, openPayload()))
                .isNotInstanceOf(DoorCommandRejectedException.class)
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TIMEOUT));
        assertThat(conn.sets()).hasSize(1);
    }

    @Test
    void aCommandTheClientNeverConfirmsSendingIsUnknownAndItsConnectionIsDropped() throws Exception {
        connectWithAReadFirst();
        FakeConnection conn = connector.only();
        conn.publishMode = PublishMode.NEVER_COMPLETES;

        assertThatThrownBy(() -> session.setOnce(DOOR, NS, openPayload()))
                .isNotInstanceOf(DoorCommandRejectedException.class)
                .isInstanceOf(MerossException.class);
        assertThat(conn.closed).as("dropped so a queued command can't go out later").isTrue();
        assertThat(conn.sets()).hasSize(1);
    }

    @Test
    void aCommandWhosePublishFailsIsUnknown() throws Exception {
        connectWithAReadFirst();
        FakeConnection conn = connector.only();
        conn.publishMode = PublishMode.FAILS;

        assertThatThrownBy(() -> session.setOnce(DOOR, NS, openPayload()))
                .isNotInstanceOf(DoorCommandRejectedException.class)
                .isInstanceOf(MerossException.class);
        assertThat(conn.closed).isTrue();
    }

    @Test
    void aCommandTheClientRefusesOutrightIsRejected() throws Exception {
        connectWithAReadFirst();
        FakeConnection conn = connector.only();
        conn.publishMode = PublishMode.THROWS;

        assertThatThrownBy(() -> session.setOnce(DOOR, NS, openPayload())).isInstanceOf(DoorCommandRejectedException.class);
    }

    @Test
    void anErrorReplyToACommandIsNotARejection() throws Exception {
        connectWithAReadFirst();
        connector.only().responder = request -> signedReply(request, "ERROR", Map.of());

        // The device answered: whether it acted is unknown, so it must not read as "nothing was sent".
        assertThatThrownBy(() -> session.setOnce(DOOR, NS, openPayload()))
                .isNotInstanceOf(DoorCommandRejectedException.class)
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(Kind.DEVICE_ERROR));
    }

    /* ---------- logging ---------- */

    @Test
    void logsNeverContainCredentials() throws Exception {
        api.loginFailure = new MerossException(Kind.AUTH, "Meross sign-in refused (apiStatus 1004): Wrong password");
        assertKind(() -> session.get(DOOR, NS), Kind.AUTH);
        clock.advance(MerossSession.AUTH_BACKOFF);
        api.loginFailure = null;
        connectWithAReadFirst();
        FakeConnection conn = connector.only();
        conn.responder = request -> null;
        assertThatThrownBy(() -> session.setOnce(DOOR, NS, openPayload())).isInstanceOf(MerossException.class);
        conn.drop(true);

        String all = logText();
        assertThat(all).contains("meross event=sign_in outcome=ok");
        for (String secret : List.of(EMAIL, PASSWORD, KEY, TOKEN, MerossCrypto.mqttPassword(USER_ID, KEY))) {
            assertThat(all).doesNotContain(secret);
        }
    }

    /* ---------- helpers ---------- */

    private Map<String, Object> sessionGet() {
        try {
            return session.get(DOOR, NS);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void connectWithAReadFirst() throws Exception {
        connector.nextResponder = door(Map.of("state", List.of()));
        session.get(DOOR, NS);
    }

    private static Map<String, Object> openPayload() {
        return Map.of("state", Map.of("channel", 0, "open", 1, "uuid", DOOR));
    }

    private Function<Map<String, Object>, Map<String, Object>> door(Map<String, Object> getPayload) {
        return request -> signedReply(request, "GETACK", getPayload);
    }

    /** A reply signed the way the device signs: same messageId and timestamp, our key. */
    private static Map<String, Object> signedReply(Map<String, Object> request, String method, Map<String, Object> payload) {
        Map<String, Object> reqHeader = MerossCloudApi.map(request.get("header"));
        String messageId = String.valueOf(reqHeader.get("messageId"));
        long timestamp = ((Number) reqHeader.get("timestamp")).longValue();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("messageId", messageId);
        header.put("method", method);
        header.put("namespace", reqHeader.get("namespace"));
        header.put("timestamp", timestamp);
        header.put("sign", MerossCrypto.messageSign(messageId, KEY, timestamp));
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("header", header);
        reply.put("payload", payload);
        return reply;
    }

    private void assertKind(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Kind kind) {
        assertThatThrownBy(call).isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }

    private String logText() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : logs.list) {
            sb.append(e.getFormattedMessage()).append('\n');
            for (IThrowableProxy t = e.getThrowableProxy(); t != null; t = t.getCause()) {
                sb.append(t.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    /* ---------- fakes ---------- */

    /** Meross's HTTP API, faked: no network. */
    static final class FakeApi extends MerossCloudApi {
        int logins;
        MerossException loginFailure;
        MerossException devicesFailure;

        FakeApi() {
            super("http://unused.invalid", Duration.ofSeconds(1));
        }

        @Override
        MerossCredentials login(String email, String password) throws MerossException {
            logins++;
            if (loginFailure != null) throw loginFailure;
            assertThat(email).isEqualTo(EMAIL);
            return new MerossCredentials(USER_ID, KEY, TOKEN, "https://iotx-us.meross.com", "mqtt-us-4.meross.com");
        }

        @Override
        List<MerossDeviceInfo> listDevices(MerossCredentials creds) throws MerossException {
            if (devicesFailure != null) throw devicesFailure;
            return List.of();
        }
    }

    enum PublishMode { COMPLETES, NEVER_COMPLETES, FAILS, THROWS }

    record Published(String topic, Map<String, Object> message, boolean atMostOnce) {}

    final class FakeConnector implements MqttConnector {
        final List<FakeConnection> created = new CopyOnWriteArrayList<>();
        java.util.function.Supplier<CompletableFuture<Void>> nextConnect = () -> CompletableFuture.completedFuture(null);
        Function<Map<String, Object>, Map<String, Object>> nextResponder = request -> null;

        @Override
        public MqttConnection create(MqttConnection.Settings settings, MqttConnection.Listener listener) {
            FakeConnection c = new FakeConnection(settings, listener, nextConnect.get(), nextResponder);
            created.add(c);
            return c;
        }

        FakeConnection only() {
            assertThat(created).hasSize(1);
            return created.get(0);
        }
    }

    /** A pretend MQTT connection: replies come from {@code responder}, delivered synchronously. */
    final class FakeConnection implements MqttConnection {
        final Settings settings;
        final Listener listener;
        final CompletableFuture<Void> connectResult;
        volatile Function<Map<String, Object>, Map<String, Object>> responder;
        volatile PublishMode publishMode = PublishMode.COMPLETES;
        final Map<String, Consumer<byte[]>> subscriptions = new ConcurrentHashMap<>();
        final List<Published> published = new CopyOnWriteArrayList<>();
        volatile boolean connected;
        volatile boolean closed;

        FakeConnection(Settings settings, Listener listener, CompletableFuture<Void> connectResult,
                       Function<Map<String, Object>, Map<String, Object>> responder) {
            this.settings = settings;
            this.listener = listener;
            this.connectResult = connectResult;
            this.responder = responder;
        }

        @Override
        public CompletableFuture<Void> connect() {
            return connectResult.thenRun(() -> {
                connected = true;
                listener.connected();
            });
        }

        @Override
        public CompletableFuture<Void> subscribe(String topic, Consumer<byte[]> onMessage) {
            subscriptions.put(topic, onMessage);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> publish(String topic, byte[] payload, boolean atMostOnce) {
            if (publishMode == PublishMode.THROWS) throw new IllegalStateException("client is shutting down");
            @SuppressWarnings("unchecked")
            Map<String, Object> message = json.readValue(payload, Map.class);
            published.add(new Published(topic, message, atMostOnce));
            switch (publishMode) {
                case NEVER_COMPLETES:
                    return new CompletableFuture<>();
                case FAILS:
                    return CompletableFuture.failedFuture(new IOException("socket closed"));
                default:
                    Map<String, Object> reply = responder.apply(message);
                    if (reply != null) {
                        String from = String.valueOf(MerossCloudApi.map(message.get("header")).get("from"));
                        Consumer<byte[]> handler = subscriptions.get(from);
                        if (handler != null) handler.accept(json.writeValueAsBytes(reply));
                    }
                    return CompletableFuture.completedFuture(null);
            }
        }

        @Override
        public boolean isConnected() {
            return connected && !closed;
        }

        @Override
        public void close() {
            closed = true;
            connected = false;
        }

        void drop(boolean authRejected) {
            connected = false;
            listener.disconnected(new IOException("connection reset"), authRejected);
        }

        void reconnect() {
            connected = true;
            listener.connected();
        }

        List<Published> sets() {
            List<Published> out = new ArrayList<>();
            for (Published p : published) {
                if ("SET".equals(MerossCloudApi.map(p.message().get("header")).get("method"))) out.add(p);
            }
            return out;
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
