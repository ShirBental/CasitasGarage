package home.casita.meross;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;

import home.casita.garage.DoorException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Talks to devices through Meross's cloud, the way the Meross app does: sign in over HTTP,
 * then connect to the account's MQTT broker over TLS, publish signed commands to
 * {@code /appliance/<uuid>/subscribe} and wait for the reply on our own
 * {@code /app/<userid>-<appid>/subscribe} topic, matched by message id.
 *
 * <p>Connects lazily on first use. If the connection is gone, the next request signs in again.
 */
class MerossMqttTransport implements MerossTransport, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MerossMqttTransport.class);
    private static final int MQTT_PORT = 443;

    private final MerossCloudApi api;
    private final MerossProperties properties;
    private final Duration timeout;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String appId = MerossCrypto.newAppId();
    private final Map<String, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();

    private record Session(MerossCredentials creds, Mqtt3AsyncClient client) {
        String replyTopic(String appId) {
            return "/app/" + creds.userId() + "-" + appId + "/subscribe";
        }
    }

    // Guarded by this.
    private Session session;

    MerossMqttTransport(MerossCloudApi api, MerossProperties properties) {
        this.api = api;
        this.properties = properties;
        this.timeout = properties.requestTimeout();
    }

    @Override
    public List<MerossDeviceInfo> devices() throws DoorException {
        return api.listDevices(session().creds());
    }

    @Override
    public Map<String, Object> request(String deviceUuid, String method, String namespace, Map<String, Object> payload)
            throws DoorException {
        Session s = session();
        String messageId = MerossCrypto.newMessageId();
        long timestamp = System.currentTimeMillis() / 1000;

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("from", s.replyTopic(appId));
        header.put("messageId", messageId);
        header.put("method", method);
        header.put("namespace", namespace);
        header.put("payloadVersion", 1);
        header.put("sign", MerossCrypto.messageSign(messageId, s.creds().key(), timestamp));
        header.put("timestamp", timestamp);
        header.put("triggerSrc", "Casita");
        header.put("uuid", deviceUuid);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("header", header);
        message.put("payload", payload);

        CompletableFuture<Map<String, Object>> reply = new CompletableFuture<>();
        pending.put(messageId, reply);
        try {
            s.client().publishWith()
                    .topic("/appliance/" + deviceUuid + "/subscribe")
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .payload(json.writeValueAsBytes(message))
                    .send()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            Map<String, Object> answer = reply.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            Map<String, Object> answerHeader = MerossCloudApi.map(answer.get("header"));
            Map<String, Object> answerPayload = MerossCloudApi.map(answer.get("payload"));
            if (!String.valueOf(answerHeader.get("method")).endsWith("ACK")) {
                throw new DoorException("The opener refused " + method + " " + namespace + ": " + answerPayload);
            }
            return answerPayload;
        } catch (TimeoutException e) {
            throw new DoorException("The opener didn't answer within " + timeout.toSeconds() + "s", e);
        } catch (ExecutionException e) {
            throw new DoorException("Couldn't send to the opener: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DoorException("Interrupted while waiting for the opener", e);
        } finally {
            pending.remove(messageId);
        }
    }

    private synchronized Session session() throws DoorException {
        if (session != null && session.client().getState().isConnectedOrReconnect()) {
            return session;
        }
        disconnect();

        MerossCredentials creds = api.login(properties.email(), properties.password());
        log.info("Signed in to Meross ({})", creds);
        Mqtt3AsyncClient client = MqttClient.builder()
                .useMqttVersion3()
                .identifier("app:" + appId)
                .serverHost(creds.mqttHost())
                .serverPort(MQTT_PORT)
                .sslWithDefaultConfig()
                .automaticReconnectWithDefaultConfig()
                .simpleAuth()
                    .username(creds.userId())
                    .password(MerossCrypto.mqttPassword(creds.userId(), creds.key()).getBytes(StandardCharsets.UTF_8))
                    .applySimpleAuth()
                .buildAsync();
        Session s = new Session(creds, client);
        try {
            client.connectWith().cleanSession(true).keepAlive(30).send()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            client.subscribeWith()
                    .topicFilter(s.replyTopic(appId))
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .callback(publish -> onReply(creds, publish))
                    .send()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            client.disconnect();
            throw new DoorException("Meross's MQTT broker didn't answer in time", e);
        } catch (ExecutionException e) {
            client.disconnect();
            throw new DoorException("Couldn't connect to Meross's MQTT broker: " + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            client.disconnect();
            throw new DoorException("Interrupted while connecting to Meross", e);
        }
        log.info("Connected to Meross MQTT at {}", creds.mqttHost());
        session = s;
        return s;
    }

    /** Matches a reply to its request. Replies with a bad signature are dropped. */
    private void onReply(MerossCredentials creds, Mqtt3Publish publish) {
        try {
            Map<String, Object> message = MerossCloudApi.map(json.readValue(publish.getPayloadAsBytes(), Map.class));
            Map<String, Object> header = MerossCloudApi.map(message.get("header"));
            String messageId = MerossCloudApi.string(header.get("messageId"));
            Object timestamp = header.get("timestamp");
            if (messageId == null || timestamp == null) return;
            String expected = MerossCrypto.messageSign(messageId, creds.key(), Long.parseLong(String.valueOf(timestamp)));
            if (!expected.equalsIgnoreCase(String.valueOf(header.get("sign")))) {
                log.warn("Dropped a Meross reply with a bad signature");
                return;
            }
            CompletableFuture<Map<String, Object>> waiting = pending.get(messageId);
            if (waiting != null) waiting.complete(message);
        } catch (RuntimeException e) {
            log.warn("Couldn't read a Meross reply: {}", e.getMessage());
        }
    }

    private synchronized void disconnect() {
        if (session != null) {
            session.client().disconnect();
            session = null;
        }
    }

    @Override
    public void destroy() {
        disconnect();
    }
}
