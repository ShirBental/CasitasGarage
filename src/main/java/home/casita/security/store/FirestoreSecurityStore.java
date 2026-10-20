package home.casita.security.store;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;

import com.google.api.core.ApiFuture;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Blob;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreException;
import com.google.cloud.firestore.Precondition;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.WriteBatch;

/**
 * The security store in Firestore (docs/FIRESTORE.md), Native mode, the {@code (default)} database, through the
 * server client library with Application Default Credentials.
 *
 * <p>Every decision is a compare-and-set: read the documents it depends on (one strongly consistent read), decide in
 * code, then commit one atomic batch in which <em>every</em> document the decision read carries a precondition: its
 * {@code updateTime} is unchanged, or, for one that was absent, it is still absent ({@code create}). If anything
 * changed in between, nothing is written, and the decision is made again from a fresh read. This is DynamoDB's
 * conditional write, and it needs no locks: concurrent attempts can't hold each other up, and exactly one of them
 * changes a document. (Read-write transactions were tried first: under contention, their read locks made
 * contenders time out together in the emulator, see docs/FIRESTORE.md.)
 *
 * <p>Every decision is idempotent from stored state, so deciding again is always safe, including after a commit
 * whose answer was lost: the next read finds this attempt's own write (its {@code changeEventId}, request id or
 * enrollment) and gives the same answer without writing. The time and every random ID are fixed before the first
 * attempt. Attempts are bounded in number and time.
 *
 * <p>Queries only find documents; the fields that decide are checked on the documents themselves. Any error reading
 * Firestore, and no decision within the timeout, is a {@link StoreUnavailableException} (fail closed), except where
 * noted: an enrollment whose outcome is unknown is settled by reading what is stored.
 *
 * <p>Changes to access (consuming, enrolling, revoking, releasing, signing in) write a {@code securityEvents}
 * record in the same batch: the change and its record are committed together or not at all.
 */
public class FirestoreSecurityStore implements SecurityStore {

    private static final Logger log = LoggerFactory.getLogger(FirestoreSecurityStore.class);
    /** How long invitation records are kept after they expire, for investigation. A TTL policy removes them, eventually. */
    static final Duration INVITATION_RETENTION = Duration.ofDays(30);
    /** How long security events are kept. A TTL policy on {@code expiresAt} removes them, eventually. */
    static final Duration EVENT_RETENTION = Duration.ofDays(400);
    /** Decisions per call, conflicts and unknown outcomes included, before giving up. Then the store is unavailable. */
    static final int ATTEMPTS = 6;

    /** A security event, as stored. Holds identifiers and outcomes only: no token, session, address or key. */
    public record SecurityEvent(String id, String type, Instant at, String memberId, String nodeId, String inviteId, String credentialId,
                                String outcome, String reason) {}

    private enum Enrolled { COMPLETED, ALREADY_COMPLETE, REFUSED }

    /** What a decision answers, and the batch that makes it true (null: nothing to write). */
    private record Decision<T>(T answer, WriteBatch writes) {
        static <T> Decision<T> answer(T answer) {
            return new Decision<>(answer, null);
        }
    }

    @FunctionalInterface
    private interface Decide<T> {
        Decision<T> decide();
    }

    private final Firestore db;
    private final FirestoreCollections collections;
    private final Clock clock;
    private final Duration timeout;

    public FirestoreSecurityStore(Firestore db, FirestoreCollections collections, Clock clock, Duration timeout) {
        this.db = db;
        this.collections = collections;
        this.clock = clock;
        this.timeout = timeout;
    }

    /* ---------- members ---------- */

    @Override
    public void seedMembers(Collection<String> memberIds) {
        for (String id : memberIds) {
            DocumentReference ref = collections.member(db, id);
            if (read(ref).exists()) continue; // exists already, active or revoked: left as it is, and nothing written
            Map<String, Object> doc = new HashMap<>();
            doc.put("memberId", id);
            doc.put("status", Status.ACTIVE.name());
            doc.put("createdAt", ts(now()));
            try {
                await(ref.create(doc));
            } catch (StoreUnavailableException e) {
                if (!alreadyExists(e)) throw e; // created meanwhile: left as it is
            }
        }
    }

    @Override
    public Optional<Member> member(String memberId) {
        if (!FirestoreCollections.valid(memberId)) return Optional.empty();
        return get(collections.member(db, memberId)).map(FirestoreSecurityStore::toMember);
    }

    @Override
    public Optional<Member> memberByUserHandle(Bytes userHandle) {
        String handle = userHandle.toBase64UrlString();
        if (!FirestoreCollections.valid(handle)) return Optional.empty();
        Optional<String> memberId = get(collections.userHandle(db, handle)).map(d -> d.getString("memberId"));
        // The lookup only finds a member; their own record decides.
        return memberId.flatMap(this::member).filter(m -> m.userHandle() != null && handle.equals(m.userHandle().toBase64UrlString()));
    }

    @Override
    public boolean setUserHandle(String memberId, Bytes userHandle) {
        String handle = userHandle.toBase64UrlString();
        if (!FirestoreCollections.valid(memberId) || !FirestoreCollections.valid(handle)) return false;
        Instant now = now();
        DocumentReference memberRef = collections.member(db, memberId);
        DocumentReference handleRef = collections.userHandle(db, handle);
        return decided(() -> {
            List<DocumentSnapshot> read = getAll(memberRef, handleRef);
            DocumentSnapshot member = read.get(0), owner = read.get(1);
            if (!member.exists() || !Status.ACTIVE.name().equals(member.getString("status"))) return Decision.answer(false);
            String current = member.getString("userHandle");
            if (current != null) return Decision.answer(current.equals(handle));
            if (owner.exists()) return Decision.answer(false); // someone else's handle: a handle belongs to one member
            WriteBatch batch = db.batch();
            batch.update(memberRef, Map.of("userHandle", handle, "updatedAt", ts(now)), unchanged(member));
            batch.create(handleRef, Map.of("memberId", memberId, "createdAt", ts(now)));
            return new Decision<>(true, batch);
        });
    }

    @Override
    public boolean revokeMember(String memberId) {
        if (!FirestoreCollections.valid(memberId)) return false;
        Instant now = now();
        String eventId = newEventId();
        DocumentReference ref = collections.member(db, memberId);
        return decided(() -> {
            DocumentSnapshot member = read(ref);
            if (!member.exists()) return Decision.answer(false);
            if (Status.REVOKED.name().equals(member.getString("status"))) return Decision.answer(true);
            WriteBatch batch = db.batch();
            batch.update(ref, Map.of("status", Status.REVOKED.name(), "revokedAt", ts(now), "updatedAt", ts(now), "changeEventId", eventId),
                    unchanged(member));
            event(batch, eventId, now, "MEMBER_REVOKED", memberId, null, null, null, "OK", null);
            return new Decision<>(true, batch);
        });
    }

    @Override
    public Cleanup cleanUpMember(String memberId) {
        int devices = 0, passkeys = 0, invitations = 0;
        for (DocumentSnapshot d : query(db.collection(collections.devices()).whereEqualTo("memberId", memberId))) {
            if (revokeDevice(d.getString("nodeId"))) devices++;
        }
        for (DocumentSnapshot p : query(db.collection(collections.passkeys()).whereEqualTo("memberId", memberId))) {
            if (revokePasskey(Bytes.fromBase64(p.getString("credentialId")))) passkeys++;
        }
        for (Invitation invitation : invitationsOf(memberId)) {
            if (revokeInvitation(invitation.tokenHash(), invitation.inviteId()) == RevokeOutcome.REVOKED) invitations++;
        }
        return new Cleanup(devices, passkeys, invitations);
    }

    /* ---------- passkeys ---------- */

    @Override
    public Optional<Passkey> passkey(Bytes credentialId) {
        String id = credentialId.toBase64UrlString();
        if (!FirestoreCollections.valid(id)) return Optional.empty();
        return get(collections.passkey(db, id)).map(FirestoreSecurityStore::toPasskey);
    }

    @Override
    public List<Passkey> passkeysOf(String memberId) {
        List<Passkey> result = new ArrayList<>();
        for (DocumentSnapshot d : query(db.collection(collections.passkeys()).whereEqualTo("memberId", memberId))) {
            Passkey p = toPasskey(d);
            if (p.activeFor(memberId)) result.add(p);
        }
        result.sort(Comparator.comparing(p -> p.record().getCreated()));
        return result;
    }

    @Override
    public boolean recordUse(CredentialRecord used, String memberId) {
        String id = used.getCredentialId().toBase64UrlString();
        if (!FirestoreCollections.valid(id)) return false;
        Instant now = now();
        Instant usedAt = used.getLastUsed() == null ? now : used.getLastUsed().truncatedTo(ChronoUnit.MICROS);
        String eventId = newEventId();
        DocumentReference ref = collections.passkey(db, id);
        return decided(() -> {
            DocumentSnapshot passkey = read(ref);
            if (!passkey.exists() || !Status.ACTIVE.name().equals(passkey.getString("status")) || !memberId.equals(passkey.getString("memberId"))) {
                return Decision.answer(false);
            }
            if (eventId.equals(passkey.getString("changeEventId"))) return Decision.answer(true); // this call, decided again
            WriteBatch batch = db.batch();
            batch.update(ref, Map.of("signatureCount", used.getSignatureCount(), "lastUsedAt", ts(usedAt), "backupState", used.isBackupState(),
                    "changeEventId", eventId), unchanged(passkey));
            event(batch, eventId, now, "SIGNED_IN", memberId, null, null, id, "OK", null);
            return new Decision<>(true, batch);
        });
    }

    @Override
    public boolean revokePasskey(Bytes credentialId) {
        String id = credentialId.toBase64UrlString();
        if (!FirestoreCollections.valid(id)) return false;
        return setStatus(collections.passkey(db, id), Status.ACTIVE, Status.REVOKED, "revokedAt", "PASSKEY_REVOKED", null, id);
    }

    /* ---------- devices ---------- */

    @Override
    public Optional<Device> device(String nodeId) {
        if (!FirestoreCollections.valid(nodeId)) return Optional.empty();
        return get(collections.device(db, nodeId)).map(FirestoreSecurityStore::toDevice);
    }

    @Override
    public List<Device> devicesOf(String memberId) {
        List<Device> result = new ArrayList<>();
        for (DocumentSnapshot d : query(db.collection(collections.devices()).whereEqualTo("memberId", memberId))) {
            Device device = toDevice(d);
            if (device.memberId().equals(memberId)) result.add(device);
        }
        return result;
    }

    @Override
    public boolean revokeDevice(String nodeId) {
        if (!FirestoreCollections.valid(nodeId)) return false;
        return setStatus(collections.device(db, nodeId), Status.ACTIVE, Status.REVOKED, "revokedAt", "DEVICE_REVOKED", nodeId, null);
    }

    @Override
    public boolean releaseDevice(String nodeId) {
        if (!FirestoreCollections.valid(nodeId)) return false;
        return setStatus(collections.device(db, nodeId), Status.REVOKED, Status.RELEASED, "releasedAt", "DEVICE_RELEASED", nodeId, null);
    }

    /* ---------- invitations ---------- */

    @Override
    public void createInvitation(Invitation pending) {
        if (pending.status() != InvitationStatus.PENDING) throw new IllegalArgumentException("a new invitation is PENDING");
        Instant now = now();
        String eventId = newEventId();
        Map<String, Object> doc = new HashMap<>();
        doc.put("tokenHash", pending.tokenHash());
        doc.put("inviteId", pending.inviteId());
        doc.put("memberId", pending.memberId());
        doc.put("label", pending.label());
        doc.put("status", InvitationStatus.PENDING.name());
        doc.put("createdAt", ts(pending.createdAt()));
        doc.put("expiresAt", ts(pending.expiresAt()));
        // Cleanup only, set once: nothing reads it, and nothing changes it later.
        doc.put("ttl", ts(pending.expiresAt().plus(INVITATION_RETENTION)));
        doc.put("changeEventId", eventId);
        DocumentReference ref = collections.invitation(db, pending.tokenHash());
        boolean created = decided(() -> {
            DocumentSnapshot existing = read(ref);
            if (existing.exists()) return Decision.answer(eventId.equals(existing.getString("changeEventId"))); // ours (decided again), or a refusal
            WriteBatch batch = db.batch();
            batch.create(ref, doc);
            event(batch, eventId, now, "INVITATION_CREATED", pending.memberId(), null, pending.inviteId(), null, "OK", null);
            return new Decision<>(true, batch);
        });
        if (!created) throw new IllegalStateException("invitation exists");
    }

    @Override
    public Optional<Invitation> consume(String tokenHash, String nodeId, String nodeName, String consumeRequestId, String enrollmentId) {
        if (!FirestoreCollections.valid(tokenHash) || !FirestoreCollections.valid(nodeId)) return Optional.empty();
        Instant now = now();
        String eventId = newEventId();
        DocumentReference ref = collections.invitation(db, tokenHash);
        String name = nodeName == null ? "" : nodeName;
        return decided(() -> {
            DocumentSnapshot doc = read(ref);
            if (!doc.exists()) return Decision.<Optional<Invitation>>answer(Optional.empty()); // an unknown token writes nothing, not even an event
            Invitation stored = toInvitation(doc);
            if (stored.expired(now)) return refusal(Optional.empty(), eventId, now, "INVITATION_REFUSED", stored, nodeId, "expired");
            if (stored.status() == InvitationStatus.CONSUMED && consumeRequestId.equals(doc.getString("consumeRequestId"))
                    && enrollmentId.equals(stored.enrollmentId()) && nodeId.equals(stored.nodeId()) && stored.enrolledAt() == null) {
                return Decision.answer(Optional.of(stored)); // this attempt consumed it (perhaps a lost answer): the same answer, nothing written
            }
            if (stored.status() != InvitationStatus.PENDING) {
                return refusal(Optional.empty(), eventId, now, "INVITATION_REFUSED", stored, nodeId,
                        stored.status() == InvitationStatus.REVOKED ? "revoked" : "already-used");
            }
            Instant consumedAt = stored.consumedAt() != null ? stored.consumedAt() : now;
            WriteBatch batch = db.batch();
            batch.update(ref, Map.of("status", InvitationStatus.CONSUMED.name(), "consumedAt", ts(consumedAt), "consumeRequestId", consumeRequestId,
                    "enrollmentId", enrollmentId, "nodeId", nodeId, "nodeName", name, "changeEventId", eventId), unchanged(doc));
            event(batch, eventId, now, "INVITATION_CONSUMED", stored.memberId(), nodeId, stored.inviteId(), null, "OK", null);
            return new Decision<>(Optional.of(new Invitation(stored.tokenHash(), stored.inviteId(), stored.memberId(), stored.label(),
                    InvitationStatus.CONSUMED, stored.createdAt(), stored.expiresAt(), consumedAt, enrollmentId, nodeId, name, null, null,
                    stored.revokedAt())), batch);
        });
    }

    /** A refusal of a known invitation: nothing changes but the record of it (written again, identically, if decided again). */
    private <T> Decision<T> refusal(T answer, String eventId, Instant now, String type, Invitation stored, String nodeId, String reason) {
        WriteBatch batch = db.batch();
        event(batch, eventId, now, type, stored.memberId(), nodeId, stored.inviteId(), null, "REFUSED", reason);
        return new Decision<>(answer, batch);
    }

    @Override
    public Optional<Invitation> invitation(String tokenHash) {
        if (!FirestoreCollections.valid(tokenHash)) return Optional.empty();
        return get(collections.invitation(db, tokenHash)).map(FirestoreSecurityStore::toInvitation);
    }

    @Override
    public Optional<Invitation> invitationById(String inviteId) {
        for (DocumentSnapshot d : query(db.collection(collections.invitations()).whereEqualTo("inviteId", inviteId).limit(2))) {
            Invitation invitation = toInvitation(d);
            if (invitation.inviteId().equals(inviteId)) return Optional.of(invitation);
        }
        return Optional.empty();
    }

    @Override
    public List<Invitation> invitationsOf(String memberId) {
        List<Invitation> result = new ArrayList<>();
        for (DocumentSnapshot d : query(db.collection(collections.invitations()).whereEqualTo("memberId", memberId))) {
            Invitation invitation = toInvitation(d);
            if (invitation.memberId().equals(memberId)) result.add(invitation);
        }
        result.sort(Comparator.comparing(Invitation::createdAt));
        return result;
    }

    @Override
    public RevokeOutcome revokeInvitation(String tokenHash, String inviteId) {
        if (!FirestoreCollections.valid(tokenHash)) return RevokeOutcome.NOT_FOUND;
        Instant now = now();
        String eventId = newEventId();
        DocumentReference ref = collections.invitation(db, tokenHash);
        return decided(() -> {
            DocumentSnapshot doc = read(ref);
            if (!doc.exists() || !inviteId.equals(doc.getString("inviteId"))) return Decision.answer(RevokeOutcome.NOT_FOUND);
            Invitation stored = toInvitation(doc);
            if (stored.status() == InvitationStatus.REVOKED) {
                // Revoked by this very call (decided again after a lost answer), or by an earlier one.
                return Decision.answer(eventId.equals(doc.getString("changeEventId")) ? RevokeOutcome.REVOKED : RevokeOutcome.ALREADY_REVOKED);
            }
            if (stored.enrolledAt() != null) return Decision.answer(RevokeOutcome.ALREADY_ENROLLED);
            WriteBatch batch = db.batch();
            batch.update(ref, Map.of("status", InvitationStatus.REVOKED.name(), "revokedAt", ts(now), "changeEventId", eventId), unchanged(doc));
            event(batch, eventId, now, "INVITATION_REVOKED", stored.memberId(), stored.nodeId(), inviteId, null, "OK", null);
            return new Decision<>(RevokeOutcome.REVOKED, batch);
        });
    }

    /** By id alone. Firestore's queries are strongly consistent, so a just-created invitation is found too. */
    @Override
    public RevokeOutcome revokeInvitation(String inviteId) {
        return invitationById(inviteId).map(i -> revokeInvitation(i.tokenHash(), inviteId)).orElse(RevokeOutcome.NOT_FOUND);
    }

    /* ---------- consistent views ---------- */

    /** One BatchGetDocuments: the three documents as of one read time, strongly consistent. */
    @Override
    public AccessView accessView(String memberId, Bytes credentialId, String nodeId) {
        String credential = credentialId.toBase64UrlString();
        List<Optional<DocumentSnapshot>> docs = present(getAll(
                FirestoreCollections.valid(memberId) ? collections.member(db, memberId) : null,
                FirestoreCollections.valid(credential) ? collections.passkey(db, credential) : null,
                FirestoreCollections.valid(nodeId) ? collections.device(db, nodeId) : null));
        return new AccessView(docs.get(0).map(FirestoreSecurityStore::toMember), docs.get(1).map(FirestoreSecurityStore::toPasskey),
                docs.get(2).map(FirestoreSecurityStore::toDevice));
    }

    @Override
    public EnrollmentView enrollmentView(String tokenHash, String memberId) {
        List<Optional<DocumentSnapshot>> docs = present(getAll(
                FirestoreCollections.valid(tokenHash) ? collections.invitation(db, tokenHash) : null,
                FirestoreCollections.valid(memberId) ? collections.member(db, memberId) : null));
        return new EnrollmentView(docs.get(0).map(FirestoreSecurityStore::toInvitation), docs.get(1).map(FirestoreSecurityStore::toMember));
    }

    /* ---------- the enrollment ---------- */

    /**
     * One atomic batch, conditioned on all four documents the decision read: the invitation and the member as read,
     * the passkey still absent (new) or as read (existing), the node still absent or as read. The member and an
     * existing passkey aren't changed by enrolling, so they are touched ({@code lastEnrollmentAt}) to carry their
     * condition: a revocation of either that lands first makes the batch fail and the decision is made again.
     */
    @Override
    public boolean enroll(EnrollmentClaim claim, CredentialRecord newPasskey, Bytes existingCredentialId) {
        if ((newPasskey == null) == (existingCredentialId == null)) throw new IllegalArgumentException("a new passkey or an existing one");
        String credentialId = newPasskey != null ? newPasskey.getCredentialId().toBase64UrlString() : existingCredentialId.toBase64UrlString();
        if (!FirestoreCollections.valid(claim.tokenHash()) || !FirestoreCollections.valid(claim.memberId())
                || !FirestoreCollections.valid(claim.nodeId()) || !FirestoreCollections.valid(credentialId)) {
            return false;
        }
        Instant now = now();
        String eventId = newEventId();
        DocumentReference invitationRef = collections.invitation(db, claim.tokenHash());
        DocumentReference memberRef = collections.member(db, claim.memberId());
        DocumentReference passkeyRef = collections.passkey(db, credentialId);
        DocumentReference deviceRef = collections.device(db, claim.nodeId());
        Map<String, Object> newPasskeyDoc = newPasskey == null ? null : passkeyDoc(newPasskey, claim, now);

        Enrolled outcome;
        try {
            outcome = decided(() -> {
                List<DocumentSnapshot> read = getAll(invitationRef, memberRef, passkeyRef, deviceRef);
                DocumentSnapshot invitation = read.get(0), member = read.get(1), passkey = read.get(2), device = read.get(3);
                if (!invitation.exists()) return Decision.answer(Enrolled.REFUSED);
                Invitation i = toInvitation(invitation);

                if (i.enrolledAt() != null) {
                    // Idempotent by state: this attempt already committed (perhaps with its answer lost), at any time since.
                    return completed(claim, credentialId, i, device, passkey) ? Decision.answer(Enrolled.ALREADY_COMPLETE)
                            : refuse(eventId, now, claim, "invitation-used");
                }
                if (i.status() != InvitationStatus.CONSUMED || !claim.inviteId().equals(i.inviteId())
                        || !Objects.equals(claim.enrollmentId(), i.enrollmentId()) || !claim.nodeId().equals(i.nodeId())
                        || !claim.memberId().equals(i.memberId())) {
                    return refuse(eventId, now, claim, "claim-mismatch");
                }
                if (i.expired(now)) return refuse(eventId, now, claim, "expired");
                if (!member.exists() || !Status.ACTIVE.name().equals(member.getString("status"))) return refuse(eventId, now, claim, "member-inactive");
                if (newPasskeyDoc != null) {
                    if (passkey.exists()) return refuse(eventId, now, claim, "credential-exists");
                } else if (!passkey.exists() || !toPasskey(passkey).activeFor(claim.memberId())) {
                    return refuse(eventId, now, claim, "credential-inactive");
                }
                if (device.exists()) {
                    String status = device.getString("status");
                    boolean reusable = Status.RELEASED.name().equals(status)
                            || (Status.REVOKED.name().equals(status) && claim.memberId().equals(device.getString("memberId")));
                    if (!reusable) return refuse(eventId, now, claim, "node-taken");
                }

                WriteBatch batch = db.batch();
                batch.update(invitationRef, Map.of("enrolledAt", ts(now), "enrolledCredentialId", credentialId, "changeEventId", eventId),
                        unchanged(invitation));
                batch.update(memberRef, Map.of("lastEnrollmentAt", ts(now)), unchanged(member));
                if (newPasskeyDoc != null) batch.create(passkeyRef, newPasskeyDoc);
                else batch.update(passkeyRef, Map.of("lastEnrollmentAt", ts(now)), unchanged(passkey));
                if (device.exists()) {
                    Map<String, Object> replaced = new HashMap<>(deviceDoc(claim, credentialId, now));
                    replaced.put("revokedAt", FieldValue.delete());
                    replaced.put("releasedAt", FieldValue.delete());
                    replaced.put("changeEventId", FieldValue.delete());
                    batch.update(deviceRef, replaced, unchanged(device));
                } else {
                    batch.create(deviceRef, deviceDoc(claim, credentialId, now));
                }
                event(batch, eventId, now, "DEVICE_ENROLLED", claim.memberId(), claim.nodeId(), claim.inviteId(), credentialId, "OK", null);
                return new Decision<>(Enrolled.COMPLETED, batch);
            });
        } catch (StoreUnavailableException e) {
            // No decision in time: the last batch may or may not have been applied, entirely or not at all. Read, don't resend.
            log.warn("Enrollment for invite {} has an unknown outcome ({}); checking what is stored", claim.inviteId(), causeName(e));
            return completed(claim, credentialId);
        }
        if (outcome == Enrolled.REFUSED) log.info("Enrollment for invite {} refused by what is stored", claim.inviteId());
        return outcome != Enrolled.REFUSED;
    }

    private Decision<Enrolled> refuse(String eventId, Instant now, EnrollmentClaim claim, String reason) {
        WriteBatch batch = db.batch();
        event(batch, eventId, now, "ENROLLMENT_REFUSED", claim.memberId(), claim.nodeId(), claim.inviteId(), null, "REFUSED", reason);
        return new Decision<>(Enrolled.REFUSED, batch);
    }

    /**
     * Whether stored state shows this enrollment complete: invitation enrolled by this attempt with this passkey, the
     * node authorized for the member by this invite, the passkey active and theirs. If even this read fails, false:
     * fail closed (the state is whole either way; a later sign-in will find it).
     */
    private boolean completed(EnrollmentClaim claim, String credentialId) {
        try {
            List<DocumentSnapshot> docs = getAll(collections.invitation(db, claim.tokenHash()), collections.device(db, claim.nodeId()),
                    collections.passkey(db, credentialId));
            return docs.get(0).exists() && completed(claim, credentialId, toInvitation(docs.get(0)), docs.get(1), docs.get(2));
        } catch (StoreUnavailableException e) {
            log.warn("Couldn't read back the enrollment for invite {}; refusing", claim.inviteId());
            return false;
        }
    }

    private static boolean completed(EnrollmentClaim claim, String credentialId, Invitation i, DocumentSnapshot device, DocumentSnapshot passkey) {
        return i.enrolledAt() != null && Objects.equals(i.enrollmentId(), claim.enrollmentId()) && credentialId.equals(i.enrolledCredentialId())
                && device.exists() && toDevice(device).activeFor(claim.memberId()) && claim.inviteId().equals(device.getString("inviteId"))
                && passkey.exists() && toPasskey(passkey).activeFor(claim.memberId());
    }

    /* ---------- security events ---------- */

    /** The newest security events, newest first. For the admin and for tests; nothing decides access from them. */
    public List<SecurityEvent> securityEvents(int limit) {
        List<SecurityEvent> events = new ArrayList<>();
        for (DocumentSnapshot d : query(db.collection(collections.securityEvents()).orderBy("at", Query.Direction.DESCENDING).limit(limit))) {
            events.add(new SecurityEvent(d.getId(), d.getString("type"), time(d, "at"), d.getString("memberId"), d.getString("nodeId"),
                    d.getString("inviteId"), d.getString("credentialId"), d.getString("outcome"), d.getString("reason")));
        }
        return events;
    }

    /** Random, so an event's ID says nothing about the event; made before the first attempt, so deciding again rewrites the same one. */
    private static String newEventId() {
        return "e_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** {@code set}, not {@code create}: the same event written again by the same call is the same document, once. */
    private void event(WriteBatch batch, String eventId, Instant now, String type, String memberId, String nodeId, String inviteId,
                       String credentialId, String outcome, String reason) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("type", type);
        doc.put("at", ts(now));
        doc.put("outcome", outcome);
        doc.put("expiresAt", ts(now.plus(EVENT_RETENTION)));
        if (memberId != null) doc.put("memberId", memberId);
        if (nodeId != null) doc.put("nodeId", nodeId);
        if (inviteId != null) doc.put("inviteId", inviteId);
        if (credentialId != null) doc.put("credentialId", credentialId);
        if (reason != null) doc.put("reason", reason);
        batch.set(collections.securityEvent(db, eventId), doc);
    }

    /* ---------- deciding, reading, failing ---------- */

    /**
     * Decides, writes, and if the write is refused (something changed since the read) or its outcome unknown, reads
     * and decides again: at most {@link #ATTEMPTS} times, all within the timeout, with a short random pause between, so
     * contenders don't collide again in step. A read that fails is a failure at once: fail closed.
     */
    private <T> T decided(Decide<T> decide) {
        long deadline = System.nanoTime() + timeout.toNanos();
        StoreUnavailableException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            Decision<T> d = decide.decide();
            if (d.writes() == null) return d.answer();
            long left = deadline - System.nanoTime();
            if (left <= 0) break;
            try {
                await(d.writes().commit(), Duration.ofNanos(left));
                return d.answer();
            } catch (StoreUnavailableException e) {
                last = e;
                if (attempt < ATTEMPTS) pause(attempt);
            }
        }
        throw new StoreUnavailableException("Firestore: no decision after " + ATTEMPTS + " attempts or " + timeout, last);
    }

    private static void pause(int attempt) {
        long max = Math.min(400, 20L << attempt);
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(max / 4, max + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreUnavailableException("interrupted", e);
        }
    }

    private boolean setStatus(DocumentReference ref, Status from, Status to, String timeField, String eventType, String nodeId, String credentialId) {
        Instant now = now();
        String eventId = newEventId();
        return decided(() -> {
            DocumentSnapshot doc = read(ref);
            if (!doc.exists()) return Decision.answer(false);
            if (to.name().equals(doc.getString("status")) && eventId.equals(doc.getString("changeEventId"))) return Decision.answer(true); // this call
            if (!from.name().equals(doc.getString("status"))) return Decision.answer(false);
            WriteBatch batch = db.batch();
            batch.update(ref, Map.of("status", to.name(), timeField, ts(now), "changeEventId", eventId), unchanged(doc));
            event(batch, eventId, now, eventType, doc.getString("memberId"), nodeId, null, credentialId, "OK", null);
            return new Decision<>(true, batch);
        });
    }

    /** The write's condition: the document is exactly as the decision read it. */
    private static Precondition unchanged(DocumentSnapshot read) {
        return Precondition.updatedAt(read.getUpdateTime());
    }

    /** Firestore keeps microseconds: a time is what it will read back as. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private DocumentSnapshot read(DocumentReference ref) {
        return await(ref.get());
    }

    private Optional<DocumentSnapshot> get(DocumentReference ref) {
        DocumentSnapshot doc = read(ref);
        return doc.exists() ? Optional.of(doc) : Optional.empty();
    }

    /**
     * One batched read, all documents as of one read time, in the order asked. A null reference (an identifier that can't
     * be stored) reads as an absent document.
     */
    private List<DocumentSnapshot> getAll(DocumentReference... refs) {
        List<DocumentReference> present = new ArrayList<>();
        for (DocumentReference r : refs) if (r != null) present.add(r);
        Map<String, DocumentSnapshot> byPath = new HashMap<>();
        if (!present.isEmpty()) {
            for (DocumentSnapshot d : await(db.getAll(present.toArray(DocumentReference[]::new)))) byPath.put(d.getReference().getPath(), d);
        }
        List<DocumentSnapshot> result = new ArrayList<>();
        for (DocumentReference r : refs) result.add(r == null ? null : byPath.get(r.getPath()));
        return result;
    }

    private static List<Optional<DocumentSnapshot>> present(List<DocumentSnapshot> docs) {
        return docs.stream().map(d -> d != null && d.exists() ? Optional.of(d) : Optional.<DocumentSnapshot>empty()).toList();
    }

    private List<QueryDocumentSnapshot> query(Query query) {
        return await(query.get()).getDocuments();
    }

    private <T> T await(ApiFuture<T> future) {
        return await(future, timeout);
    }

    /** Waits for Firestore, within a limit. Anything but an answer means the store is unavailable: fail closed. */
    private static <T> T await(ApiFuture<T> future, Duration limit) {
        try {
            return future.get(limit.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new StoreUnavailableException("Firestore call interrupted", e);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new StoreUnavailableException("Firestore didn't answer in " + limit, e);
        } catch (ExecutionException e) {
            throw new StoreUnavailableException("Firestore unavailable", e.getCause());
        }
    }

    private static boolean alreadyExists(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ApiException api && api.getStatusCode().getCode() == StatusCode.Code.ALREADY_EXISTS) return true;
            if (t instanceof FirestoreException fe && fe.getStatus() != null && fe.getStatus().getCode() == io.grpc.Status.Code.ALREADY_EXISTS) {
                return true;
            }
        }
        return false;
    }

    private static String causeName(StoreUnavailableException e) {
        Throwable c = e.getCause();
        while (c != null && c.getCause() != null && c instanceof StoreUnavailableException) c = c.getCause();
        return c == null ? "no cause" : c.getClass().getSimpleName();
    }

    /* ---------- mapping ---------- */

    private static Timestamp ts(Instant instant) {
        return Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
    }

    private static Instant time(DocumentSnapshot d, String field) {
        Timestamp t = d.getTimestamp(field);
        return t == null ? null : Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
    }

    private static Bytes bytes(DocumentSnapshot d, String field) {
        Blob b = d.getBlob(field);
        return b == null ? null : new Bytes(b.toBytes());
    }

    private static boolean bool(DocumentSnapshot d, String field) {
        return Boolean.TRUE.equals(d.getBoolean(field));
    }

    private static Member toMember(DocumentSnapshot d) {
        String handle = d.getString("userHandle");
        return new Member(d.getString("memberId"), Status.valueOf(d.getString("status")), handle == null ? null : Bytes.fromBase64(handle));
    }

    private static Device toDevice(DocumentSnapshot d) {
        return new Device(d.getString("nodeId"), d.getString("memberId"), d.getString("label"), d.getString("nodeName"), d.getString("credentialId"),
                d.getString("inviteId"), Status.valueOf(d.getString("status")), time(d, "authorizedAt"), time(d, "revokedAt"));
    }

    private static Invitation toInvitation(DocumentSnapshot d) {
        return new Invitation(d.getString("tokenHash"), d.getString("inviteId"), d.getString("memberId"), d.getString("label"),
                InvitationStatus.valueOf(d.getString("status")), time(d, "createdAt"), time(d, "expiresAt"), time(d, "consumedAt"),
                d.getString("enrollmentId"), d.getString("nodeId"), d.getString("nodeName"), time(d, "enrolledAt"), d.getString("enrolledCredentialId"),
                time(d, "revokedAt"));
    }

    private static Map<String, Object> deviceDoc(EnrollmentClaim claim, String credentialId, Instant now) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("nodeId", claim.nodeId());
        doc.put("memberId", claim.memberId());
        doc.put("label", claim.label());
        doc.put("nodeName", claim.nodeName() == null ? "" : claim.nodeName());
        doc.put("credentialId", credentialId);
        doc.put("inviteId", claim.inviteId());
        doc.put("status", Status.ACTIVE.name());
        doc.put("authorizedAt", ts(now));
        return doc;
    }

    private static Map<String, Object> passkeyDoc(CredentialRecord r, EnrollmentClaim claim, Instant now) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("credentialId", r.getCredentialId().toBase64UrlString());
        doc.put("memberId", claim.memberId());
        doc.put("userHandle", r.getUserEntityUserId().toBase64UrlString());
        doc.put("publicKeyCose", Blob.fromBytes(r.getPublicKey().getBytes()));
        doc.put("signatureCount", r.getSignatureCount());
        doc.put("uvInitialized", r.isUvInitialized());
        doc.put("backupEligible", r.isBackupEligible());
        doc.put("backupState", r.isBackupState());
        if (r.getTransports() != null && !r.getTransports().isEmpty()) {
            doc.put("transports", r.getTransports().stream().map(AuthenticatorTransport::getValue).sorted().toList());
        }
        if (r.getAttestationObject() != null) doc.put("attestationObject", Blob.fromBytes(r.getAttestationObject().getBytes()));
        if (r.getAttestationClientDataJSON() != null) doc.put("attestationClientDataJson", Blob.fromBytes(r.getAttestationClientDataJSON().getBytes()));
        if (r.getLabel() != null) doc.put("label", r.getLabel());
        doc.put("createdAt", ts(r.getCreated() == null ? now : r.getCreated().truncatedTo(ChronoUnit.MICROS)));
        if (r.getLastUsed() != null) doc.put("lastUsedAt", ts(r.getLastUsed().truncatedTo(ChronoUnit.MICROS)));
        doc.put("status", Status.ACTIVE.name());
        doc.put("enrolledNodeId", claim.nodeId());
        doc.put("inviteId", claim.inviteId());
        return doc;
    }

    @SuppressWarnings("unchecked")
    private static Passkey toPasskey(DocumentSnapshot d) {
        List<String> stored = (List<String>) d.get("transports");
        Set<AuthenticatorTransport> transports = (stored == null ? List.<String>of() : stored).stream().map(AuthenticatorTransport::valueOf)
                .collect(Collectors.toCollection(() -> new TreeSet<>(Comparator.comparing(AuthenticatorTransport::getValue))));
        Long count = d.getLong("signatureCount");
        CredentialRecord record = ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(Bytes.fromBase64(d.getString("credentialId")))
                .userEntityUserId(Bytes.fromBase64(d.getString("userHandle")))
                .publicKey(new ImmutablePublicKeyCose(bytes(d, "publicKeyCose").getBytes()))
                .signatureCount(count == null ? 0 : count)
                .uvInitialized(bool(d, "uvInitialized"))
                .transports(transports)
                .backupEligible(bool(d, "backupEligible"))
                .backupState(bool(d, "backupState"))
                .attestationObject(bytes(d, "attestationObject"))
                .attestationClientDataJSON(bytes(d, "attestationClientDataJson"))
                .label(d.getString("label"))
                .created(time(d, "createdAt"))
                .lastUsed(time(d, "lastUsedAt"))
                .build();
        return new Passkey(record, d.getString("memberId"), Status.valueOf(d.getString("status")), d.getString("enrolledNodeId"),
                d.getString("inviteId"));
    }
}
