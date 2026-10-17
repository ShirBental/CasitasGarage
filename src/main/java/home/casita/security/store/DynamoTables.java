package home.casita.security.store;

import java.util.List;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * The security tables' names and shapes (docs/PERSISTENCE.md). Casita never creates tables in AWS: infrastructure
 * code does, reviewed separately. {@link #definitions()} is the reference for that code and what the tests create
 * in DynamoDB Local; {@link #verify} is the start-up check.
 */
public record DynamoTables(String members, String passkeys, String devices, String invitations) {

    public static final String BY_USER_HANDLE = "byUserHandle";
    public static final String BY_MEMBER = "byMember";
    public static final String BY_INVITE_ID = "byInviteId";

    public static DynamoTables withPrefix(String prefix) {
        return new DynamoTables(prefix + "members", prefix + "passkeys", prefix + "devices", prefix + "invitations");
    }

    public List<CreateTableRequest> definitions() {
        return List.of(
                table(members, "memberId", List.of(attribute("memberId", ScalarAttributeType.S), attribute("userHandle", ScalarAttributeType.S)),
                        List.of(index(BY_USER_HANDLE, "userHandle", null))),
                table(passkeys, "credentialId", List.of(attribute("credentialId", ScalarAttributeType.S), attribute("memberId", ScalarAttributeType.S),
                        attribute("createdAt", ScalarAttributeType.N)), List.of(index(BY_MEMBER, "memberId", "createdAt"))),
                table(devices, "nodeId", List.of(attribute("nodeId", ScalarAttributeType.S), attribute("memberId", ScalarAttributeType.S)),
                        List.of(index(BY_MEMBER, "memberId", null))),
                table(invitations, "tokenHash", List.of(attribute("tokenHash", ScalarAttributeType.S), attribute("inviteId", ScalarAttributeType.S),
                        attribute("memberId", ScalarAttributeType.S), attribute("createdAt", ScalarAttributeType.N)),
                        List.of(index(BY_INVITE_ID, "inviteId", null), index(BY_MEMBER, "memberId", "createdAt"))));
    }

    /** At start-up in DynamoDB mode: every table must exist, or Casita doesn't start. */
    public void verify(DynamoDbClient client) {
        for (String table : List.of(members, passkeys, devices, invitations)) {
            try {
                client.describeTable(b -> b.tableName(table));
            } catch (ResourceNotFoundException e) {
                throw new IllegalStateException("DynamoDB table " + table + " doesn't exist; Casita doesn't create tables (docs/PERSISTENCE.md)", e);
            }
        }
    }

    private static CreateTableRequest table(String name, String key, List<AttributeDefinition> attributes, List<GlobalSecondaryIndex> indexes) {
        return CreateTableRequest.builder().tableName(name).billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(attributes)
                .keySchema(KeySchemaElement.builder().attributeName(key).keyType(KeyType.HASH).build())
                .globalSecondaryIndexes(indexes)
                .build();
    }

    private static AttributeDefinition attribute(String name, ScalarAttributeType type) {
        return AttributeDefinition.builder().attributeName(name).attributeType(type).build();
    }

    /** KEYS_ONLY: an index only finds keys; the base table is always read again before anything is decided. */
    private static GlobalSecondaryIndex index(String name, String partition, String sort) {
        GlobalSecondaryIndex.Builder index = GlobalSecondaryIndex.builder().indexName(name)
                .projection(Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build());
        return sort == null
                ? index.keySchema(KeySchemaElement.builder().attributeName(partition).keyType(KeyType.HASH).build()).build()
                : index.keySchema(KeySchemaElement.builder().attributeName(partition).keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName(sort).keyType(KeyType.RANGE).build()).build();
    }
}
