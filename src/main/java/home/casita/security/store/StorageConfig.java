package home.casita.security.store;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.retrying.RetrySettings;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;

import home.casita.security.CasitaSecurityProperties;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

/**
 * The security store: files, DynamoDB with the instance profile's credentials, or Firestore with Application Default
 * Credentials. Seeds members at start.
 */
@Configuration
class StorageConfig {

    /** The environment variable the Firestore library reads by itself to switch to an emulator. */
    static final String EMULATOR_ENV = "FIRESTORE_EMULATOR_HOST";

    @Bean
    SecurityStore securityStore(StorageProperties storage, CasitaSecurityProperties security, Clock clock,
                                ObjectProvider<DynamoDbClient> dynamo, ObjectProvider<Firestore> firestore) {
        SecurityStore store;
        if (storage.type() == StorageProperties.Type.DYNAMODB) {
            DynamoTables tables = DynamoTables.withPrefix(storage.tablePrefix());
            DynamoDbClient client = dynamo.getObject();
            tables.verify(client); // missing tables: Casita doesn't start
            store = new DynamoSecurityStore(client, tables, clock);
        } else if (storage.type() == StorageProperties.Type.FIRESTORE) {
            FirestoreCollections collections = FirestoreCollections.standard();
            Firestore client = firestore.getObject();
            collections.verify(client); // unreachable, or not provisioned: Casita doesn't start
            store = new FirestoreSecurityStore(client, collections, clock, storage.firestore().timeout());
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

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "casita.storage.type", havingValue = "firestore")
    Firestore firestore(StorageProperties storage) {
        return firestoreOptions(storage.firestore(), System.getenv(EMULATOR_ENV)).getService();
    }

    /**
     * Short timeouts, as with DynamoDB: about 2 s per attempt and the configured total. Credentials: ADC, which on
     * Compute Engine is the metadata server; nothing here can name a key file. An emulator is used only when named in
     * Casita's own settings and on this machine: the library's environment variable alone would silently swap the
     * real database for an unauthenticated one, so it stops Casita from starting instead.
     */
    static FirestoreOptions firestoreOptions(StorageProperties.Firestore settings, String emulatorEnv) {
        String emulator = settings.emulatorHost();
        boolean configured = emulator != null && !emulator.isBlank();
        if (!configured && emulatorEnv != null && !emulatorEnv.isBlank()) {
            throw new IllegalStateException(EMULATOR_ENV + " is set but casita.storage.firestore.emulator-host isn't; refusing to start");
        }
        FirestoreOptions.Builder builder = FirestoreOptions.newBuilder()
                .setDatabaseId(FirestoreCollections.DATABASE_ID)
                .setRetrySettings(retrySettings(settings.timeout()));
        if (settings.projectId() != null && !settings.projectId().isBlank()) builder.setProjectId(settings.projectId());
        if (configured) {
            String host = emulator.substring(0, emulator.lastIndexOf(':'));
            if (!Set.of("127.0.0.1", "localhost", "[::1]").contains(host)) {
                throw new IllegalStateException("casita.storage.firestore.emulator-host must be on this machine");
            }
            builder.setEmulatorHost(emulator).setCredentialsProvider(FixedCredentialsProvider.create(new FirestoreOptions.EmulatorCredentials()));
        }
        return builder.build();
    }

    static RetrySettings retrySettings(Duration total) {
        Duration attempt = total.compareTo(Duration.ofSeconds(2)) < 0 ? total : Duration.ofSeconds(2);
        return RetrySettings.newBuilder()
                .setTotalTimeoutDuration(total)
                .setInitialRpcTimeoutDuration(attempt).setMaxRpcTimeoutDuration(attempt).setRpcTimeoutMultiplier(1.0)
                .setInitialRetryDelayDuration(Duration.ofMillis(50)).setMaxRetryDelayDuration(Duration.ofMillis(500)).setRetryDelayMultiplier(2.0)
                .build();
    }
}
