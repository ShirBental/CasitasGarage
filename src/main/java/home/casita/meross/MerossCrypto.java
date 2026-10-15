package home.casita.meross;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The hashes Meross's protocol uses. MD5 is Meross's choice, not ours: it's only a request
 * signature here, and TLS protects everything on the wire.
 */
final class MerossCrypto {

    /** Shared secret every Meross app uses to sign HTTP API requests (public, from the app). */
    static final String HTTP_SECRET = "23x17ahWarFH6w29";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String NONCE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private MerossCrypto() {}

    static String md5Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** HTTP API: md5(secret + timestamp + nonce + base64 params). */
    static String httpSign(long timestampMillis, String nonce, String params) {
        return md5Hex(HTTP_SECRET + timestampMillis + nonce + params);
    }

    /** MQTT messages: md5(messageId + key + timestamp). */
    static String messageSign(String messageId, String key, long timestampSeconds) {
        return md5Hex(messageId + key + timestampSeconds);
    }

    /** MQTT broker password: md5(userId + key). */
    static String mqttPassword(String userId, String key) {
        return md5Hex(userId + key);
    }

    /** A fresh app id, as the Meross app makes one: md5("API" + random uuid). */
    static String newAppId() {
        return md5Hex("API" + UUID.randomUUID());
    }

    static String newMessageId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static String newNonce() {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) sb.append(NONCE_CHARS.charAt(RANDOM.nextInt(NONCE_CHARS.length())));
        return sb.toString();
    }
}
