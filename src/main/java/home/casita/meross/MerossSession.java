package home.casita.meross;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import home.casita.garage.DoorCommandRejectedException;
import home.casita.garage.DoorException;
import home.casita.meross.MerossException.Kind;
import tools.jackson.databind.json.JsonMapper;

/**
 * Casita's connection to Meross's cloud: signs in, keeps one MQTT connection, and exchanges
 * signed messages with the opener. See docs/MEROSS.md for what's confirmed and what's assumed.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li><b>Lazy.</b> The first read signs in, connects and subscribes to our reply topic.</li>
 *   <li><b>Network drops:</b> the MQTT client reconnects by itself. Reads fail fast
 *       ({@code UNAVAILABLE}) until it's back; the sign-in is kept.</li>
 *   <li><b>Credentials refused</b> (sign-in, device list or broker): the sign-in is thrown
 *       away and the next attempt signs in again.</li>
 *   <li><b>Backoff:</b> after a failure Casita waits before trying Meross again (5 s, doubling
 *       to 5 min; 5 min after refused credentials), so it never hammers Meross.</li>
 * </ul>
 *
 * <h2>Commands</h2>
 * Sent only on an already-connected session (never after signing in on the spot), with
 * QoS 0, once, never retried. Anything that fails before the client takes the message is
 * {@link DoorCommandRejectedException}; anything after is an unknown outcome. If the client
 * doesn't confirm it took the message in time, the connection is dropped so nothing stays
 * queued.
 *
 * <h2>Logging</h2>
 * {@code meross event=... key=value} lines. Never the email, password, key, token or MQTT password,
 * never a full device uuid or account id, never a message payload's values. At DEBUG, every request
 * and every incoming message is traced (namespace, method, the first 8 characters of the messageId,
 * signature and correlation outcome); anything rejected or unmatched is logged at WARN, and a read
 * that times out says whether it was published, acknowledged, and what else arrived meanwhile.
 */
class MerossSession implements MerossTransport, DisposableBean {

    static final int MQTT_PORT = 443;
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(5);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    static final Duration AUTH_BACKOFF = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(MerossSession.class);
    private static final int MAX_DETAIL_LENGTH = 120;

    /** A connection and the sign-in it was made with. */
    private record Link(MerossCredentials creds, MqttConnection connection) {}

    private final MerossCloudApi api;
    private final MqttConnector connector;
    private final MerossProperties properties;
    private final Clock clock;
    private final Executor events;
    private final Duration timeout;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String appId = MerossCrypto.newAppId();
    private final Map<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    /** messageIds we stopped waiting for (namespace by id), so a reply that comes too late is recognised. */
    private final Map<String, String> expired = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 64;
        }
    });
    /** Everything that arrived, by outcome: matched, bad_signature, unmatched, late, unreadable, push. */
    private final Map<String, AtomicLong> received = new ConcurrentHashMap<>();

    // All guarded by this.
    private MerossCredentials creds;
    private volatile Link link;
    private int failures;
    private Instant retryAfter;
    private Kind lastFailure;

    MerossSession(MerossCloudApi api, MqttConnector connector, MerossProperties properties, Clock clock) {
        this(api, connector, properties, clock, ForkJoinPool.commonPool());
    }

    /** {@code events} runs connection-change handling off the MQTT client's threads. */
    MerossSession(MerossCloudApi api, MqttConnector connector, MerossProperties properties, Clock clock,
                  Executor events) {
        this.api = api;
        this.connector = connector;
        this.properties = properties;
        this.clock = clock;
        this.events = events;
        this.timeout = properties.requestTimeout();
    }

    /* ---------- MerossTransport ---------- */

    @Override
    public List<MerossDeviceInfo> devices() throws MerossException {
        MerossCredentials c = signedIn();
        try {
            return api.listDevices(c);
        } catch (MerossException e) {
            if (e.kind() == Kind.AUTH) forgetSignIn(c, "device list refused");
            throw e;
        }
    }

    @Override
    public Map<String, Object> get(String deviceUuid, String namespace) throws DoorException {
        return exchange(connectedForRead(), deviceUuid, "GET", namespace, Map.of(), false);
    }

    @Override
    public Map<String, Object> setOnce(String deviceUuid, String namespace, Map<String, Object> payload)
            throws DoorException {
        Link l;
        synchronized (this) {
            l = link;
            if (l == null || !l.connection().isConnected()) {
                log.warn("meross event=command outcome=rejected reason=not_connected");
                throw new DoorCommandRejectedException("Not connected to Meross right now; nothing was sent");
            }
        }
        return exchange(l, deviceUuid, "SET", namespace, payload, true);
    }

    /* ---------- sign-in and connection ---------- */

    private synchronized MerossCredentials signedIn() throws MerossException {
        if (creds != null) return creds;
        waitOutBackoff();
        try {
            creds = api.login(properties.email(), properties.password());
        } catch (MerossException e) {
            log.warn("meross event=sign_in outcome=failed kind={} detail=\"{}\"", e.kind(), detail(e));
            failed(e.kind());
            throw e;
        }
        log.info("meross event=sign_in outcome=ok api={} mqtt_host={}", creds.apiBaseUrl(), creds.mqttHost());
        return creds;
    }

    private synchronized Link connectedForRead() throws MerossException {
        if (link != null) {
            if (link.connection().isConnected()) return link;
            throw new MerossException(Kind.UNAVAILABLE, "Reconnecting to Meross");
        }
        waitOutBackoff();
        MerossCredentials c = signedIn();

        MqttConnection.Settings settings = new MqttConnection.Settings(c.mqttHost(), MQTT_PORT, "app:" + appId,
                c.userId(), MerossCrypto.mqttPassword(c.userId(), c.key()).getBytes(StandardCharsets.UTF_8));
        Link[] self = new Link[1];
        MqttConnection connection = connector.create(settings, new MqttConnection.Listener() {
            @Override
            public void connected() {
                log.info("meross event=mqtt_connected mqtt_host={}", c.mqttHost());
            }

            @Override
            public void disconnected(Throwable cause, boolean authRejected) {
                // Off the MQTT client's thread, so it never waits on this session's lock.
                events.execute(() -> onDisconnected(self[0], cause, authRejected));
            }
        });
        Link l = new Link(c, connection);
        self[0] = l;
        try {
            connection.connect().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            connection.subscribe(replyTopic(c), bytes -> onMessage(c, Topic.REPLY, bytes)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            // The account's push topic, as Meross's apps subscribe to it too. Only traced: replies are taken
            // from the reply topic alone, but a reply routed here instead would show up in the logs.
            connection.subscribe(pushTopic(c), bytes -> onMessage(c, Topic.PUSH, bytes)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            connection.close();
            if (causedByAuthRejection(e)) {
                creds = null;
                log.warn("meross event=mqtt_connect outcome=auth_rejected mqtt_host={}", c.mqttHost());
                failed(Kind.AUTH);
                throw new MerossException(Kind.AUTH, "Meross's MQTT broker refused the sign-in");
            }
            log.warn("meross event=mqtt_connect outcome=failed mqtt_host={} error={}", c.mqttHost(), errorName(e.getCause()));
            failed(Kind.UNAVAILABLE);
            throw new MerossException(Kind.UNAVAILABLE, "Couldn't connect to Meross's MQTT broker: " + errorName(e.getCause()));
        } catch (TimeoutException e) {
            connection.close();
            log.warn("meross event=mqtt_connect outcome=timeout mqtt_host={}", c.mqttHost());
            failed(Kind.TIMEOUT);
            throw new MerossException(Kind.TIMEOUT, "Meross's MQTT broker didn't answer within " + seconds(timeout));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            connection.close();
            throw new MerossException(Kind.UNAVAILABLE, "Interrupted while connecting to Meross", e);
        }
        link = l;
        failures = 0;
        retryAfter = null;
        log.info("meross event=mqtt_subscribed mqtt_host={}", c.mqttHost());
        return l;
    }

    private synchronized void onDisconnected(Link which, Throwable cause, boolean authRejected) {
        if (which == null || which != link) return; // an old connection
        if (authRejected) {
            log.warn("meross event=mqtt_disconnected outcome=auth_rejected");
            which.connection().close();
            link = null;
            creds = null;
            failed(Kind.AUTH);
        } else {
            log.warn("meross event=mqtt_disconnected outcome=reconnecting error={}", errorName(cause));
        }
    }

    private synchronized void forgetSignIn(MerossCredentials c, String reason) {
        if (creds != c) return;
        log.warn("meross event=sign_in_dropped reason=\"{}\"", reason);
        creds = null;
        if (link != null) {
            link.connection().close();
            link = null;
        }
        failed(Kind.AUTH);
    }

    private synchronized void dropConnection(Link which, String reason) {
        if (which != link) return;
        log.warn("meross event=mqtt_dropped reason=\"{}\"", reason);
        which.connection().close();
        link = null;
    }

    private void waitOutBackoff() throws MerossException {
        if (retryAfter != null && clock.instant().isBefore(retryAfter)) {
            throw new MerossException(Kind.UNAVAILABLE,
                    "Waiting until " + retryAfter + " before trying Meross again (last failure: " + lastFailure + ")");
        }
    }

    private void failed(Kind kind) {
        failures++;
        lastFailure = kind;
        Duration wait = kind == Kind.AUTH ? AUTH_BACKOFF : backoff(failures);
        retryAfter = clock.instant().plus(wait);
        log.warn("meross event=backoff kind={} failures={} wait_seconds={}", kind, failures, wait.toSeconds());
    }

    static Duration backoff(int failures) {
        Duration d = FIRST_BACKOFF;
        for (int i = 1; i < failures && d.compareTo(MAX_BACKOFF) < 0; i++) d = d.multipliedBy(2);
        return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
    }

    /* ---------- messages ---------- */

    private Map<String, Object> exchange(Link l, String deviceUuid, String method, String namespace,
                                         Map<String, Object> payload, boolean command) throws DoorException {
        String messageId = MerossCrypto.newMessageId();
        long timestamp = clock.instant().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("from", replyTopic(l.creds()));
        header.put("messageId", messageId);
        header.put("method", method);
        header.put("namespace", namespace);
        header.put("payloadVersion", 1);
        header.put("sign", MerossCrypto.messageSign(messageId, l.creds().key(), timestamp));
        header.put("timestamp", timestamp);
        header.put("triggerSrc", "Casita");
        header.put("uuid", deviceUuid);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("header", header);
        message.put("payload", payload);
        byte[] bytes = json.writeValueAsBytes(message);
        String topic = "/appliance/" + deviceUuid + "/subscribe";

        long started = System.nanoTime();
        long deadline = started + timeout.toNanos();
        CompletableFuture<Map<String, Object>> reply = new CompletableFuture<>();
        Map<String, Long> before = receivedSnapshot();
        boolean acked = false;
        pending.put(messageId, reply);
        log.debug("meross event=request_published method={} namespace={} mid={} qos={} payload_keys={}",
                method, namespace, shortId(messageId), command ? 0 : 1, new TreeSet<>(payload.keySet()));
        try {
            CompletableFuture<Void> published;
            try {
                published = l.connection().publish(topic, bytes, command);
            } catch (RuntimeException e) {
                // The client refused the message outright: nothing left Casita.
                if (command) {
                    log.warn("meross event=command outcome=rejected reason=publish_refused error={}", errorName(e));
                    throw new DoorCommandRejectedException("The MQTT client refused the command; nothing was sent", e);
                }
                throw new MerossException(Kind.UNAVAILABLE, "Couldn't send to the opener: " + errorName(e), e);
            }

            try {
                published.get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
                acked = true;
                // QoS 1 (reads): the broker acknowledged the publish. QoS 0 (commands): the client sent it.
                log.debug("meross event=publish_acked method={} namespace={} mid={} elapsed_ms={}",
                        method, namespace, shortId(messageId), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            } catch (TimeoutException e) {
                if (command) {
                    // It may still be queued: drop the connection so it can't go out later.
                    dropConnection(l, "command publish not confirmed");
                    log.warn("meross event=command outcome=unknown reason=publish_not_confirmed");
                    throw new MerossException(Kind.TIMEOUT, "The command may or may not have been sent (not confirmed within "
                            + seconds(timeout) + ")");
                }
                log.warn("meross event=read outcome=timeout namespace={} mid={} published=yes acked=no reply=none "
                        + "(the broker never acknowledged the publish)", namespace, shortId(messageId));
                throw new MerossException(Kind.TIMEOUT, "Couldn't send to the opener within " + seconds(timeout));
            } catch (ExecutionException e) {
                if (command) {
                    dropConnection(l, "command publish failed");
                    log.warn("meross event=command outcome=unknown reason=publish_failed error={}", errorName(e.getCause()));
                    throw new MerossException(Kind.UNAVAILABLE, "The command may or may not have been sent: "
                            + errorName(e.getCause()));
                }
                throw new MerossException(Kind.UNAVAILABLE, "Couldn't send to the opener: " + errorName(e.getCause()));
            }

            Map<String, Object> answer;
            try {
                answer = reply.get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (command) {
                    log.warn("meross event=command outcome=unknown reason=no_reply");
                    throw new MerossException(Kind.TIMEOUT,
                            "No reply from the opener within " + seconds(timeout) + "; the command may or may not have been carried out");
                }
                expired.put(messageId, namespace);
                log.warn("meross event=read outcome=timeout namespace={} mid={} published=yes acked={} reply=none "
                        + "received_meanwhile={}", namespace, shortId(messageId), acked ? "yes" : "no", receivedSince(before));
                throw new MerossException(Kind.TIMEOUT, "The opener didn't answer within " + seconds(timeout));
            } catch (ExecutionException e) {
                throw new MerossException(Kind.PROTOCOL, "Couldn't read the opener's reply", e.getCause());
            }

            Map<String, Object> answerHeader = MerossCloudApi.map(answer.get("header"));
            Map<String, Object> answerPayload = MerossCloudApi.map(answer.get("payload"));
            String answerMethod = String.valueOf(answerHeader.get("method"));
            if (!answerMethod.equals(method + "ACK")) {
                // The error code only: the payload itself may carry device details and is never logged.
                Object code = answerPayload.get("error") instanceof Map<?, ?> error ? error.get("code") : null;
                log.warn("meross event={} outcome=device_error method={} namespace={} mid={} error_code={}",
                        command ? "command" : "read", answerMethod, namespace, shortId(messageId), code);
                throw new MerossException(Kind.DEVICE_ERROR, "The opener answered " + answerMethod + " (error code " + code + ")");
            }
            if (command) log.info("meross event=command outcome=acknowledged namespace={}", namespace);
            return answerPayload;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MerossException(Kind.UNAVAILABLE, "Interrupted while talking to the opener", e);
        } finally {
            pending.remove(messageId);
        }
    }

    /** Which of our two subscriptions a message came in on. */
    enum Topic { REPLY, PUSH }

    /**
     * Matches a reply to its request. Replies come only from the reply topic and must be signed with
     * our key; everything else is traced and counted, never used.
     */
    private void onMessage(MerossCredentials c, Topic topic, byte[] bytes) {
        String method = null;
        String namespace = null;
        String mid = null;
        try {
            Map<String, Object> message = MerossCloudApi.map(json.readValue(bytes, Map.class));
            Map<String, Object> header = MerossCloudApi.map(message.get("header"));
            method = MerossCloudApi.string(header.get("method"));
            namespace = MerossCloudApi.string(header.get("namespace"));
            String messageId = MerossCloudApi.string(header.get("messageId"));
            mid = shortId(messageId);
            Object timestamp = header.get("timestamp");
            if (messageId == null || timestamp == null) {
                count("unreadable");
                log.warn("meross event=message_dropped topic={} reason=incomplete_header method={} namespace={}", topic, method, namespace);
                return;
            }
            String expected = MerossCrypto.messageSign(messageId, c.key(), Long.parseLong(String.valueOf(timestamp)));
            String sign = header.get("sign") == null ? "missing" : expected.equalsIgnoreCase(String.valueOf(header.get("sign"))) ? "ok" : "bad";
            if (!"ok".equals(sign)) {
                count("bad_signature");
                log.warn("meross event=message_dropped topic={} reason=signature_{} method={} namespace={} mid={}", topic, sign, method, namespace, mid);
                return;
            }
            if (topic == Topic.PUSH) {
                count("push");
                if (pending.containsKey(messageId)) {
                    log.warn("meross event=message_on_push_topic correlation=pending_request method={} namespace={} mid={} sign=ok "
                            + "(not used: replies are only taken from the reply topic)", method, namespace, mid);
                } else {
                    log.debug("meross event=push method={} namespace={} sign=ok", method, namespace);
                }
                return;
            }
            CompletableFuture<Map<String, Object>> waiting = pending.get(messageId);
            if (waiting == null) {
                String late = expired.get(messageId);
                count(late != null ? "late" : "unmatched");
                log.warn("meross event=reply_ignored topic=REPLY correlation={} method={} namespace={} mid={} sign=ok",
                        late != null ? "late" : "unmatched", method, namespace, mid);
                return;
            }
            count("matched");
            log.debug("meross event=reply topic=REPLY correlation=matched method={} namespace={} mid={} sign=ok", method, namespace, mid);
            waiting.complete(message);
        } catch (RuntimeException e) {
            count("unreadable");
            log.warn("meross event=message_dropped topic={} reason=unreadable error={} method={} namespace={} mid={}",
                    topic, errorName(e), method, namespace, mid);
        }
    }

    private void count(String outcome) {
        received.computeIfAbsent(outcome, k -> new AtomicLong()).incrementAndGet();
    }

    private Map<String, Long> receivedSnapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        received.forEach((k, v) -> snapshot.put(k, v.get()));
        return snapshot;
    }

    /** What arrived since {@code before}, as {outcome=count}, outcomes with nothing new left out. */
    private Map<String, Long> receivedSince(Map<String, Long> before) {
        Map<String, Long> since = new TreeMap<>();
        received.forEach((k, v) -> {
            long n = v.get() - before.getOrDefault(k, 0L);
            if (n > 0) since.put(k, n);
        });
        return since;
    }

    /** Enough of a messageId to follow one exchange through the log; it identifies nothing else. */
    static String shortId(String messageId) {
        return messageId == null ? null : messageId.length() <= 8 ? messageId : messageId.substring(0, 8);
    }

    private String replyTopic(MerossCredentials c) {
        return "/app/" + c.userId() + "-" + appId + "/subscribe";
    }

    private static String pushTopic(MerossCredentials c) {
        return "/app/" + c.userId() + "/subscribe";
    }

    /* ---------- helpers ---------- */

    private static boolean causedByAuthRejection(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof MqttConnection.AuthRejected) return true;
        }
        return false;
    }

    private static long remainingMillis(long deadlineNanos) {
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
    }

    private static String errorName(Throwable t) {
        return t == null ? "none" : t.getClass().getSimpleName();
    }

    private static String detail(Exception e) {
        return shorten(String.valueOf(e.getMessage())).replace('"', '\'');
    }

    private static String shorten(String s) {
        return s.length() > MAX_DETAIL_LENGTH ? s.substring(0, MAX_DETAIL_LENGTH) + "…" : s;
    }

    private static String seconds(Duration d) {
        return d.toMillis() % 1000 == 0 ? d.toSeconds() + "s" : d.toMillis() + "ms";
    }

    @Override
    public synchronized void destroy() {
        if (link != null) {
            link.connection().close();
            link = null;
        }
    }
}
