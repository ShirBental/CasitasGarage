package home.casita.security.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;

import home.casita.security.access.TestTailnet;
import home.casita.security.store.SecurityStore.EnrollmentClaim;
import home.casita.security.store.SecurityStore.Invitation;
import home.casita.security.store.SecurityStore.InvitationStatus;
import home.casita.security.store.SecurityStore.Status;

/**
 * What every security store guarantees (docs/PERSISTENCE.md), run against each implementation: single-use
 * invitations, idempotent retries that can't change or extend anything, expiry decided in code, one
 * authorization per node, an all-or-nothing enrollment, revocation that sticks, and restarts.
 */
abstract class SecurityStoreContract {

    protected final TestClock clock = new TestClock();
    protected SecurityStore store;

    /** A fresh, empty store. */
    protected abstract SecurityStore newStore();

    /** The same data, as a restarted Casita would see it. */
    protected abstract SecurityStore restarted();

    @BeforeEach
    void setUpStore() {
        store = newStore();
        store.seedMembers(List.of("shir", "hubby"));
    }

    /* ---------- helpers ---------- */

    private static String random() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    protected Invitation invite(String member) {
        Instant now = clock.instant();
        Invitation i = new Invitation(HexFormat.of().formatHex(UUID.randomUUID().toString().getBytes()), "i-" + random().substring(0, 8), member,
                member + "'s phone", InvitationStatus.PENDING, now, now.plus(Duration.ofMinutes(5)), null, null, null, null, null, null, null);
        store.createInvitation(i);
        return i;
    }

    protected EnrollmentClaim consume(Invitation i, TestTailnet.Phone phone) {
        Invitation c = store.consume(i.tokenHash(), phone.nodeId(), phone.name(), random(), random()).orElseThrow();
        return new EnrollmentClaim(c.tokenHash(), c.inviteId(), c.enrollmentId(), c.memberId(), c.label(), c.nodeId(), c.nodeName());
    }

    protected PublicKeyCredentialUserEntity user(String member) {
        return TestTailnet.user(store, member);
    }

    protected CredentialRecord passkey(String member, String credentialId) {
        return TestTailnet.credential(user(member), credentialId);
    }

    protected void enrolled(String member, String credentialId, TestTailnet.Phone phone) {
        assertThat(store.enroll(consume(invite(member), phone), passkey(member, credentialId), null)).isTrue();
    }

    protected <T> List<T> concurrently(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int k = 0; k < n; k++) futures.add(pool.submit(() -> {
            start.await();
            return task.call();
        }));
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) results.add(f.get());
        pool.shutdown();
        return results;
    }

    /* ---------- members ---------- */

    @Test
    void seedingAddsMembersAndNeverRevivesARevokedOne() {
        assertThat(store.member("shir")).get().satisfies(m -> assertThat(m.active()).isTrue());
        store.revokeMember("shir");

        store.seedMembers(List.of("shir", "hubby"));

        assertThat(store.member("shir")).get().satisfies(m -> assertThat(m.status()).isEqualTo(Status.REVOKED));
        assertThat(restarted().member("shir")).get().satisfies(m -> assertThat(m.status()).isEqualTo(Status.REVOKED));
    }

    @Test
    void aUserHandleIsGivenOnceAndFindsItsMember() {
        Bytes handle = Bytes.random();
        assertThat(store.setUserHandle("shir", handle)).isTrue();
        assertThat(store.setUserHandle("shir", handle)).isTrue();
        assertThat(store.setUserHandle("shir", Bytes.random())).isFalse();
        assertThat(store.memberByUserHandle(handle)).get().extracting(SecurityStore.Member::id).isEqualTo("shir");
        assertThat(store.memberByUserHandle(Bytes.random())).isEmpty();
        assertThat(store.setUserHandle("stranger", Bytes.random())).isFalse();
    }

    /* ---------- invitations ---------- */

    @Test
    void anInvitationIsConsumedOnceForOneNode() {
        Invitation i = invite("shir");

        Optional<Invitation> first = store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid-1", "eid-1");
        Optional<Invitation> second = store.consume(i.tokenHash(), TestTailnet.SHIR_IPAD.nodeId(), "ipad", "rid-2", "eid-2");

        assertThat(first).get().satisfies(c -> {
            assertThat(c.status()).isEqualTo(InvitationStatus.CONSUMED);
            assertThat(c.nodeId()).isEqualTo(TestTailnet.SHIR_PHONE.nodeId());
            assertThat(c.enrollmentId()).isEqualTo("eid-1");
            assertThat(c.memberId()).isEqualTo("shir");
            assertThat(c.expiresAt()).isEqualTo(i.expiresAt());
        });
        assertThat(second).isEmpty();
        assertThat(store.consume("no-such-hash", TestTailnet.SHIR_PHONE.nodeId(), "phone", "r", "e")).isEmpty();
    }

    @Test
    void underConcurrentRedemptionsExactlyOneWins() throws Exception {
        Invitation i = invite("shir");

        List<Optional<Invitation>> results = concurrently(16,
                () -> store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", random(), random()));

        assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
    }

    @Test
    void aRetryOfTheSameAttemptIsIdempotentAndChangesNothing() {
        Invitation i = invite("shir");
        Invitation first = store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid-1", "eid-1").orElseThrow();
        clock.advance(Duration.ofMinutes(1));

        Optional<Invitation> retry = store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid-1", "eid-1");

        assertThat(retry).get().satisfies(r -> {
            assertThat(r.enrollmentId()).isEqualTo("eid-1");
            assertThat(r.nodeId()).isEqualTo(first.nodeId());
            assertThat(r.consumedAt()).isEqualTo(first.consumedAt());
            assertThat(r.expiresAt()).isEqualTo(i.expiresAt());
        });
    }

    @Test
    void aRetryCantReplaceTheEnrollmentOrTheNode() {
        Invitation i = invite("shir");
        store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid-1", "eid-1").orElseThrow();

        assertThat(store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid-1", "eid-OTHER")).isEmpty();
        assertThat(store.consume(i.tokenHash(), TestTailnet.SHIR_IPAD.nodeId(), "ipad", "rid-1", "eid-1")).isEmpty();
        assertThat(store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid-OTHER", "eid-1")).isEmpty();
        assertThat(store.invitationById(i.inviteId())).get().satisfies(c -> {
            assertThat(c.enrollmentId()).isEqualTo("eid-1");
            assertThat(c.nodeId()).isEqualTo(TestTailnet.SHIR_PHONE.nodeId());
        });
    }

    @Test
    void expiryIsDecidedInCodeEvenWhileTheRecordIsStillStored() {
        Invitation i = invite("shir");
        clock.advance(Duration.ofMinutes(5));

        assertThat(store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid", "eid")).isEmpty();
        assertThat(store.invitationById(i.inviteId())).get().satisfies(stored -> {
            assertThat(stored.status()).isEqualTo(InvitationStatus.PENDING); // still there: cleanup is TTL's job, later
            assertThat(stored.expired(clock.instant())).isTrue();
        });
    }

    @Test
    void aRetryCantReviveAnExpiredOrRevokedInvitation() {
        Invitation expiring = invite("shir");
        store.consume(expiring.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid-1", "eid-1").orElseThrow();
        Invitation revoked = invite("shir");
        store.consume(revoked.tokenHash(), TestTailnet.SHIR_IPAD.nodeId(), "ipad", "rid-2", "eid-2").orElseThrow();
        assertThat(store.revokeInvitation(revoked.inviteId())).isTrue();

        assertThat(store.consume(revoked.tokenHash(), TestTailnet.SHIR_IPAD.nodeId(), "ipad", "rid-2", "eid-2")).isEmpty();
        clock.advance(Duration.ofMinutes(5));
        assertThat(store.consume(expiring.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid-1", "eid-1")).isEmpty();
        assertThat(store.invitationById(revoked.inviteId())).get().extracting(Invitation::status).isEqualTo(InvitationStatus.REVOKED);
    }

    @Test
    void revocationWorksBeforeOrDuringEnrollmentButNotAfter() {
        Invitation pending = invite("shir");
        assertThat(store.revokeInvitation(pending.inviteId())).isTrue();
        assertThat(store.consume(pending.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "r", "e")).isEmpty();

        Invitation consumed = invite("shir");
        EnrollmentClaim claim = consume(consumed, TestTailnet.SHIR_PHONE);
        assertThat(store.revokeInvitation(consumed.inviteId())).isTrue();
        assertThat(store.enroll(claim, passkey("shir", "Y3JlZC0x"), null)).isFalse();
        assertThat(store.passkey(Bytes.fromBase64("Y3JlZC0x"))).isEmpty();

        enrolled("shir", "Y3JlZC0y", TestTailnet.SHIR_IPAD);
        Invitation used = store.invitationsOf("shir").stream().filter(i -> i.enrolledAt() != null).findFirst().orElseThrow();
        assertThat(store.revokeInvitation(used.inviteId())).isFalse();
        assertThat(store.revokeInvitation("i-nothere")).isFalse();
    }

    @Test
    void anInvitationTokenIsNeverStored() {
        Invitation i = invite("shir");
        assertThat(store.invitationById(i.inviteId())).get().extracting(Invitation::tokenHash).isEqualTo(i.tokenHash());
        assertThat(i.toString()).doesNotContain(i.tokenHash());
    }

    /* ---------- the enrollment transaction ---------- */

    @Test
    void enrollingWithANewPasskeyAuthorizesTheNodeAndStoresThePasskeyTogether() {
        Invitation i = invite("shir");
        EnrollmentClaim claim = consume(i, TestTailnet.SHIR_PHONE);

        assertThat(store.enroll(claim, passkey("shir", "c2hpcnMta2V5"), null)).isTrue();

        assertThat(store.device(TestTailnet.SHIR_PHONE.nodeId())).get().satisfies(d -> {
            assertThat(d.activeFor("shir")).isTrue();
            assertThat(d.inviteId()).isEqualTo(i.inviteId());
            assertThat(d.label()).isEqualTo("shir's phone");
            assertThat(d.credentialId()).isEqualTo("c2hpcnMta2V5");
        });
        assertThat(store.passkey(Bytes.fromBase64("c2hpcnMta2V5"))).get().satisfies(p -> assertThat(p.activeFor("shir")).isTrue());
        assertThat(store.invitationById(i.inviteId())).get().satisfies(e -> {
            assertThat(e.enrolledAt()).isNotNull();
            assertThat(e.enrolling(clock.instant())).isFalse();
        });
    }

    @Test
    void enrollingWithAnExistingPasskeyAuthorizesOnlyTheNode() {
        enrolled("shir", "c2hpcnMta2V5", TestTailnet.SHIR_PHONE);
        EnrollmentClaim ipad = consume(invite("shir"), TestTailnet.SHIR_IPAD);

        assertThat(store.enroll(ipad, null, Bytes.fromBase64("c2hpcnMta2V5"))).isTrue();

        assertThat(store.device(TestTailnet.SHIR_IPAD.nodeId())).get().satisfies(d -> assertThat(d.activeFor("shir")).isTrue());
        assertThat(store.passkeysOf("shir")).hasSize(1);
    }

    @Test
    void anInvitationEnrollsExactlyOneDevice() {
        EnrollmentClaim claim = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        assertThat(store.enroll(claim, passkey("shir", "Zmlyc3Q"), null)).isTrue();

        // A repeat of the same enrollment reports what is stored (complete) and writes nothing new.
        assertThat(store.enroll(claim, passkey("shir", "Zmlyc3Q"), null)).isTrue();
        // The same invitation can't enroll anything else: another passkey, or the existing one again.
        assertThat(store.enroll(claim, passkey("shir", "c2Vjb25k"), null)).isFalse();
        assertThat(store.passkey(Bytes.fromBase64("c2Vjb25k"))).isEmpty();
        assertThat(store.devicesOf("shir")).hasSize(1);
        assertThat(store.passkeysOf("shir")).hasSize(1);
    }

    @Test
    void anInvitationCantUndoARevocationOfTheDeviceItEnrolled() {
        // One invitation enrolled the node; the admin revoked it within the invitation's five minutes; a second
        // request still holding the same claim (say, a parallel sign-in with an existing passkey) tries again.
        enrolled("shir", "ZXhpc3Rpbmc", TestTailnet.SHIR_PHONE);
        EnrollmentClaim claim = consume(invite("shir"), TestTailnet.SHIR_IPAD);
        assertThat(store.enroll(claim, passkey("shir", "bmV3"), null)).isTrue();
        assertThat(store.revokeDevice(TestTailnet.SHIR_IPAD.nodeId())).isTrue();

        assertThat(store.enroll(claim, null, Bytes.fromBase64("ZXhpc3Rpbmc"))).isFalse();

        assertThat(store.device(TestTailnet.SHIR_IPAD.nodeId())).get().satisfies(d -> assertThat(d.status()).isEqualTo(Status.REVOKED));
    }

        @Test
    void aClaimThatDoesntMatchTheInvitationWritesNothing() {
        EnrollmentClaim real = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        List<EnrollmentClaim> forged = List.of(
                new EnrollmentClaim(real.tokenHash(), real.inviteId(), "other-enrollment", "shir", real.label(), real.nodeId(), real.nodeName()),
                new EnrollmentClaim(real.tokenHash(), real.inviteId(), real.enrollmentId(), "shir", real.label(), TestTailnet.SHIR_IPAD.nodeId(), "ipad"),
                new EnrollmentClaim(real.tokenHash(), real.inviteId(), real.enrollmentId(), "hubby", real.label(), real.nodeId(), real.nodeName()));
        for (EnrollmentClaim claim : forged) {
            assertThat(store.enroll(claim, passkey(claim.memberId(), "Zm9yZ2Vk"), null)).as(claim.toString()).isFalse();
        }
        assertThat(store.passkey(Bytes.fromBase64("Zm9yZ2Vk"))).isEmpty();
        assertThat(store.device(TestTailnet.SHIR_PHONE.nodeId())).isEmpty();
        assertThat(store.device(TestTailnet.SHIR_IPAD.nodeId())).isEmpty();
        assertThat(store.enroll(real, passkey("shir", "cmVhbA"), null)).isTrue(); // the real one still works
    }

    @Test
    void enrollmentAfterExpiryIsRefusedAndNothingIsExtended() {
        Invitation i = invite("shir");
        EnrollmentClaim claim = consume(i, TestTailnet.SHIR_PHONE);
        clock.advance(Duration.ofMinutes(5));

        assertThat(store.enroll(claim, passkey("shir", "bGF0ZQ"), null)).isFalse();

        assertThat(store.device(TestTailnet.SHIR_PHONE.nodeId())).isEmpty();
        assertThat(store.invitationById(i.inviteId())).get().extracting(Invitation::expiresAt).isEqualTo(i.expiresAt());
    }

    @Test
    void aRevokedMemberCantEnroll() {
        EnrollmentClaim claim = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        CredentialRecord record = passkey("shir", "cmV2b2tlZA");
        store.revokeMember("shir");

        assertThat(store.enroll(claim, record, null)).isFalse();
        assertThat(store.passkey(Bytes.fromBase64("cmV2b2tlZA"))).isEmpty();
        assertThat(store.device(TestTailnet.SHIR_PHONE.nodeId())).isEmpty();
    }

    @Test
    void anExistingPasskeyMustBeActiveAndTheMembers() {
        enrolled("shir", "c2hpcnMta2V5", TestTailnet.SHIR_PHONE);
        enrolled("hubby", "aHViYnlzLWtleQ", TestTailnet.HUBBY_PHONE);

        EnrollmentClaim withHubbysPasskey = consume(invite("shir"), TestTailnet.SHIR_IPAD);
        assertThat(store.enroll(withHubbysPasskey, null, Bytes.fromBase64("aHViYnlzLWtleQ"))).isFalse();

        store.revokePasskey(Bytes.fromBase64("c2hpcnMta2V5"));
        EnrollmentClaim withRevoked = consume(invite("shir"), TestTailnet.SHIR_IPAD);
        assertThat(store.enroll(withRevoked, null, Bytes.fromBase64("c2hpcnMta2V5"))).isFalse();
        assertThat(store.device(TestTailnet.SHIR_IPAD.nodeId())).isEmpty();
    }

    @Test
    void aNewPasskeyIdThatAlreadyExistsIsRefusedWithNothingWritten() {
        enrolled("shir", "c2hpcnMta2V5", TestTailnet.SHIR_PHONE);
        EnrollmentClaim claim = consume(invite("shir"), TestTailnet.SHIR_IPAD);

        assertThat(store.enroll(claim, passkey("shir", "c2hpcnMta2V5"), null)).isFalse();
        assertThat(store.device(TestTailnet.SHIR_IPAD.nodeId())).isEmpty();
        assertThat(store.invitationById(claim.inviteId())).get().satisfies(i -> assertThat(i.enrolledAt()).isNull());
    }

    /* ---------- one authorization per node ---------- */

    @Test
    void anActiveNodeCantBeOverwrittenOrReassigned() {
        enrolled("shir", "c2hpcnMta2V5", TestTailnet.SHIR_PHONE);

        EnrollmentClaim second = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        assertThat(store.enroll(second, passkey("shir", "c2Vjb25k"), null)).isFalse();
        EnrollmentClaim hubby = consume(invite("hubby"), TestTailnet.SHIR_PHONE);
        assertThat(store.enroll(hubby, passkey("hubby", "aHViYnk"), null)).isFalse();

        assertThat(store.device(TestTailnet.SHIR_PHONE.nodeId())).get().satisfies(d -> {
            assertThat(d.activeFor("shir")).isTrue();
            assertThat(d.credentialId()).isEqualTo("c2hpcnMta2V5");
        });
        assertThat(store.passkey(Bytes.fromBase64("c2Vjb25k"))).isEmpty();
        assertThat(store.passkey(Bytes.fromBase64("aHViYnk"))).isEmpty();
    }

    @Test
    void aRevokedNodeCanBeReEnrolledOnlyForItsMemberUntilReleased() {
        enrolled("shir", "c2hpcnMta2V5", TestTailnet.SHIR_PHONE);
        store.revokeDevice(TestTailnet.SHIR_PHONE.nodeId());

        EnrollmentClaim hubby = consume(invite("hubby"), TestTailnet.SHIR_PHONE);
        assertThat(store.enroll(hubby, passkey("hubby", "aHViYnk"), null)).isFalse();

        EnrollmentClaim shirAgain = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        assertThat(store.enroll(shirAgain, null, Bytes.fromBase64("c2hpcnMta2V5"))).isTrue();
        assertThat(store.device(TestTailnet.SHIR_PHONE.nodeId())).get().satisfies(d -> assertThat(d.activeFor("shir")).isTrue());

        // Released by the admin: now another member's invite may enroll it.
        store.revokeDevice(TestTailnet.SHIR_PHONE.nodeId());
        assertThat(store.releaseDevice(TestTailnet.SHIR_PHONE.nodeId())).isTrue();
        EnrollmentClaim hubbyAfterRelease = consume(invite("hubby"), TestTailnet.SHIR_PHONE);
        assertThat(store.enroll(hubbyAfterRelease, passkey("hubby", "aHViYnk"), null)).isTrue();
        assertThat(store.device(TestTailnet.SHIR_PHONE.nodeId())).get().satisfies(d -> assertThat(d.activeFor("hubby")).isTrue());
        assertThat(store.releaseDevice(TestTailnet.SHIR_PHONE.nodeId())).isFalse(); // only a revoked node can be released
    }

    @Test
    void concurrentEnrollmentsOfOneNodeAuthorizeItOnce() throws Exception {
        user("shir");
        user("hubby");
        EnrollmentClaim shir = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        EnrollmentClaim hubby = consume(invite("hubby"), TestTailnet.SHIR_PHONE);
        CredentialRecord shirs = passkey("shir", "c2hpcg");
        CredentialRecord hubbys = passkey("hubby", "aHViYnk");

        List<Boolean> results = concurrently(2, new Callable<>() {
            private final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public Boolean call() {
                return next.getAndIncrement() == 0 ? store.enroll(shir, shirs, null) : store.enroll(hubby, hubbys, null);
            }
        });

        assertThat(results).containsOnlyOnce(true);
        String owner = store.device(TestTailnet.SHIR_PHONE.nodeId()).orElseThrow().memberId();
        String loser = owner.equals("shir") ? "aHViYnk" : "c2hpcg";
        assertThat(store.passkey(Bytes.fromBase64(loser))).isEmpty(); // the refused one left nothing behind
    }

    /* ---------- revocation and views ---------- */

    @Test
    void theAccessViewSeesEveryRevocationAtOnce() {
        enrolled("shir", "c2hpcnMta2V5", TestTailnet.SHIR_PHONE);
        Bytes key = Bytes.fromBase64("c2hpcnMta2V5");
        assertThat(store.accessView("shir", key, TestTailnet.SHIR_PHONE.nodeId())).satisfies(v -> {
            assertThat(v.member()).get().satisfies(m -> assertThat(m.active()).isTrue());
            assertThat(v.passkey()).get().satisfies(p -> assertThat(p.activeFor("shir")).isTrue());
            assertThat(v.device()).get().satisfies(d -> assertThat(d.activeFor("shir")).isTrue());
        });

        assertThat(store.revokeDevice(TestTailnet.SHIR_PHONE.nodeId())).isTrue();
        assertThat(store.accessView("shir", key, TestTailnet.SHIR_PHONE.nodeId()).device()).get()
                .satisfies(d -> assertThat(d.activeFor("shir")).isFalse());
        assertThat(store.revokePasskey(key)).isTrue();
        assertThat(store.revokePasskey(key)).isFalse();
        assertThat(store.accessView("shir", key, TestTailnet.SHIR_PHONE.nodeId()).passkey()).get()
                .satisfies(p -> assertThat(p.activeFor("shir")).isFalse());
        assertThat(store.passkeysOf("shir")).isEmpty();
    }

    @Test
    void revokingAMemberIsOneBaseRecordAndCleanupFollows() {
        enrolled("shir", "c2hpcnMta2V5", TestTailnet.SHIR_PHONE);
        Invitation open = invite("shir");
        enrolled("hubby", "aHViYnlzLWtleQ", TestTailnet.HUBBY_PHONE);

        assertThat(store.revokeMember("shir")).isTrue();
        // Before any cleanup: the member record alone refuses everything.
        assertThat(store.accessView("shir", Bytes.fromBase64("c2hpcnMta2V5"), TestTailnet.SHIR_PHONE.nodeId()).member()).get()
                .satisfies(m -> assertThat(m.active()).isFalse());

        SecurityStore.Cleanup cleanup = store.cleanUpMember("shir");
        assertThat(cleanup.devices()).isEqualTo(1);
        assertThat(cleanup.passkeys()).isEqualTo(1);
        assertThat(cleanup.invitations()).isEqualTo(1);
        assertThat(store.cleanUpMember("shir")).isEqualTo(new SecurityStore.Cleanup(0, 0, 0)); // repeatable
        assertThat(store.consume(open.tokenHash(), TestTailnet.SHIR_IPAD.nodeId(), "ipad", "r", "e")).isEmpty();
        assertThat(store.device(TestTailnet.HUBBY_PHONE.nodeId())).get().satisfies(d -> assertThat(d.activeFor("hubby")).isTrue());
    }

    @Test
    void theEnrollmentViewReadsTheInvitationAndTheMember() {
        EnrollmentClaim claim = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        SecurityStore.EnrollmentView view = store.enrollmentView(claim.tokenHash(), "shir");
        assertThat(view.invitation()).get().satisfies(i -> assertThat(i.enrolling(clock.instant())).isTrue());
        assertThat(view.member()).get().satisfies(m -> assertThat(m.active()).isTrue());
        assertThat(store.enrollmentView("nope", "nobody").invitation()).isEmpty();
    }

    @Test
    void aSignInCounterIsRecordedOnlyForAnActivePasskeyOfTheMember() {
        enrolled("shir", "c2hpcnMta2V5", TestTailnet.SHIR_PHONE);
        CredentialRecord used = passkey("shir", "c2hpcnMta2V5");

        assertThat(store.recordUse(used, "shir")).isTrue();
        assertThat(store.recordUse(used, "hubby")).isFalse();
        store.revokePasskey(used.getCredentialId());
        assertThat(store.recordUse(used, "shir")).isFalse();
    }

    /* ---------- restarts ---------- */

    @Test
    void everythingSurvivesARestartAndRevocationsStayRevoked() {
        enrolled("shir", "c2hpcnMta2V5", TestTailnet.SHIR_PHONE);
        enrolled("hubby", "aHViYnlzLWtleQ", TestTailnet.HUBBY_PHONE);
        Invitation pending = invite("shir");
        store.revokeDevice(TestTailnet.HUBBY_PHONE.nodeId());

        SecurityStore after = restarted();

        assertThat(after.device(TestTailnet.SHIR_PHONE.nodeId())).get().satisfies(d -> assertThat(d.activeFor("shir")).isTrue());
        assertThat(after.device(TestTailnet.HUBBY_PHONE.nodeId())).get().satisfies(d -> assertThat(d.status()).isEqualTo(Status.REVOKED));
        assertThat(after.passkey(Bytes.fromBase64("c2hpcnMta2V5"))).get().satisfies(p -> {
            assertThat(p.record().getPublicKey().getBytes()).containsExactly(1, 2, 3);
            assertThat(p.record().isBackupEligible()).isTrue();
        });
        // A pending invitation is still good after a restart (it is stored, not in memory).
        assertThat(after.consume(pending.tokenHash(), TestTailnet.SHIR_IPAD.nodeId(), "ipad", "r", "e")).isPresent();
    }

    @Test
    void aConsumedInvitationStaysConsumedAcrossARestart() {
        Invitation i = invite("shir");
        Invitation consumed = store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid-1", "eid-1").orElseThrow();

        SecurityStore after = restarted();

        assertThat(after.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid-2", "eid-2")).isEmpty();
        assertThat(after.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid-1", "eid-1")).get()
                .satisfies(c -> assertThat(c.consumedAt()).isEqualTo(consumed.consumedAt()));
        EnrollmentClaim claim = new EnrollmentClaim(consumed.tokenHash(), consumed.inviteId(), "eid-1", "shir", consumed.label(),
                consumed.nodeId(), consumed.nodeName());
        assertThat(after.enroll(claim, passkey("shir", "YWZ0ZXI"), null)).isTrue(); // the claim (if it survived) still completes once
        assertThat(after.enroll(new EnrollmentClaim(claim.tokenHash(), claim.inviteId(), "eid-2", "shir", claim.label(), claim.nodeId(),
                claim.nodeName()), passkey("shir", "YWdhaW4"), null)).isFalse();
    }

        @Test
    void aNewInvitationNeedsAFreshHash() {
        Invitation i = invite("shir");
        assertThatThrownBy(() -> store.createInvitation(i)).isInstanceOf(IllegalStateException.class);
    }

    protected PublicKeyCredentialUserEntity entity(String member, Bytes handle) {
        return ImmutablePublicKeyCredentialUserEntity.builder().name(member).id(handle).displayName(member).build();
    }
}
