package home.casita.security.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.webauthn.api.Bytes;

import com.amazonaws.services.dynamodbv2.local.embedded.DynamoDBEmbedded;
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
        local = DynamoDBEmbedded.create();
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
    void resendingTheSameTransactionAfterSuccessNeverDuplicatesAnything() {
        EnrollmentClaim claim = consume(invite("shir"), TestTailnet.SHIR_PHONE);
        assertThat(store.enroll(claim, passkey("shir", "c2hpcnMta2V5"), null)).isTrue();

        // As a retry inside DynamoDB's idempotency window would, and as one after it would (conditions refuse it).
        assertThat(store.enroll(claim, passkey("shir", "c2hpcnMta2V5"), null)).isTrue();
        clock.advance(java.time.Duration.ofMinutes(11));
        assertThat(store.enroll(claim, passkey("shir", "c2hpcnMta2V5"), null)).isTrue(); // still what is stored

        assertThat(store.devicesOf("shir")).hasSize(1);
        assertThat(store.passkeysOf("shir")).hasSize(1);
        assertThat(store.invitationById(claim.inviteId())).get().satisfies(i -> assertThat(i.expiresAt()).isEqualTo(
                i.createdAt().plus(java.time.Duration.ofMinutes(5))));
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
