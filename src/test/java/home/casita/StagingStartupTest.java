package home.casita;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import home.casita.garage.DoorControl;
import home.casita.garage.DoorRoster;
import home.casita.garage.Garage;

/**
 * The whole app started as staging, through the registered StagingGuard. Settings are given as command-line
 * arguments, the highest-precedence source, standing in for the environment variables a VM would set: they
 * override application.yml exactly as CASITA_DOOR=meross would.
 */
class StagingStartupTest {

    private static final List<String> COMMON = List.of("--server.port=0", "--casita.security.data-dir=target/test-data/staging");

    private static ConfigurableApplicationContext start(String... args) {
        List<String> all = new ArrayList<>(COMMON);
        all.addAll(List.of(args));
        return SpringApplication.run(CasitaApplication.class, all.toArray(String[]::new));
    }

    @Test
    void stagingStartsWithFordAndToyotaBothSimulatedAndNoMeross() {
        // A developer's own Meross credentials would (rightly) stop staging; CI has none.
        assumeThat(Files.exists(StagingGuard.MEROSS_LOCAL_FILE)).as("no config/meross.local.json here").isFalse();
        assumeThat(System.getenv("MEROSS_EMAIL")).as("no MEROSS_EMAIL here").isNull();
        try (ConfigurableApplicationContext app = start("--casita.deployment=staging")) {
            DoorRoster roster = app.getBean(DoorRoster.class);
            assertThat(roster.doors()).extracting(DoorRoster.Door::name).containsExactly("Ford", "Toyota");
            assertThat(roster.doors()).extracting(DoorRoster.Door::control).containsOnly(DoorControl.SIMULATED);
            assertThat(app.getBean(Garage.class).doors()).hasSize(2);
            // Nothing from the Meross side exists: no credentials resolved, no session, no cloud client.
            assertThat(app.getBeanNamesForType(home.casita.meross.MerossProperties.class)).as("bound, but empty").hasSize(1);
            assertThat(app.containsBean("merossLocalCredentials")).isFalse();
            assertThat(app.containsBean("merossSession")).isFalse();
            assertThat(app.containsBean("merossCloudApi")).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"meross", "MEROSS", "Simulated"})
    void stagingWithAnyOtherDoorDoesNotStart(String door) {
        assertThatThrownBy(() -> start("--casita.deployment=staging", "--casita.door=" + door,
                "--casita.meross.email=placeholder@example.com", "--casita.meross.password=placeholder"))
                .hasMessageContaining("Staging guard").hasMessageContaining("casita.door")
                .hasMessageContaining("casita.meross.email");
    }

    @ParameterizedTest
    @ValueSource(strings = {"live", "dry-run"})
    void stagingWithCommandsOnDoesNotStart(String mode) {
        assertThatThrownBy(() -> start("--casita.deployment=staging", "--casita.meross.command-mode=" + mode))
                .hasMessageContaining("Staging guard").hasMessageContaining("command-mode");
    }

    @Test
    void stagingWithMerossCredentialsDoesNotStart() {
        assertThatThrownBy(() -> start("--casita.deployment=staging", "--casita.meross.password=placeholder"))
                .hasMessageContaining("Staging guard").hasMessageContaining("casita.meross.password");
    }

    @Test
    void anUnknownDeploymentDoesNotStart() {
        assertThatThrownBy(() -> start("--casita.deployment=production"))
                .hasMessageContaining("Staging guard").hasMessageContaining("casita.deployment");
    }
}
