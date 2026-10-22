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
import java.util.Optional;

import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.authentication.ott.OneTimeTokenService;

import home.casita.security.store.SecurityStore;
import home.casita.security.store.SecurityStore.EnrollmentClaim;
import home.casita.security.store.SecurityStore.Invitation;
import home.casita.security.store.SecurityStore.InvitationStatus;

/**
 * Invitations: one per device, for one family member, with the device's intended label. 256 random bits,
 * five minutes, single use, revocable. Only the token's SHA-256 is stored (in the security store); the token
 * goes to the link file and nowhere else. Created only by the server-side admin; Spring's own token endpoints
 * are switched off ({@link #generate}, {@link #consume}): {@link #redeem} is the way in.
 */
public class Invitations implements OneTimeTokenService {

    public static final Duration LIFETIME = Duration.ofMinutes(15);

    /** A freshly created invitation and its token: the token goes to the invitee and nowhere else. */
    public record Created(Invitation invitation, String token) {
        @Override
        public String toString() {
            return "Created[" + invitation + "]";
        }
    }

    private final SecurityStore store;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public Invitations(SecurityStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public Created create(String member, String label) {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        Instant now = clock.instant();
        Invitation invitation = new Invitation(hash(token), "i-" + HexFormat.of().formatHex(randomBytes(4)), member, label, InvitationStatus.PENDING,
                now, now.plus(LIFETIME), null, null, null, null, null, null, null);
        store.createInvitation(invitation);
        return new Created(invitation, token);
    }

    /**
     * Consumes an invitation for a verified device: the start of an enrollment. The attempt's request id stays
     * here (only a retry of this very call reuses it); the enrollment id goes into the server-side session.
     */
    public Optional<EnrollmentClaim> redeem(String token, DeviceIdentity device) {
        if (token == null || token.isBlank()) return Optional.empty();
        String requestId = HexFormat.of().formatHex(randomBytes(16));
        String enrollmentId = HexFormat.of().formatHex(randomBytes(16));
        return store.consume(hash(token), device.nodeId(), device.nodeName(), requestId, enrollmentId)
                .map(i -> new EnrollmentClaim(i.tokenHash(), i.inviteId(), i.enrollmentId(), i.memberId(), i.label(), i.nodeId(), i.nodeName()));
    }

    /** Revokes by token (from the invite's link file): one conditional write on the base table, no index. */
    public SecurityStore.RevokeOutcome revoke(String token, String inviteId) {
        return store.revokeInvitation(hash(token), inviteId);
    }

    /** Revokes by token hash (an enrollment claim carries it). */
    public SecurityStore.RevokeOutcome revokeByHash(String tokenHash, String inviteId) {
        return store.revokeInvitation(tokenHash, inviteId);
    }

    /** Revokes by id alone, when the token isn't at hand: an index finds it, and may not list it yet (NOT_FOUND). */
    public SecurityStore.RevokeOutcome revokeById(String inviteId) {
        return store.revokeInvitation(inviteId);
    }

    /** Spring's token generation (the public /ott/generate) is not used: invitations come from the admin only. */
    @Override
    public OneTimeToken generate(GenerateOneTimeTokenRequest request) {
        throw new UnsupportedOperationException("Invitations are created by the server-side admin only");
    }

    /** Not used: EnrollmentAuthenticationProvider redeems with the verified device. */
    @Override
    public OneTimeToken consume(OneTimeTokenAuthenticationToken authenticationToken) {
        throw new UnsupportedOperationException("Invitations are redeemed with the verified device");
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
