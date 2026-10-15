package home.casita.meross;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode;

/**
 * {@link MqttConnection} on the HiveMQ async client (MQTT 3.1.1 over TLS).
 *
 * <ul>
 *   <li>Network drops: HiveMQ reconnects automatically (1 s, doubling to 2 min) and
 *       restores subscriptions, which it does by default (HiveMQ 1.2.0 release notes).</li>
 *   <li>Every connect uses {@code cleanSession=true}. HiveMQ doesn't republish queued
 *       messages when the session expired, also by default, so a command can't go out late
 *       after a reconnect. See docs/MEROSS.md.</li>
 *   <li>Credentials refused: reconnecting stops and the session must sign in again.</li>
 * </ul>
 * This thin adapter is not unit-tested; the session logic above it is.
 */
final class HiveMqConnector implements MqttConnector {

    @Override
    public MqttConnection create(MqttConnection.Settings settings, MqttConnection.Listener listener) {
        return new HiveMqConnection(settings, listener);
    }

    private static final class HiveMqConnection implements MqttConnection {

        private final Mqtt3AsyncClient client;

        HiveMqConnection(Settings settings, Listener listener) {
            this.client = MqttClient.builder()
                    .useMqttVersion3()
                    .identifier(settings.clientId())
                    .serverHost(settings.host())
                    .serverPort(settings.port())
                    .sslWithDefaultConfig()
                    .automaticReconnect()
                        .initialDelay(1, TimeUnit.SECONDS)
                        .maxDelay(2, TimeUnit.MINUTES)
                        .applyAutomaticReconnect()
                    .addConnectedListener(context -> listener.connected())
                    .addDisconnectedListener(context -> {
                        if (context.getSource() == MqttDisconnectSource.USER) return; // our own close()
                        boolean authRejected = isAuthRejection(context.getCause());
                        if (authRejected) context.getReconnector().reconnect(false);
                        listener.disconnected(context.getCause(), authRejected);
                    })
                    .simpleAuth()
                        .username(settings.username())
                        .password(settings.password())
                        .applySimpleAuth()
                    .buildAsync();
        }

        @Override
        public CompletableFuture<Void> connect() {
            return client.connectWith().cleanSession(true).keepAlive(30).send()
                    .handle((connAck, error) -> {
                        if (error == null) return null;
                        Throwable cause = unwrap(error);
                        if (isAuthRejection(cause)) throw new AuthRejected("The MQTT broker refused Casita's credentials");
                        throw cause instanceof RuntimeException r ? r : new CompletionException(cause);
                    });
        }

        @Override
        public CompletableFuture<Void> subscribe(String topic, Consumer<byte[]> onMessage) {
            return client.subscribeWith()
                    .topicFilter(topic)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .callback(publish -> onMessage.accept(publish.getPayloadAsBytes()))
                    .send()
                    .thenApply(subAck -> (Void) null);
        }

        @Override
        public CompletableFuture<Void> publish(String topic, byte[] payload, boolean atMostOnce) {
            return client.publishWith()
                    .topic(topic)
                    .qos(atMostOnce ? MqttQos.AT_MOST_ONCE : MqttQos.AT_LEAST_ONCE)
                    .payload(payload)
                    .send()
                    .thenApply(publish -> (Void) null);
        }

        @Override
        public boolean isConnected() {
            return client.getState().isConnected();
        }

        @Override
        public void close() {
            client.disconnect();
        }

        private static boolean isAuthRejection(Throwable error) {
            Throwable cause = unwrap(error);
            if (!(cause instanceof Mqtt3ConnAckException connAckException)) return false;
            Mqtt3ConnAckReturnCode code = connAckException.getMqttMessage().getReturnCode();
            return code == Mqtt3ConnAckReturnCode.NOT_AUTHORIZED || code == Mqtt3ConnAckReturnCode.BAD_USER_NAME_OR_PASSWORD;
        }

        private static Throwable unwrap(Throwable error) {
            Throwable t = error;
            while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
            return t;
        }
    }
}
