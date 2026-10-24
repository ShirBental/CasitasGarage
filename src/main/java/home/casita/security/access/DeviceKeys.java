package home.casita.security.access;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.ResponseCookie;

import home.casita.security.store.FileSecurityStore;

/**
 * The browser keys Casita hands out when an invite is redeemed (docs/ACCESS.md). A browser key is a random device
 * id ({@code b-} and 128 random bits, written in hex) together with an HMAC-SHA256 of that id under a server
 * secret, kept in a {@code __Host-} cookie that is Secure, HttpOnly and SameSite=Lax. The id is what device
 * authorizations are stored under; the MAC means an id read from a log or {@code casita-access list} can't be
 * turned into a working cookie. Only the server secret ({@code device.key}, owner-only, created on first start)
 * can mint or check one.
 *
 * <p>What this identifies: one browser's cookie jar on one device (Safari, or a Home Screen app, which has its
 * own). Not the physical phone: whoever copies the cookie copies the browser key. A browser key alone grants
 * nothing; it is checked together with a passkey sign-in and the security store on every request.
 */
public final class DeviceKeys {

    public static final String COOKIE = "__Host-casita-device";
    public static final String ID_PREFIX = "b-";
    /** Browsers cap cookie lifetimes at about 400 days; every passkey sign-in renews it. */
    public static final Duration LIFETIME = Duration.ofDays(400);

    private static final Pattern ID = Pattern.compile("b-[0-9a-f]{32}");
    private static final String CONTEXT = "casita-device-v1:";

    /** A newly minted browser key: its device id, and the cookie value that proves it. */
    public record Issued(String deviceId, String cookieValue) {
        @Override
        public String toString() {
            return "Issued[" + deviceId + "]";
        }
    }

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    /** Reads the server secret from {@code keyFile}, creating it (owner-only, 256 random bits) if it doesn't exist. */
    public DeviceKeys(Path keyFile) {
        try {
            Path dir = keyFile.toAbsolutePath().getParent();
            if (!Files.exists(keyFile)) {
                Files.createDirectories(dir);
                byte[] raw = new byte[32];
                random.nextBytes(raw);
                Files.createFile(keyFile);
                FileSecurityStore.restrict(keyFile, "rw-------");
                Files.writeString(keyFile, HexFormat.of().formatHex(raw) + "\n", StandardCharsets.UTF_8);
            }
            byte[] read = HexFormat.of().parseHex(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
            if (read.length < 32) throw new IllegalStateException("The device key in " + keyFile + " is shorter than 256 bits");
            this.key = read;
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't set up the device key", e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("The device key in " + keyFile + " isn't hex", e);
        }
    }

    /** A new browser key, never issued before. */
    public Issued issue() {
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        String id = ID_PREFIX + HexFormat.of().formatHex(raw);
        return new Issued(id, cookieValueFor(id));
    }

    /** The cookie value for a device id: the id, a dot, and its MAC. */
    String cookieValueFor(String deviceId) {
        return deviceId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac(deviceId));
    }

    /** The device id a cookie value proves, or empty if it doesn't (wrong form, wrong MAC). Constant-time MAC check. */
    public Optional<String> verify(String cookieValue) {
        if (cookieValue == null || cookieValue.length() > 128) return Optional.empty();
        int dot = cookieValue.indexOf('.');
        if (dot < 0) return Optional.empty();
        String id = cookieValue.substring(0, dot);
        if (!ID.matcher(id).matches()) return Optional.empty();
        byte[] given;
        try {
            given = Base64.getUrlDecoder().decode(cookieValue.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return MessageDigest.isEqual(mac(id), given) ? Optional.of(id) : Optional.empty();
    }

    /** The Set-Cookie for a browser key: host-only, whole site, HTTPS only, invisible to scripts. */
    public static ResponseCookie cookie(String cookieValue) {
        return ResponseCookie.from(COOKIE, cookieValue).path("/").secure(true).httpOnly(true).sameSite("Lax").maxAge(LIFETIME).build();
    }

    private byte[] mac(String deviceId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal((CONTEXT + deviceId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
