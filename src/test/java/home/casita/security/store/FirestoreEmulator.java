package home.casita.security.store;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;

import io.grpc.ManagedChannelBuilder;

/**
 * The Firestore emulator for tests: Google's emulator jar (the one firebase-tools runs), started once per test JVM on
 * 127.0.0.1 and a free port, never a fixed one. No Docker, no gcloud, no Google account: nothing leaves this machine.
 *
 * <p>Where it comes from, in this order: {@code -Dfirestore.emulator.host=127.0.0.1:PORT} (one already running), or
 * {@code -Dfirestore.emulator.jar=/path/cloud-firestore-emulator-vX.jar} (started here). Neither: the Firestore tests
 * are skipped, and say so. Each store gets its own project, so tests never see each other's documents.
 */
public final class FirestoreEmulator {

    private static String host;
    private static boolean tried;

    private FirestoreEmulator() {}

    /** Settings for a Spring test in Firestore mode. The emulator host only lets Casita's own client build; tests replace it. */
    public static String[] properties() {
        return new String[] {"casita.storage.type=firestore", "casita.storage.firestore.project-id=" + newProject(),
                "casita.storage.firestore.emulator-host=" + host()};
    }

    /** With {@code -Dfirestore.emulator.required=true} (CI), a missing emulator fails the build instead of skipping. */
    public static boolean available() {
        if (host() == null && Boolean.getBoolean("firestore.emulator.required")) {
            throw new IllegalStateException("firestore.emulator.required is set, but no emulator: set -Dfirestore.emulator.jar or -Dfirestore.emulator.host");
        }
        return host() != null;
    }

    public static String newProject() {
        return "casita-test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A client for the emulator, through the faulty interceptor, in its own project, with the given timeout. */
    public static Firestore client(String project, FaultyFirestore faults, Duration timeout) {
        return FirestoreOptions.newBuilder()
                .setProjectId(project)
                .setDatabaseId(FirestoreCollections.DATABASE_ID)
                .setRetrySettings(StorageConfig.retrySettings(timeout))
                .setChannelProvider(InstantiatingGrpcChannelProvider.newBuilder().setEndpoint(host())
                        .setChannelConfigurator(ManagedChannelBuilder::usePlaintext)
                        .setInterceptorProvider(() -> List.of(faults.interceptor()))
                        .build())
                .setCredentialsProvider(FixedCredentialsProvider.create(new FirestoreOptions.EmulatorCredentials()))
                .build().getService();
    }

    /**
     * What provisioning does once (docs/FIRESTORE.md): the schema marker. Casita itself never writes it. Written through
     * a setup client of its own, with a generous timeout and no faults: the first write to a just-started emulator can
     * take longer than the short timeouts the tests give their own clients, and setup isn't what they test.
     */
    public static void provision(Firestore db) {
        try (Firestore setup = client(db.getOptions().getProjectId(), new FaultyFirestore(), Duration.ofSeconds(30))) {
            FirestoreCollections.standard().schema(setup).set(Map.of("schemaVersion", FirestoreCollections.SCHEMA_VERSION)).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("couldn't provision the emulator project", e);
        }
    }

    public static synchronized String host() {
        if (tried) return host;
        tried = true;
        String given = System.getProperty("firestore.emulator.host");
        if (given != null && !given.isBlank()) return host = given;
        String jar = System.getProperty("firestore.emulator.jar");
        if (jar == null || jar.isBlank() || !Files.isRegularFile(Path.of(jar))) return null;
        try {
            int port;
            try (ServerSocket s = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                port = s.getLocalPort();
            }
            Process p = new ProcessBuilder("java", "-Xmx512m", "-jar", jar, "--host=127.0.0.1", "--port=" + port)
                    .redirectErrorStream(true).start();
            Runtime.getRuntime().addShutdownHook(new Thread(p::destroy));
            Thread drain = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    while (r.readLine() != null) { /* the emulator's log: not needed */ }
                } catch (IOException ignored) {
                    // the emulator stopped
                }
            }, "firestore-emulator-log");
            drain.setDaemon(true);
            drain.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < deadline) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                    return host = "127.0.0.1:" + port;
                } catch (IOException notYet) {
                    if (!p.isAlive()) return null;
                    Thread.sleep(200);
                }
            }
            p.destroy();
            return null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    /** Casita's Firestore, for a Spring test: the emulator, through a faulty interceptor the test can control. */
    @TestConfiguration
    public static class Config {
        @Bean
        FaultyFirestore faultyFirestore() {
            return new FaultyFirestore();
        }

        @Bean(destroyMethod = "close")
        @Primary
        Firestore emulatorFirestore(FaultyFirestore faults, StorageProperties storage) {
            Firestore db = client(storage.firestore().projectId(), faults, storage.firestore().timeout());
            provision(db);
            return db;
        }
    }
}
