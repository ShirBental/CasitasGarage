package home.casita.meross;

import java.util.LinkedHashMap;
import java.util.Map;

/** Meross's signed message, the same over MQTT and over the opener's local HTTP: a header and a payload. */
final class MerossMessages {

    private MerossMessages() {}

    /**
     * @param from where replies go: the MQTT reply topic, or (locally) the same topic, which the opener echoes but
     *             answers on the HTTP response anyway
     */
    static Map<String, Object> message(String messageId, long timestampSeconds, String from, String key, String deviceUuid,
                                       String method, String namespace, Map<String, Object> payload) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("from", from);
        header.put("messageId", messageId);
        header.put("method", method);
        header.put("namespace", namespace);
        header.put("payloadVersion", 1);
        header.put("sign", MerossCrypto.messageSign(messageId, key, timestampSeconds));
        header.put("timestamp", timestampSeconds);
        header.put("triggerSrc", "Casita");
        header.put("uuid", deviceUuid);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("header", header);
        message.put("payload", payload);
        return message;
    }

    /** True if a reply's header is signed with the account key (md5(messageId + key + timestamp)). */
    static boolean signedWith(Map<String, Object> header, String key) {
        Object sign = header.get("sign"), mid = header.get("messageId"), ts = header.get("timestamp");
        if (sign == null || mid == null || ts == null) return false;
        try {
            return MerossCrypto.messageSign(String.valueOf(mid), key, Long.parseLong(String.valueOf(ts))).equalsIgnoreCase(String.valueOf(sign));
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
