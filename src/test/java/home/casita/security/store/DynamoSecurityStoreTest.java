package home.casita.security.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.webauthn.api.Bytes;

import com.amazonaws.services.dynamodbv2.local.monitoring.Telemetry;
import com.amazonaws.services.dynamodbv2.local.monitoring.TelemetryUtil;
import com.amazonaws.services.dynamodbv2.local.shared.access.AmazonDynamoDBLocal;

import home.casita.security.access.TestTailnet;
import home.casita.security.store.SecurityStore.EnrollmentClaim;
import home.casita.security.store.SecurityStore.Invitation;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The contract, on DynamoDB Local in the test JVM, plus what only a remote store has: lost responses, failures
 * before sending, a failed recovery read, an outage, and that nothing depends on TTL or an index.
 */
class DynamoSecurityStoreTest extends SecurityStoreContract {

    private final DynamoTables tables = DynamoTables.withPrefix("test-");
    private AmazonDynamoDBLocal local;
    private FaultyDynamo dynamo;

    @Override
    protected SecurityStore newStore() {
        local = LocalDynamo.embedded();
        dynamo = new FaultyDynamo(LocalDynamo.start(local, tables));
        return new DynamoSecurityStore(dynamo.client(), tables, clock);
    }

    @Override
    protected SecurityStore restarted() {
        return new DynamoSecurityStore(dynamo.client(), tables, clock);
    }

    @AfterEach
    void stop() {
        local.shutdown();
    }

    @Test
    void theTablesAreThereAtStartUpOrCasitaDoesntStart() {
        tables.verify(dynamo.client());
        assertThatThrownBy(() -> DynamoTables.withPrefix("missing-").verify(dynamo.client())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing-members");
    }

    @Test
    void invitationsAreKeptThirtyDaysPastExpiryByTtlWhichNothingReads() {
        Invitation i = invite("shir");
        Map<String, AttributeValue> item = dynamo.real().getItem(b -> b.tableName(tables.invitations())
                .key(Map.of("tokenHash", AttributeValue.fromS(i.tokenHash()))).consistentRead(true)).item();

        assertThat(Long.parseLong(item.get("ttl").n())).isEqualTo(i.expiresAt().plus(DynamoSecurityStore.INVITATION_RETENTION).getEpochSecond());
        assertThat(item).doesNotContainKey("token");
        assertThat(item.get("status").s()).isEqualTo("PENDING");
    }

    /* ---------- consume ---------- */

    @Test
    void aConsumeWhoseResponseWasLostIsRetriedAsTheSameAttempt() {
        Invitation i = invite("shir");
        dynamo.loseResponse("updateItem", 1);

        Optional<Invitation> consumed = store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid-1", "eid-1");

        assertThat(consumed).get().satisfies(c -> {
            assertThat(c.enrollmentId()).isEqualTo("eid-1");
            assertThat(c.expiresAt()).isEqualTo(i.expiresAt());
        });
        // And still single use for anyone else.
        assertThat(store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid-2", "eid-2")).isEmpty();
    }

    @Test
    void aConsumeThatKeepsFailingIsRefusedAsUnavailable() {
        Invitation i = invite("shir");
        dynamo.failBefore("updateItem", 2);

        assertThatThrownBy(() -> store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "rid", "eid"))
                .isInstanceOf(StoreUnavailableException.class);
        assertThat(store.invitationById(i.inviteId())).get().extracting(Invitation::status).isEqualTo(SecurityStore.InvitationStatus.PENDING);
    }

    /* ---------- the enrollment transaction: unknown outcomes ---------- */

    @Test
    void aLostTransactionResponseIsSettledByReadingWhatIsStored() {
        EnrollmentClaim claim = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        dynamo.loseResponse("transactWriteItems", 1);

        assertThat(store.enroll(claim, passkey("shir", "c2hpcnMta2V5"), null)).isTrue();

        assertThat(store.devicesOf("shir")).hasSize(1);
        assertThat(store.passkeysOf("shir")).hasSize(1);
    }

    @Test
    void aTransactionThatNeverRanIsAFailureWithNothingWritten() {
        EnrollmentClaim claim = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        dynamo.failBefore("transactWriteItems", 1);

        assertThat(store.enroll(claim, passkey("shir", "c2hpcnMta2V5"), null)).isFalse();

        assertThat(store.device(TestTailnet.SHIR_PHONE.nodeId())).isEmpty();
        assertThat(store.passkey(Bytes.fromBase64("c2hpcnMta2V5"))).isEmpty();
        assertThat(store.invitationById(claim.inviteId())).get().satisfies(i -> assertThat(i.enrolledAt()).isNull());
    }

    @Test
    void ifTheRecoveryReadFailsTooItIsRefusedButStoredStateIsWhole() {
        EnrollmentClaim claim = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        dynamo.loseResponse("transactWriteItems", 1);
        dynamo.failBefore("transactGetItems", 1);

        assertThat(store.enroll(claim, passkey("shir", "c2hpcnMta2V5"), null)).isFalse(); // fail closed

        // It did happen, entirely: the next sign-in on that phone will find device and passkey both.
        assertThat(store.accessView("shir", Bytes.fromBase64("c2hpcnMta2V5"), TestTailnet.SHIR_PHONE.nodeId())).satisfies(v -> {
            assertThat(v.device()).get().satisfies(d -> assertThat(d.activeFor("shir")).isTrue());
            assertThat(v.passkey()).get().satisfies(p -> assertThat(p.activeFor("shir")).isTrue());
        });
    }

    @Test
    void aResendInsideTheIdempotencyWindowIsAcknowledgedWithoutRunningAgain() {
        EnrollmentClaim claim = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        assertThat(store.enroll(claim, passkey("shir", "c2hpcnMta2V5"), null)).isTrue();

        assertThat(store.enroll(claim, passkey("shir", "c2hpcnMta2V5"), null)).isTrue(); // same token: DynamoDB answers success

        assertThat(dynamo.realErrors()).isEmpty();
        assertThat(store.devicesOf("shir")).hasSize(1);
        assertThat(store.passkeysOf("shir")).hasSize(1);
    }

    @Test
    void aResendAfterTheIdempotencyWindowRunsAgainIsRefusedByItsConditionsAndDuplicatesNothing() {
        // DynamoDB no longer recognizes the token after ten minutes, so the resend really executes. A fresh token
        // makes DynamoDB Local do the same now (its own window hasn't passed in real time).
        DynamoSecurityStore dynamoStore = (DynamoSecurityStore) store;
        Invitation invitation = invite("shir");
        EnrollmentClaim claim = consume(invitation, TestTailnet.SHIR_PHONE);
        var record = passkey("shir", "c2hpcnMta2V5");
        assertThat(dynamoStore.enroll(claim, record, null)).isTrue();

        assertThat(dynamoStore.enroll(claim, record, "c2hpcnMta2V5", "resent-after-the-window-1")).isTrue(); // read back: it is complete
        clock.advance(java.time.Duration.ofMinutes(11)); // and past the invitation's expiry too
        assertThat(dynamoStore.enroll(claim, record, "c2hpcnMta2V5", "resent-after-the-window-2")).isTrue();

        assertThat(dynamo.realErrors()).containsExactly("transactWriteItems: TransactionCanceledException",
                "transactWriteItems: TransactionCanceledException"); // it ran, and its conditions refused it, twice
        assertThat(store.devicesOf("shir")).hasSize(1);
        assertThat(store.passkeysOf("shir")).hasSize(1);
        assertThat(store.invitationById(claim.inviteId())).get().satisfies(i -> {
            assertThat(i.expiresAt()).isEqualTo(invitation.expiresAt());
            assertThat(i.enrolledAt()).isNotNull();
        });
    }

    @Test
    void aResendAfterTheWindowForAnEnrollmentThatNeverHappenedStillCantSucceedLate() {
        DynamoSecurityStore dynamoStore = (DynamoSecurityStore) store;
        EnrollmentClaim claim = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        var record = passkey("shir", "c2hpcnMta2V5");
        clock.advance(java.time.Duration.ofMinutes(11));

        assertThat(dynamoStore.enroll(claim, record, "c2hpcnMta2V5", "late-resend")).isFalse();
        assertThat(store.device(TestTailnet.SHIR_PHONE.nodeId())).isEmpty();
        assertThat(store.passkey(record.getCredentialId())).isEmpty();
    }

    /* ---------- M1: revocation doesn't depend on an index, and never reports a false success ---------- */

    @Test
    void revokingByTokenHashWorksWhileTheIndexHasntCaughtUp() {
        Invitation i = invite("shir");
        dynamo.indexesLag(true);

        assertThat(store.revokeInvitation(i.tokenHash(), i.inviteId())).isEqualTo(SecurityStore.RevokeOutcome.REVOKED);

        dynamo.indexesLag(false);
        assertThat(store.invitation(i.tokenHash())).get().extracting(Invitation::status).isEqualTo(SecurityStore.InvitationStatus.REVOKED);
    }

    @Test
    void revokingByIdWhileTheIndexLagsIsNotFoundAndChangesNothing() {
        Invitation i = invite("shir");
        dynamo.indexesLag(true);

        assertThat(store.revokeInvitation(i.inviteId())).isEqualTo(SecurityStore.RevokeOutcome.NOT_FOUND);

        dynamo.indexesLag(false);
        assertThat(store.invitation(i.tokenHash())).get().extracting(Invitation::status).isEqualTo(SecurityStore.InvitationStatus.PENDING);
        assertThat(store.revokeInvitation(i.inviteId())).isEqualTo(SecurityStore.RevokeOutcome.REVOKED); // once it is indexed
    }

    @Test
    void aRevocationDuringAnOutageIsAnErrorNotAnAnswer() {
        Invitation i = invite("shir");
        dynamo.down(true);

        assertThatThrownBy(() -> store.revokeInvitation(i.tokenHash(), i.inviteId())).isInstanceOf(StoreUnavailableException.class);

        dynamo.down(false);
        assertThat(store.invitation(i.tokenHash())).get().extracting(Invitation::status).isEqualTo(SecurityStore.InvitationStatus.PENDING);
    }

    @Test
    void aRevocationWhoseResponseWasLostIsAnErrorAndARerunTellsTheTruth() {
        Invitation i = invite("shir");
        dynamo.loseResponse("updateItem", 1);

        // It happened, but the caller can't know: an error, never a success.
        assertThatThrownBy(() -> store.revokeInvitation(i.tokenHash(), i.inviteId())).isInstanceOf(StoreUnavailableException.class);

        assertThat(store.revokeInvitation(i.tokenHash(), i.inviteId())).isEqualTo(SecurityStore.RevokeOutcome.ALREADY_REVOKED);
        assertThat(store.consume(i.tokenHash(), TestTailnet.SHIR_PHONE.nodeId(), "phone", "r", "e")).isEmpty();
    }

    @Test
    void theRefusedRevocationsReasonIsReadFromTheBaseTableEvenWhileIndexesLag() {
        enrolled("shir", "c2hpcnMta2V5", TestTailnet.SHIR_PHONE);
        Invitation used = store.invitationsOf("shir").getFirst();
        dynamo.indexesLag(true);

        assertThat(store.revokeInvitation(used.tokenHash(), used.inviteId())).isEqualTo(SecurityStore.RevokeOutcome.ALREADY_ENROLLED);
    }

        /* ---------- DynamoDB Local's telemetry ---------- */

    @Test
    void dynamoDbLocalTelemetryIsOffInThisJvm() {
        // DynamoDB Local creates its Pinpoint telemetry client only when telemetry is enabled; it is a JVM-wide
        // singleton, so its absence here means no test in this JVM so far has turned it on.
        LocalDynamo.embedded().shutdown();
        assertThat(Telemetry.getTelemetry()).isEmpty();
        // And the second guard (surefire's DDB_LOCAL_TELEMETRY=0) would hold even if code forgot the flag.
        assertThat(TelemetryUtil.isTelemetryEnabled(false)).isFalse();
    }

    @Test
    void theIdempotencyTokenIsPerEnrollmentAndPasskey() {
        EnrollmentClaim claim = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        assertThat(DynamoSecurityStore.requestToken(claim, "YQ")).hasSize(36).isNotEqualTo(DynamoSecurityStore.requestToken(claim, "Yg"));
    }

    /* ---------- outages ---------- */

    @Test
    void duringAnOutageEveryReadAndWriteIsUnavailable() {
        enrolled("shir", "c2hpcnMta2V5", TestTailnet.SHIR_PHONE);
        Invitation i = invite("shir");
        dynamo.down(true);

        for (Runnable call : List.<Runnable>of(
                () -> store.accessView("shir", Bytes.fromBase64("c2hpcnMta2V5"), TestTailnet.SHIR_PHONE.nodeId()),
                () -> store.enrollmentView(i.tokenHash(), "shir"),
                () -> store.member("shir"),
                () -> store.consume(i.tokenHash(), TestTailnet.SHIR_IPAD.nodeId(), "ipad", "r", "e"),
                () -> store.revokeDevice(TestTailnet.SHIR_PHONE.nodeId()))) {
            assertThatThrownBy(call::run).isInstanceOf(StoreUnavailableException.class);
        }
        dynamo.down(false);
    }

    @Test
    void anIndexThatHasntCaughtUpCanOnlyHideThingsNeverAllowThem() {
        // Indexes only find keys; every answer is read again from the base table. A record the index still
        // lists but the base table has changed is judged by the base table.
        enrolled("shir", "c2hpcnMta2V5", TestTailnet.SHIR_PHONE);
        dynamo.real().updateItem(b -> b.tableName(tables.passkeys()).key(Map.of("credentialId", AttributeValue.fromS("c2hpcnMta2V5")))
                .updateExpression("SET #st = :r").expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(Map.of(":r", AttributeValue.fromS("REVOKED"))));
        assertThat(store.passkeysOf("shir")).isEmpty();
    }
}
