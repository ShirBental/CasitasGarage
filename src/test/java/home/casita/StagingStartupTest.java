package home.casita;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.google.cloud.firestore.Firestore;

import home.casita.garage.DoorControl;
import home.casita.garage.DoorRoster;
import home.casita.garage.Garage;
import home.casita.security.CasitaSecurityProperties;
import home.casita.security.store.FaultyFirestore;
import home.casita.security.store.FirestoreEmulator;
import home.casita.security.store.SecurityStore;

/**
 * The whole app started as staging, through the registered StagingGuard. Settings are given as command-line
 * arguments, the highest-precedence source, standing in for the environment variables the VM's unit sets: they
 * override application.yml exactly as CASITA_DOOR=meross would.
 */
class StagingStartupTest {

    /** Staging as the VM sets it up, as command-line arguments, plus a random port and a test data directory. */
    private static List<String> staging(String... overrides) {
        Map<String, String> settings = StagingGuardTest.stagingSettings();
        settings.put("casita.security.data-dir", Path.of("target/test-data/staging").toAbsolutePath().toString());
        settings.put("server.port", "0");
        // On the VM the project comes from the metadata server; here, Casita's own client needs one to build.
        settings.put("casita.storage.firestore.project-id", "casita-staging-test");
        for (String o : overrides) {
            String[] kv = o.split("=", 2);
            settings.put(kv[0], kv[1]);
        }
        List<String> args = new ArrayList<>();
        settings.forEach((k, v) -> args.add("--" + k + "=" + v));
        return args;
    }

    private static ConfigurableApplicationContext start(List<String> args, Class<?>... extraSources) {
        List<Class<?>> sources = new ArrayList<>(List.of(CasitaApplication.class));
        sources.addAll(List.of(extraSources));
        return new SpringApplication(sources.toArray(Class<?>[]::new)).run(args.toArray(String[]::new));
    }

    /**
     * Firestore for the positive startup: the pinned emulator, as a primary bean, so staging's own settings name no
     * emulator (the guard refuses one). A plain class, not a configuration, so component scanning never picks it up.
     */
    static class EmulatorFirestore {
        @Bean(destroyMethod = "close")
        @Primary
        Firestore stagingTestFirestore() {
            Firestore db = FirestoreEmulator.client(FirestoreEmulator.newProject(), new FaultyFirestore(), Duration.ofSeconds(30));
            FirestoreEmulator.provision(db);
            return db;
        }
    }

    @Test
    void stagingStartsWithFordAndToyotaBothSimulatedWatsonSignInAndNoMeross() {
        // A developer's own Meross credentials would (rightly) stop staging; CI has none.
        assumeThat(Files.exists(StagingGuard.MEROSS_LOCAL_FILE)).as("no config/meross.local.json here").isFalse();
        assumeThat(System.getenv("MEROSS_EMAIL")).as("no MEROSS_EMAIL here").isNull();
        assumeThat(FirestoreEmulator.available()).as("the Firestore emulator (required in CI)").isTrue();
        try (ConfigurableApplicationContext app = start(staging(), EmulatorFirestore.class)) {
            DoorRoster roster = app.getBean(DoorRoster.class);
            assertThat(roster.doors()).extracting(DoorRoster.Door::name).containsExactly("Ford", "Toyota");
            assertThat(roster.doors()).extracting(DoorRoster.Door::control).containsOnly(DoorControl.SIMULATED);
            assertThat(app.getBean(Garage.class).doors()).hasSize(2);
            // Nothing from the Meross side exists: no credentials resolved, no session, no cloud client.
            assertThat(app.containsBean("merossLocalCredentials")).isFalse();
            assertThat(app.containsBean("merossSession")).isFalse();
            assertThat(app.containsBean("merossCloudApi")).isFalse();
            // Sign-in is bound to watson on the tailnet, and the security store is Firestore with the family seeded.
            CasitaSecurityProperties security = app.getBean(CasitaSecurityProperties.class);
            assertThat(security.rpId()).isEqualTo("watson.example-tailnet.ts.net");
            assertThat(security.allowedOrigins()).containsExactly("https://watson.example-tailnet.ts.net");
            assertThat(security.deviceIdentity()).isEqualTo(CasitaSecurityProperties.DeviceIdentityMode.TAILSCALE);
            assertThat(app.getBean(SecurityStore.class).getClass().getSimpleName()).isEqualTo("FirestoreSecurityStore");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"meross", "MEROSS", "Simulated"})
    void stagingWithAnyOtherDoorDoesNotStart(String door) {
        assertThatThrownBy(() -> start(staging("casita.door=" + door, "casita.meross.email=placeholder@example.com",
                "casita.meross.password=placeholder")))
                .hasMessageContaining("Staging guard").hasMessageContaining("casita.door")
                .hasMessageContaining("casita.meross.email");
    }

    @ParameterizedTest
    @ValueSource(strings = {"live", "dry-run"})
    void stagingWithCommandsOnDoesNotStart(String mode) {
        assertThatThrownBy(() -> start(staging("casita.meross.command-mode=" + mode)))
                .hasMessageContaining("Staging guard").hasMessageContaining("command-mode");
    }

    @Test
    void stagingWithMerossCredentialsDoesNotStart() {
        assertThatThrownBy(() -> start(staging("casita.meross.password=placeholder")))
                .hasMessageContaining("Staging guard").hasMessageContaining("casita.meross.password");
    }

    @Test
    void stagingWithThePlaceholderTailnetDoesNotStart() {
        assertThatThrownBy(() -> start(staging("casita.security.rp-id=watson.<verified-tailnet>.ts.net",
                "casita.security.allowed-origins=https://watson.<verified-tailnet>.ts.net")))
                .hasMessageContaining("Staging guard").hasMessageContaining("rp-id");
    }

    @Test
    void stagingWithTheLocalDefaultsDoesNotStart() {
        // Only casita.deployment=staging: application.yml's localhost sign-in and file store are refused.
        assertThatThrownBy(() -> start(List.of("--casita.deployment=staging", "--server.port=0")))
                .hasMessageContaining("Staging guard").hasMessageContaining("rp-id").hasMessageContaining("casita.storage.type")
                .hasMessageContaining("family").hasMessageContaining("data-dir");
    }

    @Test
    void stagingWithAFileStoreOrAnEmulatorDoesNotStart() {
        assertThatThrownBy(() -> start(staging("casita.storage.type=file")))
                .hasMessageContaining("Staging guard").hasMessageContaining("casita.storage.type");
        assertThatThrownBy(() -> start(staging("casita.storage.firestore.emulator-host=127.0.0.1:18085")))
                .hasMessageContaining("Staging guard").hasMessageContaining("emulator");
    }

    @Test
    void stagingWithTheDevProfileDoesNotStart() {
        assertThatThrownBy(() -> start(staging("spring.profiles.active=dev")))
                .hasMessageContaining("Staging guard").hasMessageContaining("dev profile");
        // On its own, the dev profile also turns Secure cookies off.
        assertThatThrownBy(() -> start(staging("spring.profiles.active=dev", "server.servlet.session.cookie.secure=false")))
                .hasMessageContaining("dev profile").hasMessageContaining("Secure");
    }

    @Test
    void anUnknownDeploymentDoesNotStart() {
        assertThatThrownBy(() -> start(staging("casita.deployment=production")))
                .hasMessageContaining("Staging guard").hasMessageContaining("casita.deployment");
    }
}
