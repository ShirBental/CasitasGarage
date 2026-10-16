package home.casita.meross;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The few MQTT operations the Meross session needs. The real one wraps the HiveMQ async
 * client ({@link HiveMqConnector}); tests use a fake. Not a general MQTT abstraction.
 */
interface MqttConnection {

    /** Connection settings. {@code password} is a secret: never logged. */
    record Settings(String host, int port, String clientId, String username, byte[] password) {
        @Override
        public String toString() {
            return "Settings[host=" + host + ", port=" + port + "]";
        }
    }

    /** Told about connection changes; called on MQTT client threads. */
    interface Listener {
        void connected();

        /**
         * @param authRejected true if the broker refused our credentials; the connection then
         *                     stops reconnecting and must be replaced after a new sign-in
         */
        void disconnected(Throwable cause, boolean authRejected);
    }

    /** Completes when the broker accepts the connection. Fails with {@link AuthRejected} on bad credentials. */
    CompletableFuture<Void> connect();

    /** Subscribes; the subscription is restored by the client after a reconnect. */
    CompletableFuture<Void> subscribe(String topic, Consumer<byte[]> onMessage);

    /**
     * Publishes once. {@code atMostOnce} selects QoS 0 (never delivered twice) instead of QoS 1.
     * The future completes when the client has handed the message to the network; it says
     * nothing about the device.
     */
    CompletableFuture<Void> publish(String topic, byte[] payload, boolean atMostOnce);

    boolean isConnected();

    /** Stops the connection for good, dropping anything queued. */
    void close();

    /** The broker refused our credentials. */
    final class AuthRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AuthRejected(String message) {
            super(message);
        }
    }
}
