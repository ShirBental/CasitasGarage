package home.casita.security.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;

import tools.jackson.databind.json.JsonMapper;

/**
 * The security store for local development: one JSON file, {@code <data-dir>/security.json}, readable by
 * Casita's user only, written whole and atomically after every change. One file and one lock make every
 * change, including an enrollment, all or nothing, with the same rules as {@link DynamoSecurityStore}
 * (both pass SecurityStoreContract). A failed write leaves memory as it was and is a
 * {@link StoreUnavailableException}.
 */
public class FileSecurityStore implements SecurityStore {

    /** A passkey as stored. Byte fields are base64url. */
    record StoredPasskey(String credentialId, String memberId, String userHandle, String publicKey, long signatureCount,
                         boolean uvInitialized, List<String> transports, boolean backupEligible, boolean backupState,
                         String attestationObject, String attestationClientDataJson, String label, Instant created, Instant lastUsed,
                         Status status, Instant revokedAt, String enrolledNodeId, String inviteId) {}

    record StoredMember(String id, Status status, String userHandle, Instant createdAt, Instant revokedAt) {}

    record Contents(List<StoredMember> members, List<StoredPasskey> passkeys, List<Device> devices, List<Invitation> invitations,
                    Map<String, String> consumeRequests) {}

    private final Path file;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private Map<String, StoredMember> members = new LinkedHashMap<>();
    private Map<String, StoredPasskey> passkeys = new LinkedHashMap<>();
    private Map<String, Device> devices = new LinkedHashMap<>();
    private Map<String, Invitation> invitations = new LinkedHashMap<>();
    /** The attempt that consumed each invitation, by token hash: server-side only, for idempotent retries. */
    private Map<String, String> consumeRequests = new LinkedHashMap<>();

    public FileSecurityStore(Path dataDir, Clock clock) {
        this.file = dataDir.resolve("security.json");
        this.clock = clock;
        if (Files.exists(file)) {
            Contents c = json.readValue(file.toFile(), Contents.class);
            c.members().forEach(m -> members.put(m.id(), m));
            c.passkeys().forEach(p -> passkeys.put(p.credentialId(), p));
            c.devices().forEach(d -> devices.put(d.nodeId(), d));
            c.invitations().forEach(i -> invitations.put(i.tokenHash(), i));
            if (c.consumeRequests() != null) consumeRequests.putAll(c.consumeRequests());
        }
    }

    /* ---------- members ---------- */

    @Override
    public synchronized void seedMembers(Collection<String> memberIds) {
        List<String> missing = memberIds.stream().filter(id -> !members.containsKey(id)).toList();
        if (missing.isEmpty()) return;
        change(() -> missing.forEach(id -> members.put(id, new StoredMember(id, Status.ACTIVE, null, clock.instant(), null))));
    }

    @Override
    public synchronized Optional<Member> member(String memberId) {
        return Optional.ofNullable(members.get(memberId)).map(FileSecurityStore::toMember);
    }

    @Override
    public synchronized Optional<Member> memberByUserHandle(Bytes userHandle) {
        String handle = userHandle.toBase64UrlString();
        return members.values().stream().filter(m -> handle.equals(m.userHandle())).findFirst().map(FileSecurityStore::toMember);
    }

    @Override
    public synchronized boolean setUserHandle(String memberId, Bytes userHandle) {
        StoredMember m = members.get(memberId);
        if (m == null || m.status() != Status.ACTIVE) return false;
        String handle = userHandle.toBase64UrlString();
        if (m.userHandle() != null) return m.userHandle().equals(handle);
        if (members.values().stream().anyMatch(other -> handle.equals(other.userHandle()))) return false;
        change(() -> members.put(memberId, new StoredMember(m.id(), m.status(), handle, m.createdAt(), m.revokedAt())));
        return true;
    }

    @Override
    public synchronized boolean revokeMember(String memberId) {
        StoredMember m = members.get(memberId);
        if (m == null) return false;
        if (m.status() == Status.REVOKED) return true;
        change(() -> members.put(memberId, new StoredMember(m.id(), Status.REVOKED, m.userHandle(), m.createdAt(), clock.instant())));
        return true;
    }

    @Override
    public synchronized Cleanup cleanUpMember(String memberId) {
        int d = 0, p = 0, i = 0;
        for (Device device : devicesOf(memberId)) if (device.status() == Status.ACTIVE && revokeDevice(device.nodeId())) d++;
        for (Passkey passkey : passkeysOf(memberId)) if (revokePasskey(passkey.record().getCredentialId())) p++;
        for (Invitation invitation : invitationsOf(memberId)) if (revokeInvitation(invitation.inviteId())) i++;
        return new Cleanup(d, p, i);
    }

    /* ---------- passkeys ---------- */

    @Override
    public synchronized Optional<Passkey> passkey(Bytes credentialId) {
        return Optional.ofNullable(passkeys.get(credentialId.toBase64UrlString())).map(FileSecurityStore::toPasskey);
    }

    @Override
    public synchronized List<Passkey> passkeysOf(String memberId) {
        return passkeys.values().stream().filter(p -> p.memberId().equals(memberId) && p.status() == Status.ACTIVE)
                .sorted(Comparator.comparing(StoredPasskey::created)).map(FileSecurityStore::toPasskey).toList();
    }

    @Override
    public synchronized boolean recordUse(CredentialRecord used, String memberId) {
        StoredPasskey p = passkeys.get(used.getCredentialId().toBase64UrlString());
        if (p == null || p.status() != Status.ACTIVE || !p.memberId().equals(memberId)) return false;
        change(() -> passkeys.put(p.credentialId(), new StoredPasskey(p.credentialId(), p.memberId(), p.userHandle(), p.publicKey(),
                used.getSignatureCount(), p.uvInitialized(), p.transports(), p.backupEligible(), used.isBackupState(), p.attestationObject(),
                p.attestationClientDataJson(), p.label(), p.created(), used.getLastUsed(), p.status(), p.revokedAt(), p.enrolledNodeId(),
                p.inviteId())));
        return true;
    }

    @Override
    public synchronized boolean revokePasskey(Bytes credentialId) {
        StoredPasskey p = passkeys.get(credentialId.toBase64UrlString());
        if (p == null || p.status() != Status.ACTIVE) return false;
        change(() -> passkeys.put(p.credentialId(), new StoredPasskey(p.credentialId(), p.memberId(), p.userHandle(), p.publicKey(),
                p.signatureCount(), p.uvInitialized(), p.transports(), p.backupEligible(), p.backupState(), p.attestationObject(),
                p.attestationClientDataJson(), p.label(), p.created(), p.lastUsed(), Status.REVOKED, clock.instant(), p.enrolledNodeId(),
                p.inviteId())));
        return true;
    }

    /* ---------- devices ---------- */

    @Override
    public synchronized Optional<Device> device(String nodeId) {
        return Optional.ofNullable(devices.get(nodeId));
    }

    @Override
    public synchronized List<Device> devicesOf(String memberId) {
        return devices.values().stream().filter(d -> d.memberId().equals(memberId)).toList();
    }

    @Override
    public synchronized boolean revokeDevice(String nodeId) {
        Device d = devices.get(nodeId);
        if (d == null || d.status() != Status.ACTIVE) return false;
        change(() -> devices.put(nodeId, withStatus(d, Status.REVOKED, clock.instant())));
        return true;
    }

    @Override
    public synchronized boolean releaseDevice(String nodeId) {
        Device d = devices.get(nodeId);
        if (d == null || d.status() != Status.REVOKED) return false;
        change(() -> devices.put(nodeId, withStatus(d, Status.RELEASED, d.revokedAt())));
        return true;
    }

    /* ---------- invitations ---------- */

    @Override
    public synchronized void createInvitation(Invitation pending) {
        if (pending.status() != InvitationStatus.PENDING) throw new IllegalArgumentException("a new invitation is PENDING");
        if (invitations.containsKey(pending.tokenHash())) throw new IllegalStateException("invitation exists");
        change(() -> invitations.put(pending.tokenHash(), pending));
    }

    @Override
    public synchronized Optional<Invitation> consume(String tokenHash, String nodeId, String nodeName, String consumeRequestId,
                                                     String enrollmentId) {
        Invitation i = invitations.get(tokenHash);
        Instant now = clock.instant();
        if (i == null || i.expired(now)) return Optional.empty();
        boolean fresh = i.status() == InvitationStatus.PENDING;
        boolean sameAttempt = i.status() == InvitationStatus.CONSUMED && consumeRequestId.equals(consumeRequests.get(tokenHash))
                && enrollmentId.equals(i.enrollmentId()) && nodeId.equals(i.nodeId());
        if (!fresh && !sameAttempt) return Optional.empty();
        if (sameAttempt) return Optional.of(i);
        Invitation consumed = new Invitation(i.tokenHash(), i.inviteId(), i.memberId(), i.label(), InvitationStatus.CONSUMED, i.createdAt(),
                i.expiresAt(), now, enrollmentId, nodeId, nodeName, null, null, null);
        change(() -> {
            invitations.put(tokenHash, consumed);
            consumeRequests.put(tokenHash, consumeRequestId);
        });
        return Optional.of(consumed);
    }

    @Override
    public synchronized Optional<Invitation> invitationById(String inviteId) {
        return invitations.values().stream().filter(i -> i.inviteId().equals(inviteId)).findFirst();
    }

    @Override
    public synchronized List<Invitation> invitationsOf(String memberId) {
        return invitations.values().stream().filter(i -> i.memberId().equals(memberId))
                .sorted(Comparator.comparing(Invitation::createdAt)).toList();
    }

    @Override
    public synchronized boolean revokeInvitation(String inviteId) {
        Optional<Invitation> found = invitationById(inviteId);
        if (found.isEmpty()) return false;
        Invitation i = found.get();
        boolean revocable = i.status() == InvitationStatus.PENDING || (i.status() == InvitationStatus.CONSUMED && i.enrolledAt() == null);
        if (!revocable) return false;
        change(() -> invitations.put(i.tokenHash(), new Invitation(i.tokenHash(), i.inviteId(), i.memberId(), i.label(),
                InvitationStatus.REVOKED, i.createdAt(), i.expiresAt(), i.consumedAt(), i.enrollmentId(), i.nodeId(), i.nodeName(), null, null,
                clock.instant())));
        return true;
    }

    /* ---------- views ---------- */

    @Override
    public synchronized AccessView accessView(String memberId, Bytes credentialId, String nodeId) {
        return new AccessView(member(memberId), passkey(credentialId), device(nodeId));
    }

    @Override
    public synchronized EnrollmentView enrollmentView(String tokenHash, String memberId) {
        return new EnrollmentView(Optional.ofNullable(invitations.get(tokenHash)), member(memberId));
    }

    /* ---------- the enrollment transaction ---------- */

    @Override
    public synchronized boolean enroll(EnrollmentClaim claim, CredentialRecord newPasskey, Bytes existingCredentialId) {
        if ((newPasskey == null) == (existingCredentialId == null)) throw new IllegalArgumentException("a new passkey or an existing one");
        String credentialId = newPasskey != null ? newPasskey.getCredentialId().toBase64UrlString() : existingCredentialId.toBase64UrlString();
        Instant now = clock.instant();

        Invitation i = invitations.get(claim.tokenHash());
        boolean invitationOk = i != null && i.status() == InvitationStatus.CONSUMED && Objects.equals(i.enrollmentId(), claim.enrollmentId())
                && Objects.equals(i.nodeId(), claim.nodeId()) && i.memberId().equals(claim.memberId()) && !i.expired(now)
                && i.enrolledAt() == null;
        if (!invitationOk) return completed(claim, credentialId); // a repeat of a completed enrollment is still complete

        StoredMember m = members.get(claim.memberId());
        if (m == null || m.status() != Status.ACTIVE) return false;
        StoredPasskey existing = passkeys.get(credentialId);
        if (newPasskey != null ? existing != null : existing == null || existing.status() != Status.ACTIVE || !existing.memberId().equals(claim.memberId())) {
            return false;
        }
        Device d = devices.get(claim.nodeId());
        boolean nodeFree = d == null || (d.status() == Status.REVOKED && d.memberId().equals(claim.memberId())) || d.status() == Status.RELEASED;
        if (!nodeFree) return false;

        change(() -> {
            invitations.put(i.tokenHash(), new Invitation(i.tokenHash(), i.inviteId(), i.memberId(), i.label(), i.status(), i.createdAt(),
                    i.expiresAt(), i.consumedAt(), i.enrollmentId(), i.nodeId(), i.nodeName(), now, credentialId, null));
            if (newPasskey != null) passkeys.put(credentialId, stored(newPasskey, claim));
            devices.put(claim.nodeId(), new Device(claim.nodeId(), claim.memberId(), claim.label(), claim.nodeName(), credentialId,
                    claim.inviteId(), Status.ACTIVE, now, null));
        });
        return true;
    }

    /** Durable state shows this enrollment complete: the invitation, the device and the passkey all agree. */
    private boolean completed(EnrollmentClaim claim, String credentialId) {
        Invitation i = invitations.get(claim.tokenHash());
        Device d = devices.get(claim.nodeId());
        StoredPasskey p = passkeys.get(credentialId);
        return i != null && i.enrolledAt() != null && Objects.equals(i.enrollmentId(), claim.enrollmentId())
                && credentialId.equals(i.enrolledCredentialId())
                && d != null && d.activeFor(claim.memberId()) && claim.inviteId().equals(d.inviteId())
                && p != null && p.status() == Status.ACTIVE && p.memberId().equals(claim.memberId());
    }

    /* ---------- mapping ---------- */

    private static Member toMember(StoredMember m) {
        return new Member(m.id(), m.status(), m.userHandle() == null ? null : Bytes.fromBase64(m.userHandle()));
    }

    private static Device withStatus(Device d, Status status, Instant revokedAt) {
        return new Device(d.nodeId(), d.memberId(), d.label(), d.nodeName(), d.credentialId(), d.inviteId(), status, d.authorizedAt(), revokedAt);
    }

    private StoredPasskey stored(CredentialRecord r, EnrollmentClaim claim) {
        return new StoredPasskey(r.getCredentialId().toBase64UrlString(), claim.memberId(), r.getUserEntityUserId().toBase64UrlString(),
                new Bytes(r.getPublicKey().getBytes()).toBase64UrlString(), r.getSignatureCount(), r.isUvInitialized(),
                r.getTransports() == null ? List.of() : r.getTransports().stream().map(AuthenticatorTransport::getValue).sorted().toList(),
                r.isBackupEligible(), r.isBackupState(), base64(r.getAttestationObject()), base64(r.getAttestationClientDataJSON()),
                r.getLabel(), r.getCreated() == null ? clock.instant() : r.getCreated(), r.getLastUsed(), Status.ACTIVE, null,
                claim.nodeId(), claim.inviteId());
    }

    private static Passkey toPasskey(StoredPasskey p) {
        Set<AuthenticatorTransport> transports = p.transports().stream().map(AuthenticatorTransport::valueOf)
                .collect(Collectors.toCollection(() -> new TreeSet<>(Comparator.comparing(AuthenticatorTransport::getValue))));
        CredentialRecord record = ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(Bytes.fromBase64(p.credentialId()))
                .userEntityUserId(Bytes.fromBase64(p.userHandle()))
                .publicKey(new ImmutablePublicKeyCose(Bytes.fromBase64(p.publicKey()).getBytes()))
                .signatureCount(p.signatureCount())
                .uvInitialized(p.uvInitialized())
                .transports(transports)
                .backupEligible(p.backupEligible())
                .backupState(p.backupState())
                .attestationObject(p.attestationObject() == null ? null : Bytes.fromBase64(p.attestationObject()))
                .attestationClientDataJSON(p.attestationClientDataJson() == null ? null : Bytes.fromBase64(p.attestationClientDataJson()))
                .label(p.label())
                .created(p.created())
                .lastUsed(p.lastUsed())
                .build();
        return new Passkey(record, p.memberId(), p.status(), p.enrolledNodeId(), p.inviteId());
    }

    private static String base64(Bytes b) {
        return b == null ? null : b.toBase64UrlString();
    }

    /* ---------- storage ---------- */

    /** Applies a change and writes it; if the write fails, memory goes back to how it was. */
    private void change(Runnable change) {
        Map<String, StoredMember> m = new LinkedHashMap<>(members);
        Map<String, StoredPasskey> p = new LinkedHashMap<>(passkeys);
        Map<String, Device> d = new LinkedHashMap<>(devices);
        Map<String, Invitation> i = new LinkedHashMap<>(invitations);
        Map<String, String> r = new LinkedHashMap<>(consumeRequests);
        try {
            change.run();
            write();
        } catch (RuntimeException e) {
            members = m;
            passkeys = p;
            devices = d;
            invitations = i;
            consumeRequests = r;
            throw e instanceof StoreUnavailableException ? e : new StoreUnavailableException("Couldn't save the security store", e);
        }
    }

    private void write() {
        try {
            Path dir = file.getParent();
            Files.createDirectories(dir);
            restrict(dir, "rwx------");
            Path tmp = Files.createTempFile(dir, "security", ".tmp");
            restrict(tmp, "rw-------");
            json.writeValue(tmp.toFile(), new Contents(new ArrayList<>(members.values()), new ArrayList<>(passkeys.values()),
                    new ArrayList<>(devices.values()), new ArrayList<>(invitations.values()), new LinkedHashMap<>(consumeRequests)));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void restrict(Path path, String permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException e) {
            // Not a POSIX file system.
        }
    }
}
