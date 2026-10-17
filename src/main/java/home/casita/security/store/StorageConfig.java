package home.casita.security.store;

import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import home.casita.security.CasitaSecurityProperties;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

/** The security store: files, or DynamoDB with the instance profile's credentials. Seeds members at start. */
@Configuration
class StorageConfig {

    @Bean
    SecurityStore securityStore(StorageProperties storage, CasitaSecurityProperties security, Clock clock,
                                org.springframework.beans.factory.ObjectProvider<DynamoDbClient> dynamo) {
        SecurityStore store;
        if (storage.type() == StorageProperties.Type.DYNAMODB) {
            DynamoTables tables = DynamoTables.withPrefix(storage.tablePrefix());
            DynamoDbClient client = dynamo.getObject();
            tables.verify(client); // missing tables: Casita doesn't start
            store = new DynamoSecurityStore(client, tables, clock);
        } else {
            store = new FileSecurityStore(security.dataDir(), clock);
        }
        store.seedMembers(security.family());
        return store;
    }

    /** Short timeouts: a slow DynamoDB is an unavailable one, and Casita refuses rather than waits. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "casita.storage.type", havingValue = "dynamodb")
    DynamoDbClient dynamoDbClient(StorageProperties storage) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(5)).apiCallAttemptTimeout(Duration.ofSeconds(2)));
        if (storage.region() != null && !storage.region().isBlank()) builder.region(Region.of(storage.region()));
        return builder.build();
    }
}
