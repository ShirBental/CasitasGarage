package home.casita;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.impl.NoOpLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/** The staging guard's rules, one configuration at a time. StagingStartupTest runs them through a real startup. */
class StagingGuardTest {

    /** Staging as it should be: what application.yml gives, plus casita.deployment=staging. */
    private static MockEnvironment staging() {
        return new MockEnvironment()
                .withProperty("casita.deployment", "staging")
                .withProperty("casita.door", "simulated")
                .withProperty("casita.meross.command-mode", "off");
    }

    private static List<String> problems(MockEnvironment env) {
        return StagingGuard.problems(env, false, false, Map.of());
    }

    @Test
    void stagingWithThePracticeDoorsAndCommandsOffStarts() {
        assertThat(problems(staging())).isEmpty();
        assertThat(StagingGuard.problems(staging(), true, false, Map.of())).as("on Compute Engine too").isEmpty();
    }

    @Test
    void localDevelopmentIsUnchanged() {
        // No casita.deployment and not on Compute Engine: the guard has no opinion, whatever the door settings.
        MockEnvironment local = new MockEnvironment()
                .withProperty("casita.door", "meross")
                .withProperty("casita.meross.command-mode", "live")
                .withProperty("casita.meross.email", "placeholder@example.com");
        assertThat(StagingGuard.problems(local, false, true, Map.of("MEROSS_EMAIL", "a@example.com"))).isEmpty();
        assertThat(problems(local.withProperty("casita.deployment", "local"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"meross", "MEROSS", "Simulated", "SIMULATED", " simulated", "simulated ", "", "fake"})
    void stagingRefusesAnyDoorButExactlySimulated(String door) {
        assertThat(problems(staging().withProperty("casita.door", door))).singleElement().asString().contains("casita.door");
    }

    @Test
    void stagingRefusesAMissingDoorSetting() {
        MockEnvironment env = new MockEnvironment().withProperty("casita.deployment", "staging")
                .withProperty("casita.meross.command-mode", "off");
        assertThat(problems(env)).singleElement().asString().contains("casita.door", "not set");
    }

    @ParameterizedTest
    // "false" is what YAML makes of a bare off: ambiguous, so refused.
    @ValueSource(strings = {"live", "LIVE", "dry-run", "DRY_RUN", "OFF", "false", ""})
    void stagingRefusesAnyCommandModeButExactlyOff(String mode) {
        assertThat(problems(staging().withProperty("casita.meross.command-mode", mode))).singleElement().asString()
                .contains("casita.meross.command-mode");
    }

    @Test
    void stagingRefusesAMissingCommandMode() {
        MockEnvironment env = new MockEnvironment().withProperty("casita.deployment", "staging")
                .withProperty("casita.door", "simulated");
        assertThat(problems(env)).singleElement().asString().contains("command-mode", "not set");
    }

    @Test
    void stagingRefusesMerossCredentialsWhereverTheyAre() {
        assertThat(problems(staging().withProperty("casita.meross.email", "placeholder@example.com"))).singleElement().asString()
                .contains("casita.meross.email");
        assertThat(problems(staging().withProperty("casita.meross.password", "placeholder"))).singleElement().asString()
                .contains("casita.meross.password");
        assertThat(StagingGuard.problems(staging(), false, false, Map.of("MEROSS_EMAIL", "a@example.com"))).singleElement().asString()
                .contains("MEROSS_EMAIL");
        assertThat(StagingGuard.problems(staging(), false, false, Map.of("MEROSS_PASSWORD", "x"))).singleElement().asString()
                .contains("MEROSS_PASSWORD");
        assertThat(StagingGuard.problems(staging(), false, true, Map.of())).singleElement().asString()
                .contains("meross.local.json");
        // Blank values are absent.
        assertThat(StagingGuard.problems(staging().withProperty("casita.meross.email", " "), false, false, Map.of("MEROSS_EMAIL", "")))
                .isEmpty();
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        MockEnvironment env = staging().withProperty("casita.door", "meross").withProperty("casita.meross.command-mode", "live")
                .withProperty("casita.meross.email", "placeholder@example.com");
        assertThat(StagingGuard.problems(env, true, true, Map.of())).hasSize(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "local", "  "})
    void onComputeEngineOnlyStagingMayStart(String deployment) {
        // An environment variable can say "local", but it can't change the hardware.
        MockEnvironment env = staging().withProperty("casita.deployment", deployment);
        assertThat(StagingGuard.problems(env, true, false, Map.of())).singleElement().asString().contains("Compute Engine");
    }

    @Test
    void onComputeEngineAMissingDeploymentIsRefused() {
        MockEnvironment env = new MockEnvironment().withProperty("casita.door", "simulated")
                .withProperty("casita.meross.command-mode", "off");
        assertThat(StagingGuard.problems(env, true, false, Map.of())).singleElement().asString().contains("not set");
    }

    @ParameterizedTest
    @ValueSource(strings = {"production", "prod", "stage", "Staging", "STAGING", "dev"})
    void anUnknownDeploymentIsRefusedAnywhere(String deployment) {
        assertThat(problems(staging().withProperty("casita.deployment", deployment))).singleElement().asString()
                .contains("casita.deployment");
        assertThat(StagingGuard.problems(staging().withProperty("casita.deployment", deployment), true, false, Map.of()))
                .singleElement().asString().contains("casita.deployment");
    }

    @Test
    void computeEngineIsRecognisedByItsHardware(@TempDir Path dir) throws Exception {
        Path name = dir.resolve("product_name");
        assertThat(StagingGuard.onComputeEngine(name)).as("no DMI (macOS, most containers)").isFalse();
        Files.writeString(name, "Google Compute Engine\n");
        assertThat(StagingGuard.onComputeEngine(name)).isTrue();
        Files.writeString(name, "Virtual Machine\n");
        assertThat(StagingGuard.onComputeEngine(name)).as("e.g. a GitHub Actions runner").isFalse();
        Path unreadable = Files.createDirectory(dir.resolve("unreadable"));
        assertThat(StagingGuard.onComputeEngine(unreadable)).as("exists but can't be read: fail closed").isTrue();
    }

    @Test
    void aRefusalStopsStartupBeforeAnyBeanWithEveryReason(@TempDir Path dir) throws Exception {
        Path name = Files.writeString(dir.resolve("product_name"), "Google Compute Engine\n");
        StagingGuard guard = new StagingGuard(new NoOpLog(), name, dir.resolve("meross.local.json"));
        MockEnvironment env = new MockEnvironment().withProperty("casita.door", "simulated")
                .withProperty("casita.meross.command-mode", "off");
        assertThatThrownBy(() -> guard.postProcessEnvironment(env, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Compute Engine").hasMessageContaining("docs/STAGING_GUARD.md");

        guard.postProcessEnvironment(env.withProperty("casita.deployment", "staging"), new SpringApplication());
    }
}
