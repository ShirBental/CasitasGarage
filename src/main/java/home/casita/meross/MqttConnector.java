package home.casita.meross;

/** Makes a new MQTT connection. One per sign-in. */
interface MqttConnector {

    MqttConnection create(MqttConnection.Settings settings, MqttConnection.Listener listener);
}
