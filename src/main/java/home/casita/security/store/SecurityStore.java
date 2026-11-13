package home.casita.security.store;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;

/**
 * Members, passkeys, device authorizations, invitations and Shortcut tokens: everything an access decision reads, in one
 * store, so enrolling a device is one atomic change (docs/PERSISTENCE.md). Three implementations with the
 * same contract: {@link FileSecurityStore} (local development), {@link DynamoSecurityStore} (AWS) and
 * {@link FirestoreSecurityStore} (GCP, docs/FIRESTORE.md).
 *
 * <p>Reads that decide access are strongly consistent. Any failure to reach the store is a
 * {@link StoreUnavailableException}, and callers refuse (fail closed). Revoking never deletes.
 */
public interface SecurityStore {

    enum Status { ACTIVE, REVOKED, RELEASED }

    enum InvitationStatus { PENDING, CONSUMED, REVOKED }

    record Member(String id, Status status, Bytes userHandle) {
        public boolean active() {
            return status == Status.ACTIVE;
        }
    }

    record Passkey(CredentialRecord record, String memberId, Status status, String enrolledNodeId, String inviteId) {
        public boolean activeFor(String member) {
            return status == Status.ACTIVE && memberId.equals(member);
        }

        public String id() {
            return record.getCredentialId().toBase64UrlString();
        }
    }

    /** A device's authorization, by its device id (a browser key, b-...; older records: a tailnet node): one per device, for at most one member. */
    record Device(String nodeId, String memberId, String label, String nodeName, String credentialId, String inviteId,
                  Status status, Instant authorizedAt, Instant revokedAt) {
        public boolean activeFor(String member) {
            return status == Status.ACTIVE && memberId.equals(member);
        }
    }

    /**
     * An invitation, by the SHA-256 of its token (the token itself is never stored). Expiry is decided here,
     * on every read, never by the store's own cleanup.
     */
    record Invitation(String tokenHash, String inviteId, String memberId, String label, InvitationStatus status,
                      Instant createdAt, Instant expiresAt, Instant consumedAt, String enrollmentId, String nodeId, String nodeName,
                      Instant enrolledAt, String enrolledCredentialId, Instant revokedAt) {
        public boolean expired(Instant now) {
            return !now.isBefore(expiresAt);
        }

        /** Consumed, not expired, not yet used to enroll: an enrollment may still complete. */
        public boolean enrolling(Instant now) {
            return status == InvitationStatus.CONSUMED && !expired(now) && enrolledAt == null;
        }

        @Override
        public String toString() {
            return "Invitation[" + inviteId + ", " + memberId + ", " + status + "]";
        }
    }

    /**
     * A Shortcut token (docs/SHORTCUTS.md), by the SHA-256 of the token: the token itself is never stored. {@code door} is
     * a door's API key ({@code ford}), {@code action} an action's name ({@code OPEN}). Expiry is decided here, on every
     * read, never by the store's own cleanup; revoking is a status, never a deletion.
     */
    record ShortcutToken(String tokenHash, String tokenId, String memberId, String label, String door, String action, Status status,
                         Instant createdAt, Instant expiresAt, Instant revokedAt) {
        public boolean revoked() {
            return status != Status.ACTIVE;
        }

        public boolean expired(Instant now) {
            return !now.isBefore(expiresAt);
        }

        @Override
        public String toString() {
            return "ShortcutToken[" + tokenId + ", " + memberId + ", " + action + " " + door + ", " + status + "]";
        }
    }

    /**
     * What an enrollment session holds, server-side only: which consumed invitation, the enrollment attempt
     * that consumed it, and the node it was consumed on.
     */
    record EnrollmentClaim(String tokenHash, String inviteId, String enrollmentId, String memberId, String label, String nodeId,
                           String nodeName) implements Serializable {
        @Override
        public String toString() {
            return "EnrollmentClaim[" + inviteId + ", " + memberId + "]";
        }
    }

    /** One consistent read of what a passkey session depends on. */
    record AccessView(Optional<Member> member, Optional<Passkey> passkey, Optional<Device> device) {}

    /** One consistent read of what an enrollment session depends on. */
    record EnrollmentView(Optional<Invitation> invitation, Optional<Member> member) {}

    /** What a Shortcut request depends on: its token and that token's member. */
    record ShortcutView(Optional<ShortcutToken> token, Optional<Member> member) {}

    record Cleanup(int devices, int passkeys, int invitations, int shortcutTokens) {}

    /** What revoking an invitation did. Only REVOKED and ALREADY_REVOKED mean its token can't be used. */
    enum RevokeOutcome {
        /** It was pending (or consumed, with no device enrolled yet) and is now revoked. */
        REVOKED,
        /** It had been revoked already. */
        ALREADY_REVOKED,
        /** It already enrolled a device: nothing to revoke; revoke the device instead. */
        ALREADY_ENROLLED,
        /** No such invitation. When looked up by id alone, possibly one not indexed yet: nothing was changed. */
        NOT_FOUND;

        public boolean revoked() {
            return this == REVOKED || this == ALREADY_REVOKED;
        }
    }

    /* ---------- members ---------- */

    /** Adds members that don't exist yet. Never revives a revoked one. */
    void seedMembers(Collection<String> memberIds);

    Optional<Member> member(String memberId);

    /** The member a WebAuthn user handle belongs to, verified on the member's own record. */
    Optional<Member> memberByUserHandle(Bytes userHandle);

    /** Gives an active member their WebAuthn user handle, once; false if they have a different one or aren't active. */
    boolean setUserHandle(String memberId, Bytes userHandle);

    /** Revokes a member: on its own, this refuses everything of theirs from the next request on. */
    boolean revokeMember(String memberId);

    /** After {@link #revokeMember}: revokes their devices, passkeys, open invitations and Shortcut tokens. Best effort, repeatable. */
    Cleanup cleanUpMember(String memberId);

    /* ---------- passkeys ---------- */

    Optional<Passkey> passkey(Bytes credentialId);

    List<Passkey> passkeysOf(String memberId);

    /** A sign-in's counter and time, only while the passkey is active and theirs. */
    boolean recordUse(CredentialRecord used, String memberId);

    boolean revokePasskey(Bytes credentialId);

    /* ---------- devices ---------- */

    Optional<Device> device(String nodeId);

    List<Device> devicesOf(String memberId);

    boolean revokeDevice(String nodeId);

    /** A revoked node may then be enrolled for any member, through a new invite. */
    boolean releaseDevice(String nodeId);

    /* ---------- invitations ---------- */

    /** Stores a new PENDING invitation; fails if its hash exists. */
    void createInvitation(Invitation pending);

    /**
     * Consumes a PENDING, unexpired invitation for a node: single use. A retry of the same attempt (same
     * request id, enrollment id and node) returns the same result; nothing else can consume it, change its
     * enrollment or node, or extend it. Empty when refused.
     */
    Optional<Invitation> consume(String tokenHash, String nodeId, String nodeName, String consumeRequestId, String enrollmentId);

    /** An invitation by its token hash, read consistently from the base table. */
    Optional<Invitation> invitation(String tokenHash);

    Optional<Invitation> invitationById(String inviteId);

    List<Invitation> invitationsOf(String memberId);

    /**
     * Revokes a PENDING invitation, or a CONSUMED one that hasn't enrolled a device, by its token hash: one
     * conditional write on the base table, no index involved. The invitation must also carry this id.
     */
    RevokeOutcome revokeInvitation(String tokenHash, String inviteId);

    /**
     * The same, by id alone (when the token is no longer at hand). An index finds the key, and it can lag a
     * just-created invitation: then NOT_FOUND, and nothing was changed.
     */
    RevokeOutcome revokeInvitation(String inviteId);

    /* ---------- Shortcut tokens ---------- */

    /**
     * Stores a new ACTIVE Shortcut token: one atomic write that fails if its hash exists. Refused with an
     * {@link IllegalStateException} if the hash or the id exists, or the member isn't active. (The id is 48 random bits;
     * its uniqueness is checked through an index, which in DynamoDB can lag a token created a moment ago.)
     */
    void createShortcutToken(ShortcutToken token);

    /**
     * The token with this hash, then its member: each read strongly consistent from the base table. A revocation that
     * lands between the two reads is the same as one that lands just after the request. Empty token: no such token.
     */
    ShortcutView shortcutView(String tokenHash);

    /** By id, through an index (which in DynamoDB can lag a token created a moment ago); the base table decides. */
    Optional<ShortcutToken> shortcutTokenById(String tokenId);

    List<ShortcutToken> shortcutTokensOf(String memberId);

    /**
     * Revokes a Shortcut token by id: a conditional status change on the base table. True if it is revoked afterwards
     * (by this call or an earlier one); false if there is no such token (in DynamoDB, possibly one not indexed yet).
     */
    boolean revokeShortcutToken(String tokenId);

    /* ---------- consistent views for access decisions ---------- */

    AccessView accessView(String memberId, Bytes credentialId, String nodeId);

    EnrollmentView enrollmentView(String tokenHash, String memberId);

    /* ---------- the enrollment transaction ---------- */

    /**
     * Completes an enrollment atomically: marks the invitation enrolled, checks the member is active, stores
     * the new passkey (or checks the existing one is active and theirs), and authorizes the node, which must
     * be new, revoked for this same member, or released. All or nothing. True only if, afterwards, durable
     * state shows this enrollment complete. Call only after the passkey has been verified and the request's
     * node re-verified as the claim's node.
     *
     * @param newPasskey          the passkey just registered, or null
     * @param existingCredentialId the member's existing passkey just used to sign in, or null
     */
    boolean enroll(EnrollmentClaim claim, CredentialRecord newPasskey, Bytes existingCredentialId);
}
