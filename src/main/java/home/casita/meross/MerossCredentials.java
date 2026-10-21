package home.casita.meross;

/**
 * What a Meross login returns.
 *
 * @param userId     account id; also the MQTT username
 * @param key        signs MQTT messages and makes the MQTT password; treat as a secret
 * @param token      authorizes HTTP API calls; treat as a secret
 * @param apiBaseUrl the API for this account's region
 * @param mqttHost   the MQTT broker for this account
 */
record MerossCredentials(String userId, String key, String token, String apiBaseUrl, String mqttHost) {

    @Override
    public String toString() {
        return "MerossCredentials[userId=" + userId + ", apiBaseUrl=" + apiBaseUrl + ", mqttHost=" + mqttHost + "]";
    }
}
