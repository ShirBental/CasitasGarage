package home.casita.security.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.authentication.ott.DefaultOneTimeToken;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.authentication.ott.OneTimeTokenService;

/**
 * Invitations: one per device, for one family member, with the device's intended label. Single use and
 * short-lived. Only a SHA-256 of each token is kept, in memory: a restart voids every pending invite, and
 * nothing on disk can be replayed. Created only by the server-side admin ({@link #create}); Spring's public
 * token-generation endpoint is switched off.
 */
public class Invites implements OneTimeTokenService {

    public static final Duration LIFETIME = Duration.ofMinutes(5);

    /** A pending or redeemed invite. {@code redeemedAt} is set once its token has been used. */
    public record Invite(String id, String member, String label, Instant createdAt, Instant expiresAt, Instant redeemedAt) {
        public boolean expired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    /** A freshly created invite and its token: the token goes to the invitee and nowhere else. */
    public record Created(Invite invite, String token) {
        @Override
        public String toString() {
            return "Created[" + invite + "]";
        }
    }

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Invite> byTokenHash = new LinkedHashMap<>();
    /** Redeemed invites whose enrollment may still be running, by id: revoking one ends that enrollment. */
    private final Map<String, Invite> redeemed = new LinkedHashMap<>();

    public Invites(Clock clock) {
        this.clock = clock;
    }

    public synchronized Created create(String member, String label) {
        purge();
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        byte[] id = new byte[4];
        random.nextBytes(id);
        Instant now = clock.instant();
        Invite invite = new Invite("i-" + HexFormat.of().formatHex(id), member, label, now, now.plus(LIFETIME), null);
        byTokenHash.put(hash(token), invite);
        return new Created(invite, token);
    }

    /** Spring's token generation (the public /ott/generate) is not used: invites come from the admin only. */
    @Override
    public OneTimeToken generate(GenerateOneTimeTokenRequest request) {
        throw new UnsupportedOperationException("Invites are created by the server-side admin only");
    }

    /** Uses a token once. Unknown, expired or revoked: null, which Spring treats as a failed sign-in. */
    @Override
    public synchronized OneTimeToken consume(OneTimeTokenAuthenticationToken authenticationToken) {
        Optional<Invite> invite = redeem(authenticationToken.getTokenValue());
        return invite.map(i -> new DefaultOneTimeToken(authenticationToken.getTokenValue(), i.member(), i.expiresAt())).orElse(null);
    }

    public synchronized Optional<Invite> redeem(String token) {
        purge();
        if (token == null) return Optional.empty();
        Invite invite = byTokenHash.remove(hash(token));
        if (invite == null || invite.expired(clock.instant())) return Optional.empty();
        Invite used = new Invite(invite.id(), invite.member(), invite.label(), invite.createdAt(), invite.expiresAt(), clock.instant());
        redeemed.put(used.id(), used);
        return Optional.of(used);
    }

    /** The invite a running enrollment came from, if it hasn't been revoked or run out of time. */
    public synchronized Optional<Invite> activeEnrollment(String inviteId) {
        purge();
        return Optional.ofNullable(redeemed.get(inviteId));
    }

    /** The enrollment is done: the invite can't be used for anything else. */
    public synchronized void finish(String inviteId) {
        redeemed.remove(inviteId);
    }

    /** Revokes a pending invite, or ends an enrollment in progress. */
    public synchronized boolean revoke(String inviteId) {
        boolean pending = byTokenHash.values().removeIf(i -> i.id().equals(inviteId));
        boolean running = redeemed.remove(inviteId) != null;
        return pending || running;
    }

    public synchronized int revokeMember(String member) {
        int before = byTokenHash.size() + redeemed.size();
        byTokenHash.values().removeIf(i -> i.member().equals(member));
        redeemed.values().removeIf(i -> i.member().equals(member));
        return before - byTokenHash.size() - redeemed.size();
    }

    public synchronized List<Invite> pending() {
        purge();
        return List.copyOf(byTokenHash.values());
    }

    public synchronized List<Invite> enrolling() {
        purge();
        return List.copyOf(redeemed.values());
    }

    /** Drops what has expired: an unused invite at its expiry, an unfinished enrollment at the same time. */
    private void purge() {
        Instant now = clock.instant();
        byTokenHash.values().removeIf(i -> i.expired(now));
        redeemed.values().removeIf(i -> i.expired(now));
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
