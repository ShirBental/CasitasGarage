package home.casita.security.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;

import home.casita.security.access.TestTailnet;
import home.casita.security.store.FirestoreSecurityStore.SecurityEvent;
import home.casita.security.store.SecurityStore.EnrollmentClaim;
import home.casita.security.store.SecurityStore.Invitation;
import home.casita.security.store.SecurityStore.InvitationStatus;
import home.casita.security.store.SecurityStore.Status;

/**
 * The contract, on the Firestore emulator, plus what only a remote store has: decisions made again (a write that lost
 * a race, a commit whose answer was lost), failures before sending, a failed read-back, outages, a start-up check,
 * security events written with each change, and document IDs that hold nothing secret.
 */
class FirestoreSecurityStoreTest extends SecurityStoreContract {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private final FirestoreCollections collections = FirestoreCollections.standard();
    private final List<Firestore> clients = new ArrayList<>();
    private String project;
    private FaultyFirestore faults;
    private Firestore db;

    @BeforeAll
    static void emulator() {
        assumeTrue(FirestoreEmulator.available(), "No Firestore emulator: set -Dfirestore.emulator.jar or -Dfirestore.emulator.host");
    }

    @Override
    protected SecurityStore newStore() {
        project = FirestoreEmulator.newProject();
        faults = new FaultyFirestore();
        db = open(faults, TIMEOUT);
        FirestoreEmulator.provision(db);
        return new FirestoreSecurityStore(db, collections, clock, TIMEOUT);
    }

    @Override
    protected SecurityStore restarted() {
        return new FirestoreSecurityStore(open(new FaultyFirestore(), TIMEOUT), collections, clock, TIMEOUT);
    }

    private Firestore open(FaultyFirestore f, Duration timeout) {
        Firestore client = FirestoreEmulator.client(project, f, timeout);
        clients.add(client);
        return client;
    }

    /** A store on the same data whose calls give up after a second: for outages, so the tests don't wait five. */
    private FirestoreSecurityStore impatient(FaultyFirestore f) {
        return new FirestoreSecurityStore(open(f, Duration.ofSeconds(1)), collections, clock, Duration.ofSeconds(1));
    }

    @AfterEach
    void close() throws Exception {
        for (Firestore c : clients) c.close();
        clients.clear();
    }

    private FirestoreSecurityStore firestore() {
        return (FirestoreSecurityStore) store;
    }

    private List<SecurityEvent> events(String type) {
        return firestore().securityEvents(500).stream().filter(e -> e.type().equals(type)).toList();
    }

    private static String random() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /* ---------- start-up ---------- */

    @Test
    void casitaStartsOnlyOnAProvisionedReachableDatabase() {
        collections.verify(db);

        Firestore unprovisioned = FirestoreEmulator.client(FirestoreEmulator.newProject(), new FaultyFirestore(), TIMEOUT);
        clients.add(unprovisioned);
        assertThatThrownBy(() -> collections.verify(unprovisioned)).isInstanceOf(IllegalStateException.class).hasMessageContaining("meta/schema");

        FaultyFirestore down = new FaultyFirestore();
        down.down(true);
        Firestore unreachable = open(down, Duration.ofSeconds(1));
        assertThatThrownBy(() -> collections.verify(unreachable)).isInstanceOf(IllegalStateException.class).hasMessageContaining("reachable");
    }

    @Test
    void theLibrarysEmulatorVariableAloneCantSwapTheDatabase() {
        var settings = new StorageProperties.Firestore("casita-prod", TIMEOUT, null);
        assertThatThrownBy(() -> StorageConfig.firestoreOptions(settings, "127.0.0.1:8085")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FIRESTORE_EMULATOR_HOST");
        var remote = new StorageProperties.Firestore("casita-prod", TIMEOUT, "emulator.example.com:8085");
        assertThatThrownBy(() -> StorageConfig.firestoreOptions(remote, null)).isInstanceOf(IllegalStateException.class);
        assertThat(StorageConfig.firestoreOptions(settings, null).getDatabaseId()).isEqualTo("(default)");
    }

    /* ---------- documents: IDs, fields, TTL ---------- */

    @Test
    void documentIdsHoldNothingSecretAndInvitationsAreKeptThirtyDaysByTtlWhichNothingReads() throws Exception {
        Invitation i = invite("shir");
        DocumentSnapshot doc = db.collection("invitations").document("h_" + i.tokenHash()).get().get(5, TimeUnit.SECONDS);

        assertThat(doc.exists()).isTrue();
        assertThat(doc.getTimestamp("ttl").toDate().toInstant()).isEqualTo(i.expiresAt().plus(FirestoreSecurityStore.INVITATION_RETENTION));
        assertThat(doc.getData()).doesNotContainKey("token");
        assertThat(doc.getString("status")).isEqualTo("PENDING");
        // Security events have random IDs: nothing about the member, node, invitation or token.
        for (QueryDocumentSnapshot e : db.collection("securityEvents").get().get(5, TimeUnit.SECONDS).getDocuments()) {
            assertThat(e.getId()).matches("e_[0-9a-f]{32}").doesNotContain(i.tokenHash()).doesNotContain(i.inviteId());
            assertThat(e.getData().values()).doesNotContain(i.tokenHash());
        }
    }

    @Test
    void identifiersThatCantBeDocumentIdsReadAsAbsentAndWriteNothing() {
        enrolled("shir", "c2hpcnMta2V5", TestTailnet.SHIR_PHONE);
        for (String bad : List.of("n/../c_c2hpcnMta2V5", "__name__", "a/b", "")) {
            assertThat(store.device(bad)).as(bad).isEmpty();
            assertThat(store.member(bad)).as(bad).isEmpty();
            assertThat(store.revokeDevice(bad)).as(bad).isFalse();
            assertThat(store.invitation(bad)).as(bad).isEmpty();
            assertThat(store.consume(bad, TestTailnet.SHIR_IPAD.nodeId(), "ipad", "r", "e")).as(bad).isEmpty();
        }
        // A forged path can't read another collection's document as an access decision.
        assertThat(store.accessView("shir", Bytes.fromBase64("c2hpcnMta2V5"), "n/../m_shir").device()).isEmpty();
        assertThat(store.device(TestTailnet.SHIR_PHONE.nodeId())).get().satisfies(d -> assertThat(d.activeFor("shir")).isTrue());
    }

    @Test
    void anotherMembersDocumentsAreNeverListedAsTheirs() {
        enrolled("shir", "c2hpcnMta2V5", TestTailnet.SHIR_PHONE);
        enrolled("hubby", "aHViYnlzLWtleQ", TestTailnet.HUBBY_PHONE);

        assertThat(store.devicesOf("shir")).extracting(SecurityStore.Device::nodeId).containsExactly(TestTailnet.SHIR_PHONE.nodeId());
        assertThat(store.passkeysOf("hubby")).extracting(SecurityStore.Passkey::id).containsExactly("aHViYnlzLWtleQ");
        assertThat(store.invitationsOf("hubby")).allSatisfy(i -> assertThat(i.memberId()).isEqualTo("hubby"));
        assertThat(store.accessView("hubby", Bytes.fromBase64("c2hpcnMta2V5"), TestTailnet.SHIR_PHONE.nodeId())).satisfies(v -> {
            assertThat(v.passkey()).get().satisfies(p -> assertThat(p.activeFor("hubby")).isFalse());
            assertThat(v.device()).get().satisfies(d -> assertThat(d.activeFor("hubby")).isFalse());
        });
    }

    /* ---------- consume: concurrency, lost answers, contention ---------- */

    @Test
    void twentyConcurrentRedemptionsOfOneInvitationAtMostOneWinsAndOnlyItsRetryIsAnswered() throws Exception {
        // Twenty attempts read and write one document at once. Each write is conditioned on the document as read, so
        // exactly one lands; the others read again and are refused. Should a loser ever run out of attempts or time, it
        // fails closed (unavailable), refused just the same. What must never happen: two winners, or a loser's write.
        Invitation i = invite("shir");
        List<String[]> attempts = new ArrayList<>();
        for (int k = 0; k < 20; k++) attempts.add(new String[] {random(), random()});
        AtomicInteger next = new AtomicInteger();
        AtomicInteger unavailable = new AtomicInteger();

        List<Optional<Invitation>> results = concurrently(20, () -> {
            String[] a = attempts.get(next.getAndIncrement());
            try {
                return store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", a[0], a[1]);
            } catch (StoreUnavailableException e) {
                unavailable.incrementAndGet();
                return Optional.<Invitation>empty();
            }
        });

        System.out.println("20 concurrent redemptions: " + results.stream().filter(Optional::isPresent).count() + " won, "
                + unavailable.get() + " failed closed (unavailable), the rest refused");
        assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
        Invitation winner = results.stream().flatMap(Optional::stream).findFirst().orElseThrow();
        assertThat(store.invitation(i.tokenHash())).get().satisfies(stored -> {
            assertThat(stored.status()).isEqualTo(InvitationStatus.CONSUMED);
            assertThat(stored.enrollmentId()).isEqualTo(winner.enrollmentId()); // the winner's write, and only it
        });
        String[] winning = attempts.stream().filter(a -> a[1].equals(winner.enrollmentId())).findFirst().orElseThrow();
        assertThat(store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", winning[0], winning[1])).get()
                .extracting(Invitation::enrollmentId).isEqualTo(winner.enrollmentId());
        for (String[] a : attempts) {
            if (a != winning) assertThat(store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", a[0], a[1])).isEmpty();
        }
        assertThat(events("INVITATION_CONSUMED")).hasSize(1);
    }

    @Test
    void aConsumeWhoseCommitAnswerWasLostIsRerunAsTheSameAttempt() {
        Invitation i = invite("shir");
        faults.loseResponse("Commit", 1);

        Optional<Invitation> consumed = store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid-1", "eid-1");

        assertThat(consumed).get().satisfies(c -> {
            assertThat(c.enrollmentId()).isEqualTo("eid-1");
            assertThat(c.expiresAt()).isEqualTo(i.expiresAt());
        });
        assertThat(store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid-2", "eid-2")).isEmpty();
        assertThat(events("INVITATION_CONSUMED")).hasSize(1); // the rerun saw its own write and wrote nothing
    }

    @Test
    void aConsumeThatKeepsFailingIsRefusedAsUnavailableAndTheInvitationStaysGood() {
        Invitation i = invite("shir");
        faults.failBefore("Commit", 1_000);

        assertThatThrownBy(() -> store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid", "eid"))
                .isInstanceOf(StoreUnavailableException.class);

        faults.reset();
        assertThat(store.invitation(i.tokenHash())).get().extracting(Invitation::status).isEqualTo(InvitationStatus.PENDING);
        assertThat(store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid", "eid")).isPresent();
    }

    @Test
    void contentionIsRetriedABoundedNumberOfTimesThenRefused() {
        Invitation i = invite("shir");
        faults.abort("Commit", 2); // two lost locks: the third run commits
        assertThat(store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid-1", "eid-1")).isPresent();
        assertThat(events("INVITATION_CONSUMED")).hasSize(1);

        Invitation other = invite("hubby");
        faults.reset();
        faults.abort("Commit", 1_000); // contended for good
        assertThatThrownBy(() -> store.consume(other.tokenHash(), TestTailnet.HUBBY_PHONE.nodeId(), "phone", "rid-2", "eid-2"))
                .isInstanceOf(StoreUnavailableException.class);
        // Bounded: at most ATTEMPTS decisions, each one commit (allowing for the client library's own resend of a commit).
        assertThat(faults.calls("Commit")).isLessThanOrEqualTo(2 * FirestoreSecurityStore.ATTEMPTS);
        faults.reset();
        assertThat(store.invitation(other.tokenHash())).get().extracting(Invitation::status).isEqualTo(InvitationStatus.PENDING);
    }

    /* ---------- the enrollment: decided again, and unknown outcomes ---------- */

    @Test
    void anEnrollmentWhoseCommitAnswerWasLostReportsSuccessAndWritesOnce() {
        EnrollmentClaim claim = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        faults.loseResponse("Commit", 1);

        assertThat(store.enroll(claim, passkey("shir", "c2hpcnMta2V5"), null)).isTrue();

        assertThat(store.devicesOf("shir")).hasSize(1);
        assertThat(store.passkeysOf("shir")).hasSize(1);
        assertThat(events("DEVICE_ENROLLED")).hasSize(1);
        assertThat(events("ENROLLMENT_REFUSED")).isEmpty();
    }

    @Test
    void anEnrollmentThatNeverReachesFirestoreIsRefusedWithNothingWritten() {
        EnrollmentClaim claim = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        CredentialRecord record = passkey("shir", "c2hpcnMta2V5"); // gives Shir her user handle: a write, so before the fault
        faults.failBefore("Commit", 1_000);

        assertThat(store.enroll(claim, record, null)).isFalse();

        faults.reset();
        assertThat(store.device(TestTailnet.SHIR_PHONE.nodeId())).isEmpty();
        assertThat(store.passkey(Bytes.fromBase64("c2hpcnMta2V5"))).isEmpty();
        assertThat(store.invitationById(claim.inviteId())).get().satisfies(i -> assertThat(i.enrolledAt()).isNull());
        assertThat(store.enroll(claim, passkey("shir", "c2hpcnMta2V5"), null)).isTrue(); // the same enrollment, once Firestore is back
    }

    @Test
    void ifTheReadBackFailsTooItIsRefusedButTheStoredStateIsWhole() {
        EnrollmentClaim claim = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        FaultyFirestore f = new FaultyFirestore();
        FirestoreSecurityStore impatient = impatient(f);
        f.loseResponseThenDown("Commit");

        assertThat(impatient.enroll(claim, passkey("shir", "c2hpcnMta2V5"), null)).isFalse(); // fail closed

        // It did happen, entirely: the next sign-in on that phone finds device and passkey both.
        assertThat(store.accessView("shir", Bytes.fromBase64("c2hpcnMta2V5"), TestTailnet.SHIR_PHONE.nodeId())).satisfies(v -> {
            assertThat(v.device()).get().satisfies(d -> assertThat(d.activeFor("shir")).isTrue());
            assertThat(v.passkey()).get().satisfies(p -> assertThat(p.activeFor("shir")).isTrue());
        });
        assertThat(events("DEVICE_ENROLLED")).hasSize(1);
    }

    @Test
    void aRepeatedEnrollmentLongAfterIsAcknowledgedFromStateAndDuplicatesNothing() {
        // Firestore has no idempotency token and no ten-minute window: a resend at any time is judged by what is stored.
        Invitation invitation = invite("shir");
        EnrollmentClaim claim = consume(invitation, TestTailnet.SHIR_PHONE);
        CredentialRecord record = passkey("shir", "c2hpcnMta2V5");
        assertThat(store.enroll(claim, record, null)).isTrue();

        assertThat(store.enroll(claim, record, null)).isTrue();
        clock.advance(Duration.ofMinutes(11)); // past the invitation's expiry too
        assertThat(store.enroll(claim, record, null)).isTrue();

        assertThat(store.devicesOf("shir")).hasSize(1);
        assertThat(store.passkeysOf("shir")).hasSize(1);
        assertThat(events("DEVICE_ENROLLED")).hasSize(1);
        assertThat(store.invitationById(claim.inviteId())).get().satisfies(i -> {
            assertThat(i.expiresAt()).isEqualTo(invitation.expiresAt());
            assertThat(i.enrolledAt()).isNotNull();
        });
    }

    @Test
    void anEnrollmentThatNeverHappenedCantSucceedLate() {
        EnrollmentClaim claim = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        clock.advance(Duration.ofMinutes(11));

        assertThat(store.enroll(claim, passkey("shir", "c2hpcnMta2V5"), null)).isFalse();
        assertThat(store.device(TestTailnet.SHIR_PHONE.nodeId())).isEmpty();
        assertThat(events("ENROLLMENT_REFUSED")).singleElement().extracting(SecurityEvent::reason).isEqualTo("expired");
    }

    @Test
    void contendedEnrollmentsGiveUpWithNothingPartial() {
        EnrollmentClaim claim = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        CredentialRecord record = passkey("shir", "c2hpcnMta2V5");
        faults.abort("Commit", 1_000);

        assertThat(store.enroll(claim, record, null)).isFalse();

        faults.reset();
        assertThat(store.device(TestTailnet.SHIR_PHONE.nodeId())).isEmpty();
        assertThat(store.passkey(Bytes.fromBase64("c2hpcnMta2V5"))).isEmpty();
        assertThat(store.invitationById(claim.inviteId())).get().satisfies(i -> assertThat(i.enrolledAt()).isNull());
    }

    @Test
    void oneCredentialRegisteredByTwoEnrollmentsAtOnceIsStoredOnce() throws Exception {
        user("shir");
        EnrollmentClaim phone = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        EnrollmentClaim ipad = consume(invite("shir"), TestTailnet.SHIR_IPAD);
        AtomicInteger next = new AtomicInteger();

        List<Boolean> results = concurrently(2, () -> next.getAndIncrement() == 0
                ? store.enroll(phone, passkey("shir", "c2FtZQ"), null)
                : store.enroll(ipad, passkey("shir", "c2FtZQ"), null));

        assertThat(results).containsOnlyOnce(true);
        assertThat(store.passkeysOf("shir")).hasSize(1);
        assertThat(store.devicesOf("shir")).hasSize(1);
    }

    @Test
    void seedingWhileTheMemberIsRevokedNeverRevivesThem() throws Exception {
        for (int round = 0; round < 5; round++) {
            String member = "m" + round;
            store.seedMembers(List.of(member));
            AtomicInteger next = new AtomicInteger();
            concurrently(4, () -> {
                if (next.getAndIncrement() == 0) store.revokeMember(member);
                else store.seedMembers(List.of(member));
                return null;
            });
            assertThat(store.member(member)).get().extracting(SecurityStore.Member::status).as(member).isEqualTo(Status.REVOKED);
        }
    }

    /* ---------- revocation and outages ---------- */

    @Test
    void aRevocationIsSeenByTheVeryNextAccessCheckFromAnotherClient() {
        enrolled("shir", "c2hpcnMta2V5", TestTailnet.SHIR_PHONE);
        SecurityStore otherServer = restarted(); // another client: no cache shared with the one that revokes

        assertThat(store.revokeDevice(TestTailnet.SHIR_PHONE.nodeId())).isTrue();

        assertThat(otherServer.accessView("shir", Bytes.fromBase64("c2hpcnMta2V5"), TestTailnet.SHIR_PHONE.nodeId()).device()).get()
                .satisfies(d -> assertThat(d.activeFor("shir")).isFalse());
        assertThat(events("DEVICE_REVOKED")).singleElement().satisfies(e -> {
            assertThat(e.memberId()).isEqualTo("shir");
            assertThat(e.nodeId()).isEqualTo(TestTailnet.SHIR_PHONE.nodeId());
        });
    }

    @Test
    void duringAnOutageEveryReadAndWriteIsUnavailable() {
        enrolled("shir", "c2hpcnMta2V5", TestTailnet.SHIR_PHONE);
        Invitation i = invite("shir");
        FaultyFirestore f = new FaultyFirestore();
        FirestoreSecurityStore impatient = impatient(f);
        f.down(true);

        for (Runnable call : List.<Runnable>of(
                () -> impatient.accessView("shir", Bytes.fromBase64("c2hpcnMta2V5"), TestTailnet.SHIR_PHONE.nodeId()),
                () -> impatient.enrollmentView(i.tokenHash(), "shir"),
                () -> impatient.member("shir"),
                () -> impatient.passkeysOf("shir"),
                () -> impatient.consume(i.tokenHash(), TestTailnet.SHIR_IPAD.nodeId(), "ipad", "r", "e"),
                () -> impatient.revokeDevice(TestTailnet.SHIR_PHONE.nodeId()),
                () -> impatient.revokeInvitation(i.tokenHash(), i.inviteId()),
                () -> impatient.recordUse(passkey("shir", "c2hpcnMta2V5"), "shir"))) {
            assertThatThrownBy(call::run).isInstanceOf(StoreUnavailableException.class);
        }
        assertThat(store.device(TestTailnet.SHIR_PHONE.nodeId())).get().satisfies(d -> assertThat(d.activeFor("shir")).isTrue());
        assertThat(store.invitation(i.tokenHash())).get().extracting(Invitation::status).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void aSlowFirestoreIsAnUnavailableOne() {
        enrolled("shir", "c2hpcnMta2V5", TestTailnet.SHIR_PHONE);
        FaultyFirestore f = new FaultyFirestore();
        FirestoreSecurityStore impatient = impatient(f);
        f.failBefore("BatchGetDocuments", 1_000); // retried by the client until the deadline

        long start = System.nanoTime();
        assertThatThrownBy(() -> impatient.accessView("shir", Bytes.fromBase64("c2hpcnMta2V5"), TestTailnet.SHIR_PHONE.nodeId()))
                .isInstanceOf(StoreUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void aRevocationWhoseAnswerWasLostIsAnErrorOrTheTruthNeverAFalseSuccess() {
        Invitation i = invite("shir");
        FaultyFirestore f = new FaultyFirestore();
        FirestoreSecurityStore impatient = impatient(f);
        f.loseResponseThenDown("Commit");

        assertThatThrownBy(() -> impatient.revokeInvitation(i.tokenHash(), i.inviteId())).isInstanceOf(StoreUnavailableException.class);

        assertThat(store.revokeInvitation(i.tokenHash(), i.inviteId())).isEqualTo(SecurityStore.RevokeOutcome.ALREADY_REVOKED);
        assertThat(store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "r", "e")).isEmpty();
    }

    @Test
    void revokingByIdFindsAJustCreatedInvitation() {
        // Firestore's queries are strongly consistent: no index lag, so no "run it again".
        Invitation i = invite("shir");
        assertThat(store.revokeInvitation(i.inviteId())).isEqualTo(SecurityStore.RevokeOutcome.REVOKED);
    }

    /* ---------- security events ---------- */

    @Test
    void everyChangeToAccessIsRecordedWithItInOneCommit() {
        Duration tick = Duration.ofSeconds(1); // events are listed by time
        Invitation i = invite("shir");
        clock.advance(tick);
        EnrollmentClaim claim = consume(i, TestTailnet.SHIR_PHONE);
        clock.advance(tick);
        CredentialRecord record = passkey("shir", "c2hpcnMta2V5");
        store.enroll(claim, record, null);
        clock.advance(tick);
        store.recordUse(record, "shir");
        clock.advance(tick);
        store.consume(i.tokenHash(), TestTailnet.SHIR_IPAD.nodeId(), "ipad", "replayed", "replayed"); // a replayed invite link
        clock.advance(tick);
        store.revokePasskey(record.getCredentialId());
        clock.advance(tick);
        store.revokeDevice(TestTailnet.SHIR_PHONE.nodeId());
        clock.advance(tick);
        store.releaseDevice(TestTailnet.SHIR_PHONE.nodeId());
        clock.advance(tick);
        store.revokeMember("shir");
        clock.advance(tick);
        store.consume("0".repeat(64), TestTailnet.SHIR_IPAD.nodeId(), "ipad", "r", "e"); // an unknown token: nothing to record

        assertThat(firestore().securityEvents(50)).extracting(SecurityEvent::type).containsExactly("MEMBER_REVOKED", "DEVICE_RELEASED",
                "DEVICE_REVOKED", "PASSKEY_REVOKED", "INVITATION_REFUSED", "SIGNED_IN", "DEVICE_ENROLLED", "INVITATION_CONSUMED",
                "INVITATION_CREATED");
        assertThat(events("INVITATION_REFUSED")).singleElement().satisfies(e -> {
            assertThat(e.outcome()).isEqualTo("REFUSED");
            assertThat(e.reason()).isEqualTo("already-used");
            assertThat(e.nodeId()).isEqualTo(TestTailnet.SHIR_IPAD.nodeId());
        });
        assertThat(events("DEVICE_ENROLLED")).singleElement().satisfies(e -> {
            assertThat(e.credentialId()).isEqualTo("c2hpcnMta2V5");
            assertThat(e.inviteId()).isEqualTo(i.inviteId());
        });
    }

    @Test
    void aChangeThatFailsLeavesNoEventAndAnEventNeverOutlivesItsChange() {
        Invitation i = invite("shir");
        faults.failBefore("Commit", 1_000);
        assertThatThrownBy(() -> store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid", "eid"))
                .isInstanceOf(StoreUnavailableException.class);
        faults.reset();

        assertThat(events("INVITATION_CONSUMED")).isEmpty();
        Map<String, Object> stored = store.invitation(i.tokenHash()).map(x -> Map.<String, Object>of("status", x.status())).orElseThrow();
        assertThat(stored).containsEntry("status", InvitationStatus.PENDING);
    }
}
