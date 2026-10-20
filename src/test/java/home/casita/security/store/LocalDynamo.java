package home.casita.security.store;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.amazonaws.services.dynamodbv2.local.embedded.DynamoDBEmbedded;
import com.amazonaws.services.dynamodbv2.local.shared.access.AmazonDynamoDBLocal;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DynamoDB Local inside the test JVM: in memory, no port, no Docker, no AWS account. Tables are created from
 * {@link DynamoTables#definitions()}, the same shapes the infrastructure code will create.
 */
public final class LocalDynamo {

    private LocalDynamo() {}

    /** Settings for a Spring test in DynamoDB mode (the region only lets the unused real client build; nothing calls AWS). */
    public static final String[] PROPERTIES = {"casita.storage.type=dynamodb", "casita.storage.region=us-east-1"};

    /** The only way tests start DynamoDB Local: in memory, with its usage telemetry off (no network calls). */
    public static AmazonDynamoDBLocal embedded() {
        return DynamoDBEmbedded.create(true); // true: disableTelemetry
    }

        public static DynamoDbClient start(AmazonDynamoDBLocal local, DynamoTables tables) {
        DynamoDbClient client = local.dynamoDbClient();
        tables.definitions().forEach(client::createTable);
        return client;
    }

    @TestConfiguration
    public static class Config {
        @Bean(destroyMethod = "shutdown")
        AmazonDynamoDBLocal dynamoDbLocal() {
            return embedded();
        }

        /** The faulty wrapper lets a test make DynamoDB fail; it passes everything through until told otherwise. */
        @Bean
        FaultyDynamo faultyDynamo(AmazonDynamoDBLocal local) {
            return new FaultyDynamo(start(local, DynamoTables.withPrefix("casita-")));
        }

        @Bean
        @Primary
        DynamoDbClient localDynamoDbClient(FaultyDynamo faulty) {
            return faulty.client();
        }
    }
}
