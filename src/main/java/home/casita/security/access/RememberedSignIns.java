package home.casita.security.access;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.ResponseCookie;

import home.casita.security.RememberProperties;

/**
 * Remembered sign-ins (docs/ACCESS.md, "Staying signed in"): after a real passkey sign-in, the browser gets a second
 * cookie that lets it sign itself back in, without Face ID, when its session is gone (a restart, a week idle), for a
 * limited time. It is tied to one member, the passkey they signed in with and the browser key of that browser, and
 * signed (HMAC-SHA256) with its own server secret, {@code <data-dir>/remember.key}.
 *
 * <p>The cookie is a claim, never a decision: every use is checked again against the security store, exactly like a
 * session ({@link AccessDecisions#passkeySession}), so revoking the browser, the passkey or the member stops it on the
 * next request. What it can't do: tell two copies of the same cookie apart. It is a bearer secret in the same cookie jar
 * as the browser key; whoever copies one copies both (as today with the session cookie), until {@code max-age} after the
 * last Face ID, or until the browser, passkey or member is revoked. Deleting {@code remember.key} (and restarting) ends
 * every remembered sign-in at once.
 *
 * <ul>
 *   <li>Lifetime: at most {@code max-age} after the passkey sign-in that issued it (never extended by use: Face ID
 *       at least that often), and at most {@code idle} after it was last used. Re-issued on every use with a new idle
 *       deadline. Lowering either setting shortens cookies already issued.</li>
 *   <li>Cookie: {@value #COOKIE}, host-only, {@code Secure}, {@code HttpOnly}, {@code SameSite=Lax}. Its content is
 *       not secret (member, passkey id, browser id, times) but it is never logged.</li>
 * </ul>
 */
public final class RememberedSignIns {

    public static final String COOKIE = "__Host-casita-signin";
    private static final String VERSION = "v1";
    private static final String CONTEXT = "casita-signin-v1:";
    private static final Pattern MEMBER = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern CREDENTIAL = Pattern.compile("[A-Za-z0-9_-]{1,1400}");
    private static final Pattern NODE = Pattern.compile("b-[0-9a-f]{32}");
    /** A cookie claiming a sign-in from the future (beyond clock skew) is refused. */
    private static final Duration SKEW = Duration.ofMinutes(5);

    /**
     * @param verifiedAt when the member last proved themselves with the passkey on this browser
     * @param expiresAt  {@code verifiedAt + max-age}: never moves
     * @param idleUntil  when it lapses if unused
     */
    public record Claim(String member, String credentialId, String nodeId, Instant verifiedAt, Instant expiresAt, Instant idleUntil) {}

    private final byte[] key;
    private final RememberProperties properties;
    private final Clock clock;

    public RememberedSignIns(Path keyFile, RememberProperties properties, Clock clock) {
        this.key = DeviceKeys.loadOrCreate(keyFile, new SecureRandom());
        this.properties = properties;
        this.clock = clock;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /** After a passkey sign-in on this browser: a new remembered sign-in, or empty when they are off. */
    public Optional<ResponseCookie> issue(String member, String credentialId, String nodeId) {
        if (!enabled()) return Optional.empty();
        Instant now = now();
        Instant expires = now.plus(properties.maxAge());
        return Optional.of(cookie(new Claim(member, credentialId, nodeId, now, expires, min(now.plus(properties.idle()), expires))));
    }

    /** After a session was restored from {@code claim}: the same sign-in and expiry, a new idle deadline. */
    public ResponseCookie renew(Claim claim) {
        Instant now = now();
        return cookie(new Claim(claim.member(), claim.credentialId(), claim.nodeId(), claim.verifiedAt(), claim.expiresAt(),
                min(now.plus(properties.idle()), claim.expiresAt())));
    }

    /**
     * What a cookie value claims, if its MAC verifies (constant time) and it is still within its lifetime under the
     * current settings. Says nothing about whether the member, passkey or browser are still allowed: the caller checks.
     */
    public Optional<Claim> verify(String value) {
        if (!enabled() || value == null || value.length() > 2048) return Optional.empty();
        String[] parts = value.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) return Optional.empty();
        byte[] payload;
        byte[] given;
        try {
            payload = Base64.getUrlDecoder().decode(parts[1]);
            given = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(mac(payload), given)) return Optional.empty();
        String[] f = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
        if (f.length != 6 || !MEMBER.matcher(f[0]).matches() || !CREDENTIAL.matcher(f[1]).matches() || !NODE.matcher(f[2]).matches()) {
            return Optional.empty();
        }
        Claim claim;
        try {
            claim = new Claim(f[0], f[1], f[2], Instant.ofEpochSecond(Long.parseLong(f[3])), Instant.ofEpochSecond(Long.parseLong(f[4])),
                    Instant.ofEpochSecond(Long.parseLong(f[5])));
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return Optional.empty();
        }
        Instant now = now();
        if (claim.verifiedAt().isAfter(now.plus(SKEW))) return Optional.empty();
        // Under today's settings, whatever the cookie says: lowering max-age or idle shortens what was issued before.
        Instant expires = min(claim.expiresAt(), claim.verifiedAt().plus(properties.maxAge()));
        if (!now.isBefore(expires) || !now.isBefore(claim.idleUntil())) return Optional.empty();
        if (claim.idleUntil().isAfter(now.plus(properties.idle()).plus(SKEW))) return Optional.empty();
        return Optional.of(claim);
    }

    /** Removes the cookie from the browser: on sign-out, and whenever it is refused. */
    public static ResponseCookie cleared() {
        return ResponseCookie.from(COOKIE, "").path("/").secure(true).httpOnly(true).sameSite("Lax").maxAge(0).build();
    }

    private ResponseCookie cookie(Claim c) {
        byte[] payload = (c.member() + "|" + c.credentialId() + "|" + c.nodeId() + "|" + c.verifiedAt().getEpochSecond() + "|"
                + c.expiresAt().getEpochSecond() + "|" + c.idleUntil().getEpochSecond()).getBytes(StandardCharsets.UTF_8);
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String value = VERSION + "." + b64.encodeToString(payload) + "." + b64.encodeToString(mac(payload));
        long seconds = Math.max(0, Duration.between(now(), c.idleUntil()).toSeconds());
        return ResponseCookie.from(COOKIE, value).path("/").secure(true).httpOnly(true).sameSite("Lax").maxAge(seconds).build();
    }

    Instant now() {
        return clock.instant();
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private byte[] mac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(CONTEXT.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(payload);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
