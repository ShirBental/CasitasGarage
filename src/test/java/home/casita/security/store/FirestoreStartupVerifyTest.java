package home.casita.security.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.api.gax.retrying.RetrySettings;
import com.google.cloud.firestore.Firestore;

/**
 * The start-up check against a first connection slower than the runtime's 2 s per attempt, as on watson (an e2-micro
 * busy starting the JVM): {@code DEADLINE_EXCEEDED ... connecting_and_lb_delay=2.27s} in FirestoreCollections.verify,
 * and Casita refused to start. The check now has one attempt of its own; the runtime client keeps its short deadlines.
 */
class FirestoreStartupVerifyTest {

    /** Longer than the runtime client's whole budget (5 s, in 2 s attempts): every runtime attempt runs out meanwhile. */
    private static final Duration SLOW_FIRST_CONNECTION = Duration.ofSeconds(6);

    private final FaultyFirestore faults = new FaultyFirestore();
    private final FirestoreCollections collections = FirestoreCollections.standard();

    @BeforeEach
    void emulator() {
        assumeThat(FirestoreEmulator.available()).as("the Firestore emulator (required in CI)").isTrue();
    }

    /** The runtime client, with Casita's own settings: 5 s in all, 2 s per attempt (StorageConfig.retrySettings). */
    private Firestore runtime(String project) {
        return FirestoreEmulator.client(project, faults, Duration.ofSeconds(5));
    }

    @Test
    void onTheRuntimeDeadlinesASlowFirstConnectionStopsStartUp() throws Exception {
        // What happened on watson with release 5954fba: the check ran on the runtime client.
        try (Firestore runtime = runtime(FirestoreEmulator.newProject())) {
            FirestoreEmulator.provision(runtime);
            faults.connectionReadyAfter(SLOW_FIRST_CONNECTION);
            assertThatThrownBy(() -> collections.verify(runtime)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("reachable").rootCause().hasMessageContaining("DEADLINE_EXCEEDED");
        }
    }

    @Test
    void theStartUpCheckWaitsOutAFirstConnectionSlowerThanTwoSeconds() throws Exception {
        for (Duration slow : new Duration[] {Duration.ofMillis(2600), SLOW_FIRST_CONNECTION}) {
            try (Firestore runtime = runtime(FirestoreEmulator.newProject())) {
                FirestoreEmulator.provision(runtime);
                faults.reset();
                faults.connectionReadyAfter(slow);
                StorageConfig.verifyAtStartup(collections, runtime);
                assertThat(faults.calls("BatchGetDocuments")).as(slow + ": one attempt, not retried").isEqualTo(1);
            }
        }
    }

    @Test
    void theRuntimeClientKeepsItsShortDeadlines() throws Exception {
        try (Firestore runtime = runtime(FirestoreEmulator.newProject())) {
            FirestoreEmulator.provision(runtime);
            RetrySettings retry = runtime.getOptions().getRetrySettings();
            assertThat(retry.getMaxRpcTimeoutDuration()).isEqualTo(Duration.ofSeconds(2));
            assertThat(retry.getTotalTimeoutDuration()).isEqualTo(Duration.ofSeconds(5));

            StorageConfig.verifyAtStartup(collections, runtime);
            assertThat(runtime.getOptions().getRetrySettings()).isEqualTo(retry);
            // And it still refuses a slow store rather than waiting on it.
            faults.connectionReadyAfter(SLOW_FIRST_CONNECTION);
            assertThatThrownBy(() -> collections.verify(runtime)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void startUpStillFailsClosed() throws Exception {
        try (Firestore unprovisioned = runtime(FirestoreEmulator.newProject())) {
            assertThatThrownBy(() -> StorageConfig.verifyAtStartup(collections, unprovisioned))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("meta/schema");
        }
        try (Firestore runtime = runtime(FirestoreEmulator.newProject())) {
            FirestoreEmulator.provision(runtime);
            faults.down(true);
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> StorageConfig.verifyAtStartup(collections, runtime))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("reachable");
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).as("an unavailable store is refused, not waited on")
                    .isLessThan(StorageConfig.STARTUP_VERIFY_TIMEOUT);
        }
    }

    @Test
    void aFailedWarmUpDoesNotStopStartUp() throws Exception {
        try (Firestore runtime = runtime(FirestoreEmulator.newProject())) {
            FirestoreEmulator.provision(runtime);
            faults.down(true);
            StorageConfig.warmUp(collections, runtime, Duration.ofSeconds(1)); // logs, doesn't throw
        }
    }
}
