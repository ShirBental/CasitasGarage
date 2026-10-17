package home.casita.security.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionCheck;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.Get;
import software.amazon.awssdk.services.dynamodb.model.ItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactGetItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

/**
 * The security store in DynamoDB (docs/PERSISTENCE.md). Every decision-making read is strongly consistent on a
 * base table; indexes only find keys. Every change is a conditional write, revocation is a status, and
 * enrolling a device is one transaction. Any error reaching DynamoDB is a {@link StoreUnavailableException},
 * except where noted: an ambiguous enrollment is settled by reading what is durably stored.
 */
public class DynamoSecurityStore implements SecurityStore {

    private static final Logger log = LoggerFactory.getLogger(DynamoSecurityStore.class);
    /** How long invitation records are kept after they expire, for investigation. TTL removes them, eventually. */
    static final Duration INVITATION_RETENTION = Duration.ofDays(30);

    private final DynamoDbClient db;
    private final DynamoTables tables;
    private final Clock clock;

    public DynamoSecurityStore(DynamoDbClient db, DynamoTables tables, Clock clock) {
        this.db = db;
        this.tables = tables;
        this.clock = clock;
    }

    /* ---------- members ---------- */

    @Override
    public void seedMembers(Collection<String> memberIds) {
        for (String id : memberIds) {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("memberId", s(id));
            item.put("status", s(Status.ACTIVE.name()));
            item.put("createdAt", n(clock.instant()));
            try {
                call(() -> db.putItem(b -> b.tableName(tables.members()).item(item).conditionExpression("attribute_not_exists(memberId)")));
            } catch (ConditionalCheckFailedException e) {
                // Exists already, active or revoked: left as it is.
            }
        }
    }

    @Override
    public Optional<Member> member(String memberId) {
        return get(tables.members(), "memberId", memberId).map(DynamoSecurityStore::toMember);
    }

    @Override
    public Optional<Member> memberByUserHandle(Bytes userHandle) {
        String handle = userHandle.toBase64UrlString();
        List<Map<String, AttributeValue>> keys = call(() -> db.query(b -> b.tableName(tables.members()).indexName(DynamoTables.BY_USER_HANDLE)
                .keyConditionExpression("userHandle = :h").expressionAttributeValues(Map.of(":h", s(handle))))).items();
        for (Map<String, AttributeValue> key : keys) {
            Optional<Member> member = member(key.get("memberId").s());
            if (member.isPresent() && member.get().userHandle() != null && handle.equals(member.get().userHandle().toBase64UrlString())) {
                return member;
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean setUserHandle(String memberId, Bytes userHandle) {
        try {
            call(() -> db.updateItem(b -> b.tableName(tables.members()).key(key("memberId", memberId))
                    .updateExpression("SET userHandle = :h, updatedAt = :now")
                    .conditionExpression("#st = :active AND (attribute_not_exists(userHandle) OR userHandle = :h)")
                    .expressionAttributeNames(Map.of("#st", "status"))
                    .expressionAttributeValues(Map.of(":h", s(userHandle.toBase64UrlString()), ":now", n(clock.instant()),
                            ":active", s(Status.ACTIVE.name())))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    @Override
    public boolean revokeMember(String memberId) {
        try {
            call(() -> db.updateItem(b -> b.tableName(tables.members()).key(key("memberId", memberId))
                    .updateExpression("SET #st = :revoked, revokedAt = if_not_exists(revokedAt, :now), updatedAt = :now")
                    .conditionExpression("attribute_exists(memberId)")
                    .expressionAttributeNames(Map.of("#st", "status"))
                    .expressionAttributeValues(Map.of(":revoked", s(Status.REVOKED.name()), ":now", n(clock.instant())))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    @Override
    public Cleanup cleanUpMember(String memberId) {
        int devices = 0, passkeys = 0, invitations = 0;
        for (String nodeId : indexKeys(tables.devices(), "nodeId", memberId)) if (revokeDevice(nodeId)) devices++;
        for (String credentialId : indexKeys(tables.passkeys(), "credentialId", memberId)) {
            if (revokePasskey(Bytes.fromBase64(credentialId))) passkeys++;
        }
        for (Invitation invitation : invitationsOf(memberId)) if (revokeInvitation(invitation.inviteId())) invitations++;
        return new Cleanup(devices, passkeys, invitations);
    }

    /* ---------- passkeys ---------- */

    @Override
    public Optional<Passkey> passkey(Bytes credentialId) {
        return get(tables.passkeys(), "credentialId", credentialId.toBase64UrlString()).map(DynamoSecurityStore::toPasskey);
    }

    @Override
    public List<Passkey> passkeysOf(String memberId) {
        List<Passkey> result = new ArrayList<>();
        for (String id : indexKeys(tables.passkeys(), "credentialId", memberId)) {
            passkey(Bytes.fromBase64(id)).filter(p -> p.activeFor(memberId)).ifPresent(result::add);
        }
        result.sort(Comparator.comparing(p -> p.record().getCreated()));
        return result;
    }

    @Override
    public boolean recordUse(CredentialRecord used, String memberId) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":count", AttributeValue.fromN(Long.toString(used.getSignatureCount())));
        values.put(":used", n(used.getLastUsed() == null ? clock.instant() : used.getLastUsed()));
        values.put(":backup", AttributeValue.fromBool(used.isBackupState()));
        values.put(":active", s(Status.ACTIVE.name()));
        values.put(":m", s(memberId));
        try {
            call(() -> db.updateItem(b -> b.tableName(tables.passkeys()).key(key("credentialId", used.getCredentialId().toBase64UrlString()))
                    .updateExpression("SET signatureCount = :count, lastUsedAt = :used, backupState = :backup")
                    .conditionExpression("#st = :active AND memberId = :m")
                    .expressionAttributeNames(Map.of("#st", "status")).expressionAttributeValues(values)));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    @Override
    public boolean revokePasskey(Bytes credentialId) {
        return setStatus(tables.passkeys(), "credentialId", credentialId.toBase64UrlString(), Status.ACTIVE, Status.REVOKED, "revokedAt");
    }

    /* ---------- devices ---------- */

    @Override
    public Optional<Device> device(String nodeId) {
        return get(tables.devices(), "nodeId", nodeId).map(DynamoSecurityStore::toDevice);
    }

    @Override
    public List<Device> devicesOf(String memberId) {
        List<Device> result = new ArrayList<>();
        for (String nodeId : indexKeys(tables.devices(), "nodeId", memberId)) {
            device(nodeId).filter(d -> d.memberId().equals(memberId)).ifPresent(result::add);
        }
        return result;
    }

    @Override
    public boolean revokeDevice(String nodeId) {
        return setStatus(tables.devices(), "nodeId", nodeId, Status.ACTIVE, Status.REVOKED, "revokedAt");
    }

    @Override
    public boolean releaseDevice(String nodeId) {
        return setStatus(tables.devices(), "nodeId", nodeId, Status.REVOKED, Status.RELEASED, "releasedAt");
    }

    /* ---------- invitations ---------- */

    @Override
    public void createInvitation(Invitation pending) {
        if (pending.status() != InvitationStatus.PENDING) throw new IllegalArgumentException("a new invitation is PENDING");
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("tokenHash", s(pending.tokenHash()));
        item.put("inviteId", s(pending.inviteId()));
        item.put("memberId", s(pending.memberId()));
        item.put("label", s(pending.label()));
        item.put("status", s(InvitationStatus.PENDING.name()));
        item.put("createdAt", n(pending.createdAt()));
        item.put("expiresAt", n(pending.expiresAt()));
        // Cleanup only, set once: nothing reads it, and nothing changes it later.
        item.put("ttl", AttributeValue.fromN(Long.toString(pending.expiresAt().plus(INVITATION_RETENTION).getEpochSecond())));
        try {
            call(() -> db.putItem(b -> b.tableName(tables.invitations()).item(item).conditionExpression("attribute_not_exists(tokenHash)")));
        } catch (ConditionalCheckFailedException e) {
            throw new IllegalStateException("invitation exists", e);
        }
    }

    @Override
    public Optional<Invitation> consume(String tokenHash, String nodeId, String nodeName, String consumeRequestId, String enrollmentId) {
        try {
            return attemptConsume(tokenHash, nodeId, nodeName, consumeRequestId, enrollmentId);
        } catch (StoreUnavailableException first) {
            // Ambiguous: the write may have happened. Retrying this same attempt is safe: only its own request id,
            // enrollment id and node pass the condition, and nothing it writes can extend or revive the invitation.
            log.warn("Consuming an invitation was ambiguous ({}); retrying the same attempt once", first.getCause().getClass().getSimpleName());
            return attemptConsume(tokenHash, nodeId, nodeName, consumeRequestId, enrollmentId);
        }
    }

    private Optional<Invitation> attemptConsume(String tokenHash, String nodeId, String nodeName, String consumeRequestId, String enrollmentId) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":consumed", s(InvitationStatus.CONSUMED.name()));
        values.put(":pending", s(InvitationStatus.PENDING.name()));
        values.put(":now", n(clock.instant()));
        values.put(":rid", s(consumeRequestId));
        values.put(":eid", s(enrollmentId));
        values.put(":node", s(nodeId));
        values.put(":nodeName", s(nodeName == null ? "" : nodeName));
        try {
            Map<String, AttributeValue> item = call(() -> db.updateItem(b -> b.tableName(tables.invitations()).key(key("tokenHash", tokenHash))
                    .updateExpression("SET #st = :consumed, consumedAt = if_not_exists(consumedAt, :now), consumeRequestId = :rid, "
                            + "enrollmentId = :eid, nodeId = :node, nodeName = :nodeName")
                    .conditionExpression("attribute_exists(tokenHash) AND expiresAt > :now AND (#st = :pending OR "
                            + "(#st = :consumed AND consumeRequestId = :rid AND enrollmentId = :eid AND nodeId = :node))")
                    .expressionAttributeNames(Map.of("#st", "status")).expressionAttributeValues(values)
                    .returnValues(ReturnValue.ALL_NEW))).attributes();
            return Optional.of(toInvitation(item));
        } catch (ConditionalCheckFailedException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Invitation> invitationById(String inviteId) {
        List<Map<String, AttributeValue>> keys = call(() -> db.query(b -> b.tableName(tables.invitations()).indexName(DynamoTables.BY_INVITE_ID)
                .keyConditionExpression("inviteId = :id").expressionAttributeValues(Map.of(":id", s(inviteId))))).items();
        for (Map<String, AttributeValue> key : keys) {
            Optional<Invitation> invitation = get(tables.invitations(), "tokenHash", key.get("tokenHash").s()).map(DynamoSecurityStore::toInvitation);
            if (invitation.isPresent() && invitation.get().inviteId().equals(inviteId)) return invitation;
        }
        return Optional.empty();
    }

    @Override
    public List<Invitation> invitationsOf(String memberId) {
        List<Invitation> result = new ArrayList<>();
        for (String hash : indexKeys(tables.invitations(), "tokenHash", memberId)) {
            get(tables.invitations(), "tokenHash", hash).map(DynamoSecurityStore::toInvitation).filter(i -> i.memberId().equals(memberId))
                    .ifPresent(result::add);
        }
        result.sort(Comparator.comparing(Invitation::createdAt));
        return result;
    }

    @Override
    public boolean revokeInvitation(String inviteId) {
        Optional<Invitation> invitation = invitationById(inviteId);
        if (invitation.isEmpty()) return false;
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":id", s(inviteId));
        values.put(":revoked", s(InvitationStatus.REVOKED.name()));
        values.put(":pending", s(InvitationStatus.PENDING.name()));
        values.put(":consumed", s(InvitationStatus.CONSUMED.name()));
        values.put(":now", n(clock.instant()));
        try {
            call(() -> db.updateItem(b -> b.tableName(tables.invitations()).key(key("tokenHash", invitation.get().tokenHash()))
                    .updateExpression("SET #st = :revoked, revokedAt = :now")
                    .conditionExpression("inviteId = :id AND (#st = :pending OR (#st = :consumed AND attribute_not_exists(enrolledAt)))")
                    .expressionAttributeNames(Map.of("#st", "status")).expressionAttributeValues(values)));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    /* ---------- consistent views ---------- */

    @Override
    public AccessView accessView(String memberId, Bytes credentialId, String nodeId) {
        List<ItemResponse> items = transactGet(
                getOf(tables.members(), "memberId", memberId),
                getOf(tables.passkeys(), "credentialId", credentialId.toBase64UrlString()),
                getOf(tables.devices(), "nodeId", nodeId));
        return new AccessView(item(items.get(0)).map(DynamoSecurityStore::toMember), item(items.get(1)).map(DynamoSecurityStore::toPasskey),
                item(items.get(2)).map(DynamoSecurityStore::toDevice));
    }

    @Override
    public EnrollmentView enrollmentView(String tokenHash, String memberId) {
        List<ItemResponse> items = transactGet(getOf(tables.invitations(), "tokenHash", tokenHash), getOf(tables.members(), "memberId", memberId));
        return new EnrollmentView(item(items.get(0)).map(DynamoSecurityStore::toInvitation), item(items.get(1)).map(DynamoSecurityStore::toMember));
    }

    /* ---------- the enrollment transaction ---------- */

    @Override
    public boolean enroll(EnrollmentClaim claim, CredentialRecord newPasskey, Bytes existingCredentialId) {
        if ((newPasskey == null) == (existingCredentialId == null)) throw new IllegalArgumentException("a new passkey or an existing one");
        String credentialId = newPasskey != null ? newPasskey.getCredentialId().toBase64UrlString() : existingCredentialId.toBase64UrlString();
        Instant now = clock.instant();

        Map<String, AttributeValue> invitationValues = new HashMap<>();
        invitationValues.put(":now", n(now));
        invitationValues.put(":credential", s(credentialId));
        invitationValues.put(":consumed", s(InvitationStatus.CONSUMED.name()));
        invitationValues.put(":eid", s(claim.enrollmentId()));
        invitationValues.put(":node", s(claim.nodeId()));
        invitationValues.put(":m", s(claim.memberId()));
        TransactWriteItem invitation = TransactWriteItem.builder().update(Update.builder().tableName(tables.invitations())
                .key(key("tokenHash", claim.tokenHash()))
                .updateExpression("SET enrolledAt = :now, enrolledCredentialId = :credential")
                .conditionExpression("#st = :consumed AND enrollmentId = :eid AND nodeId = :node AND memberId = :m "
                        + "AND expiresAt > :now AND attribute_not_exists(enrolledAt)")
                .expressionAttributeNames(Map.of("#st", "status")).expressionAttributeValues(invitationValues).build()).build();

        TransactWriteItem member = TransactWriteItem.builder().conditionCheck(ConditionCheck.builder().tableName(tables.members())
                .key(key("memberId", claim.memberId())).conditionExpression("#st = :active")
                .expressionAttributeNames(Map.of("#st", "status")).expressionAttributeValues(Map.of(":active", s(Status.ACTIVE.name()))).build())
                .build();

        TransactWriteItem passkey = newPasskey != null
                ? TransactWriteItem.builder().put(Put.builder().tableName(tables.passkeys()).item(passkeyItem(newPasskey, claim, now))
                        .conditionExpression("attribute_not_exists(credentialId)").build()).build()
                : TransactWriteItem.builder().conditionCheck(ConditionCheck.builder().tableName(tables.passkeys())
                        .key(key("credentialId", credentialId)).conditionExpression("#st = :active AND memberId = :m")
                        .expressionAttributeNames(Map.of("#st", "status"))
                        .expressionAttributeValues(Map.of(":active", s(Status.ACTIVE.name()), ":m", s(claim.memberId()))).build()).build();

        Map<String, AttributeValue> device = new HashMap<>();
        device.put("nodeId", s(claim.nodeId()));
        device.put("memberId", s(claim.memberId()));
        device.put("label", s(claim.label()));
        device.put("nodeName", s(claim.nodeName() == null ? "" : claim.nodeName()));
        device.put("credentialId", s(credentialId));
        device.put("inviteId", s(claim.inviteId()));
        device.put("status", s(Status.ACTIVE.name()));
        device.put("authorizedAt", n(now));
        TransactWriteItem node = TransactWriteItem.builder().put(Put.builder().tableName(tables.devices()).item(device)
                .conditionExpression("attribute_not_exists(nodeId) OR (#st = :revoked AND memberId = :m) OR #st = :released")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(Map.of(":revoked", s(Status.REVOKED.name()), ":released", s(Status.RELEASED.name()),
                        ":m", s(claim.memberId()))).build()).build();

        try {
            db.transactWriteItems(b -> b.clientRequestToken(requestToken(claim, credentialId)).transactItems(invitation, member, passkey, node));
            return true;
        } catch (TransactionCanceledException e) {
            // Refused, or a retry of a transaction that had already succeeded: what is stored says which.
            log.info("Enrollment transaction for invite {} cancelled ({}); checking what is stored", claim.inviteId(),
                    e.hasCancellationReasons() ? e.cancellationReasons().stream().map(r -> String.valueOf(r.code())).toList() : "no reasons");
            return completed(claim, credentialId);
        } catch (SdkException e) {
            // The answer was lost (or never came): the transaction happened entirely or not at all. Read, don't resend.
            log.warn("Enrollment transaction for invite {} has an unknown outcome ({}); checking what is stored", claim.inviteId(),
                    e.getClass().getSimpleName());
            return completed(claim, credentialId);
        }
    }

    /**
     * Whether durable state shows this enrollment complete: invitation enrolled by this attempt with this passkey,
     * the node authorized for the member by this invite, the passkey active and theirs. If even this read fails,
     * false: fail closed (the state is whole either way; a later sign-in will find it).
     */
    private boolean completed(EnrollmentClaim claim, String credentialId) {
        try {
            List<ItemResponse> items = transactGet(getOf(tables.invitations(), "tokenHash", claim.tokenHash()),
                    getOf(tables.devices(), "nodeId", claim.nodeId()), getOf(tables.passkeys(), "credentialId", credentialId));
            Optional<Invitation> i = item(items.get(0)).map(DynamoSecurityStore::toInvitation);
            Optional<Device> d = item(items.get(1)).map(DynamoSecurityStore::toDevice);
            Optional<Passkey> p = item(items.get(2)).map(DynamoSecurityStore::toPasskey);
            return i.isPresent() && i.get().enrolledAt() != null && Objects.equals(i.get().enrollmentId(), claim.enrollmentId())
                    && credentialId.equals(i.get().enrolledCredentialId())
                    && d.isPresent() && d.get().activeFor(claim.memberId()) && claim.inviteId().equals(d.get().inviteId())
                    && p.isPresent() && p.get().activeFor(claim.memberId());
        } catch (StoreUnavailableException e) {
            log.warn("Couldn't read back the enrollment for invite {}; refusing", claim.inviteId());
            return false;
        }
    }

    /** Idempotency token: one per enrollment attempt and passkey, so the two enrollment paths never collide. */
    static String requestToken(EnrollmentClaim claim, String credentialId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((claim.enrollmentId() + ":" + credentialId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 36);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /* ---------- reads and writes ---------- */

    private Optional<Map<String, AttributeValue>> get(String table, String keyName, String keyValue) {
        Map<String, AttributeValue> item = call(() -> db.getItem(b -> b.tableName(table).key(key(keyName, keyValue)).consistentRead(true))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(item);
    }

    private List<ItemResponse> transactGet(TransactGetItem... gets) {
        return call(() -> db.transactGetItems(b -> b.transactItems(gets))).responses();
    }

    private static TransactGetItem getOf(String table, String keyName, String keyValue) {
        return TransactGetItem.builder().get(Get.builder().tableName(table).key(key(keyName, keyValue)).build()).build();
    }

    private static Optional<Map<String, AttributeValue>> item(ItemResponse response) {
        return response == null || !response.hasItem() || response.item().isEmpty() ? Optional.empty() : Optional.of(response.item());
    }

    /** Base-table keys of a member's items, through the byMember index (finding only; callers read the base table). */
    private List<String> indexKeys(String table, String keyName, String memberId) {
        List<String> keys = new ArrayList<>();
        Map<String, AttributeValue> start = null;
        do {
            Map<String, AttributeValue> from = start;
            var page = call(() -> db.query(b -> b.tableName(table).indexName(DynamoTables.BY_MEMBER).keyConditionExpression("memberId = :m")
                    .expressionAttributeValues(Map.of(":m", s(memberId))).exclusiveStartKey(from)));
            page.items().forEach(i -> keys.add(i.get(keyName).s()));
            start = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty() ? page.lastEvaluatedKey() : null;
        } while (start != null);
        return keys;
    }

    private boolean setStatus(String table, String keyName, String keyValue, Status from, Status to, String timeAttribute) {
        try {
            call(() -> db.updateItem(b -> b.tableName(table).key(key(keyName, keyValue))
                    .updateExpression("SET #st = :to, " + timeAttribute + " = :now")
                    .conditionExpression("#st = :from")
                    .expressionAttributeNames(Map.of("#st", "status"))
                    .expressionAttributeValues(Map.of(":to", s(to.name()), ":from", s(from.name()), ":now", n(clock.instant())))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    /** Runs a DynamoDB call; a refused condition passes through, anything else means the store is unavailable. */
    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            return request.get();
        } catch (ConditionalCheckFailedException e) {
            throw e;
        } catch (SdkException e) {
            throw new StoreUnavailableException("DynamoDB unavailable", e);
        }
    }

    /* ---------- mapping ---------- */

    private static Map<String, AttributeValue> key(String name, String value) {
        return Map.of(name, s(value));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    private static AttributeValue n(Instant instant) {
        return AttributeValue.fromN(Long.toString(instant.toEpochMilli()));
    }

    private static String str(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v == null ? null : v.s();
    }

    private static Instant time(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v == null || v.n() == null ? null : Instant.ofEpochMilli(Long.parseLong(v.n()));
    }

    private static boolean bool(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v != null && Boolean.TRUE.equals(v.bool());
    }

    private static Bytes bytes(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v == null || v.b() == null ? null : new Bytes(v.b().asByteArray());
    }

    private static Member toMember(Map<String, AttributeValue> item) {
        String handle = str(item, "userHandle");
        return new Member(str(item, "memberId"), Status.valueOf(str(item, "status")), handle == null ? null : Bytes.fromBase64(handle));
    }

    private static Device toDevice(Map<String, AttributeValue> item) {
        return new Device(str(item, "nodeId"), str(item, "memberId"), str(item, "label"), str(item, "nodeName"), str(item, "credentialId"),
                str(item, "inviteId"), Status.valueOf(str(item, "status")), time(item, "authorizedAt"), time(item, "revokedAt"));
    }

    private static Invitation toInvitation(Map<String, AttributeValue> item) {
        return new Invitation(str(item, "tokenHash"), str(item, "inviteId"), str(item, "memberId"), str(item, "label"),
                InvitationStatus.valueOf(str(item, "status")), time(item, "createdAt"), time(item, "expiresAt"), time(item, "consumedAt"),
                str(item, "enrollmentId"), str(item, "nodeId"), str(item, "nodeName"), time(item, "enrolledAt"), str(item, "enrolledCredentialId"),
                time(item, "revokedAt"));
    }

    private static Map<String, AttributeValue> passkeyItem(CredentialRecord r, EnrollmentClaim claim, Instant now) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("credentialId", s(r.getCredentialId().toBase64UrlString()));
        item.put("memberId", s(claim.memberId()));
        item.put("userHandle", s(r.getUserEntityUserId().toBase64UrlString()));
        item.put("publicKeyCose", AttributeValue.fromB(SdkBytes.fromByteArray(r.getPublicKey().getBytes())));
        item.put("signatureCount", AttributeValue.fromN(Long.toString(r.getSignatureCount())));
        item.put("uvInitialized", AttributeValue.fromBool(r.isUvInitialized()));
        item.put("backupEligible", AttributeValue.fromBool(r.isBackupEligible()));
        item.put("backupState", AttributeValue.fromBool(r.isBackupState()));
        if (r.getTransports() != null && !r.getTransports().isEmpty()) {
            item.put("transports", AttributeValue.fromSs(r.getTransports().stream().map(AuthenticatorTransport::getValue).sorted().toList()));
        }
        if (r.getAttestationObject() != null) item.put("attestationObject", AttributeValue.fromB(SdkBytes.fromByteArray(r.getAttestationObject().getBytes())));
        if (r.getAttestationClientDataJSON() != null) {
            item.put("attestationClientDataJson", AttributeValue.fromB(SdkBytes.fromByteArray(r.getAttestationClientDataJSON().getBytes())));
        }
        if (r.getLabel() != null) item.put("label", s(r.getLabel()));
        item.put("createdAt", n(r.getCreated() == null ? now : r.getCreated()));
        if (r.getLastUsed() != null) item.put("lastUsedAt", n(r.getLastUsed()));
        item.put("status", s(Status.ACTIVE.name()));
        item.put("enrolledNodeId", s(claim.nodeId()));
        item.put("inviteId", s(claim.inviteId()));
        return item;
    }

    private static Passkey toPasskey(Map<String, AttributeValue> item) {
        AttributeValue t = item.get("transports");
        Set<AuthenticatorTransport> transports = (t == null || !t.hasSs() ? List.<String>of() : t.ss()).stream().map(AuthenticatorTransport::valueOf)
                .collect(Collectors.toCollection(() -> new TreeSet<>(Comparator.comparing(AuthenticatorTransport::getValue))));
        CredentialRecord record = ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(Bytes.fromBase64(str(item, "credentialId")))
                .userEntityUserId(Bytes.fromBase64(str(item, "userHandle")))
                .publicKey(new ImmutablePublicKeyCose(bytes(item, "publicKeyCose").getBytes()))
                .signatureCount(Long.parseLong(item.get("signatureCount").n()))
                .uvInitialized(bool(item, "uvInitialized"))
                .transports(transports)
                .backupEligible(bool(item, "backupEligible"))
                .backupState(bool(item, "backupState"))
                .attestationObject(bytes(item, "attestationObject"))
                .attestationClientDataJSON(bytes(item, "attestationClientDataJson"))
                .label(str(item, "label"))
                .created(time(item, "createdAt"))
                .lastUsed(time(item, "lastUsedAt"))
                .build();
        return new Passkey(record, str(item, "memberId"), Status.valueOf(str(item, "status")), str(item, "enrolledNodeId"), str(item, "inviteId"));
    }
}
