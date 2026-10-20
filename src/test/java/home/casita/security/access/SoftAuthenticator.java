package home.casita.security.access;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A software passkey for tests: a real P-256 key, a real "none" attestation and real ES256 assertions, built
 * the way an iPhone's authenticator builds them, so tests go through Spring Security's actual WebAuthn
 * verification (webauthn4j) instead of handing it a pre-verified credential. Knobs make it misbehave:
 * no user verification, a wrong origin, a bad signature.
 */
public final class SoftAuthenticator {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder D64 = Base64.getUrlDecoder();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final int UP = 0x01, UV = 0x04, BE = 0x08, BS = 0x10, AT = 0x40;

    private final KeyPair keys;
    private final byte[] credentialId = new byte[16];
    private byte[] userHandle;

    /** How the next ceremony behaves; all false is an honest iPhone. */
    public boolean skipUserVerification;
    public boolean badSignature;
    public String origin = "http://localhost:8080";

    public SoftAuthenticator() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            keys = generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        new SecureRandom().nextBytes(credentialId);
    }

    public String credentialId() {
        return B64.encodeToString(credentialId);
    }

    /** The body Spring's passkey script posts to /webauthn/register, answering these creation options. */
    public String register(String creationOptionsJson, String label) {
        JsonNode options = JSON.readTree(creationOptionsJson);
        userHandle = D64.decode(options.path("user").path("id").asText());
        String rpId = options.path("rp").path("id").asText();
        byte[] clientData = clientData("webauthn.create", options.path("challenge").asText());
        byte[] authData = concat(sha256(rpId.getBytes(StandardCharsets.UTF_8)), new byte[] {(byte) flags(AT)}, new byte[4],
                new byte[16], ByteBuffer.allocate(2).putShort((short) credentialId.length).array(), credentialId, coseKey());
        Map<Object, Object> attestation = new LinkedHashMap<>();
        attestation.put("fmt", "none");
        attestation.put("attStmt", Map.of());
        attestation.put("authData", authData);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("attestationObject", B64.encodeToString(cbor(attestation)));
        response.put("clientDataJSON", B64.encodeToString(clientData));
        response.put("transports", List.of("internal", "hybrid"));
        Map<String, Object> credential = new LinkedHashMap<>();
        credential.put("id", credentialId());
        credential.put("rawId", credentialId());
        credential.put("response", response);
        credential.put("type", "public-key");
        credential.put("clientExtensionResults", Map.of());
        credential.put("authenticatorAttachment", "platform");
        return JSON.writeValueAsString(Map.of("publicKey", Map.of("credential", credential, "label", label)));
    }

    /** The body Spring's passkey script posts to /login/webauthn, answering these request options. */
    public String assertion(String requestOptionsJson) {
        if (userHandle == null) throw new IllegalStateException("register first");
        JsonNode options = JSON.readTree(requestOptionsJson);
        String rpId = options.path("rpId").asText();
        byte[] clientData = clientData("webauthn.get", options.path("challenge").asText());
        byte[] authData = concat(sha256(rpId.getBytes(StandardCharsets.UTF_8)), new byte[] {(byte) flags(0)}, new byte[4]);
        byte[] signature = sign(concat(authData, sha256(clientData)));
        if (badSignature) signature[signature.length - 1] ^= 0x01;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("authenticatorData", B64.encodeToString(authData));
        response.put("clientDataJSON", B64.encodeToString(clientData));
        response.put("signature", B64.encodeToString(signature));
        response.put("userHandle", B64.encodeToString(userHandle));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", credentialId());
        body.put("rawId", credentialId());
        body.put("response", response);
        body.put("type", "public-key");
        body.put("clientExtensionResults", Map.of());
        body.put("authenticatorAttachment", "platform");
        return JSON.writeValueAsString(body);
    }

    /* ---------- the bytes ---------- */

    /** User present always; user verified unless told otherwise; backup-eligible and backed up, like iCloud Keychain. */
    private int flags(int extra) {
        return UP | (skipUserVerification ? 0 : UV) | BE | BS | extra;
    }

    private byte[] clientData(String type, String challenge) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", type);
        data.put("challenge", challenge);
        data.put("origin", origin);
        data.put("crossOrigin", false);
        return JSON.writeValueAsString(data).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] coseKey() {
        ECPublicKey pub = (ECPublicKey) keys.getPublic();
        Map<Object, Object> key = new LinkedHashMap<>();
        key.put(1, 2);     // kty: EC2
        key.put(3, -7);    // alg: ES256
        key.put(-1, 1);    // crv: P-256
        key.put(-2, unsigned32(pub.getW().getAffineX()));
        key.put(-3, unsigned32(pub.getW().getAffineY()));
        return cbor(key);
    }

    private byte[] sign(byte[] data) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(keys.getPrivate());
            s.update(data);
            return s.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] unsigned32(BigInteger n) {
        byte[] raw = n.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    /** Just enough CBOR for WebAuthn: integers, byte and text strings, maps. */
    private static byte[] cbor(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeCbor(out, value);
        return out.toByteArray();
    }

    private static void writeCbor(ByteArrayOutputStream out, Object value) {
        if (value instanceof Integer i) {
            if (i >= 0) head(out, 0, i);
            else head(out, 1, -1L - i);
        } else if (value instanceof byte[] b) {
            head(out, 2, b.length);
            out.writeBytes(b);
        } else if (value instanceof String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            head(out, 3, b.length);
            out.writeBytes(b);
        } else if (value instanceof Map<?, ?> m) {
            head(out, 5, m.size());
            m.forEach((k, v) -> {
                writeCbor(out, k);
                writeCbor(out, v);
            });
        } else {
            throw new IllegalArgumentException("no CBOR for " + value);
        }
    }

    private static void head(ByteArrayOutputStream out, int major, long n) {
        int m = major << 5;
        if (n < 24) out.write(m | (int) n);
        else if (n < 256) {
            out.write(m | 24);
            out.write((int) n);
        } else if (n < 65536) {
            out.write(m | 25);
            out.write((int) (n >> 8));
            out.write((int) n);
        } else {
            out.write(m | 26);
            out.writeBytes(ByteBuffer.allocate(4).putInt((int) n).array());
        }
    }
}
