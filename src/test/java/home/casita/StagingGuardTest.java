package home.casita;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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

    /** Staging as the VM sets it up (deploy/watson/casita.env.example, with a tailnet filled in). */
    static Map<String, String> stagingSettings() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("casita.deployment", "staging");
        s.put("casita.door", "simulated");
        s.put("casita.meross.command-mode", "off");
        s.put("casita.security.rp-id", "watson.example-tailnet.ts.net");
        s.put("casita.security.allowed-origins", "https://watson.example-tailnet.ts.net");
        s.put("casita.security.family", "shir,hubby");
        s.put("casita.security.device-identity", "tailscale");
        s.put("casita.security.data-dir", "/var/lib/casita");
        s.put("casita.storage.type", "firestore");
        s.put("server.servlet.session.cookie.secure", "true");
        return s;
    }

    /** Staging as it should be, without the named settings. */
    private static MockEnvironment staging(String... without) {
        MockEnvironment env = new MockEnvironment();
        stagingSettings().forEach((k, v) -> {
            if (!List.of(without).contains(k)) env.setProperty(k, v);
        });
        return env;
    }

    private static List<String> problems(MockEnvironment env) {
        return StagingGuard.problems(env, false, false, Map.of());
    }

    @Test
    void stagingWithThePracticeDoorsCommandsOffAndWatsonSignInStarts() {
        assertThat(problems(staging())).isEmpty();
        assertThat(StagingGuard.problems(staging(), true, false, Map.of())).as("on Compute Engine too").isEmpty();
    }

    @Test
    void localDevelopmentIsUnchanged() {
        // No casita.deployment and not on Compute Engine: the guard has no opinion, whatever the settings.
        MockEnvironment local = new MockEnvironment()
                .withProperty("casita.door", "meross")
                .withProperty("casita.meross.command-mode", "live")
                .withProperty("casita.meross.email", "placeholder@example.com")
                .withProperty("casita.security.rp-id", "localhost")
                .withProperty("casita.storage.type", "file");
        local.setActiveProfiles("dev");
        assertThat(StagingGuard.problems(local, false, true, Map.of("MEROSS_EMAIL", "a@example.com"))).isEmpty();
        assertThat(problems(local.withProperty("casita.deployment", "local"))).isEmpty();
    }

    /* ---------- the garage ---------- */

    @ParameterizedTest
    @ValueSource(strings = {"meross", "MEROSS", "Simulated", "SIMULATED", " simulated", "simulated ", "", "fake"})
    void stagingRefusesAnyDoorButExactlySimulated(String door) {
        assertThat(problems(staging().withProperty("casita.door", door))).singleElement().asString().contains("casita.door");
    }

    @Test
    void stagingRefusesAMissingDoorSetting() {
        assertThat(problems(staging("casita.door"))).singleElement().asString().contains("casita.door", "not set");
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
        assertThat(problems(staging("casita.meross.command-mode"))).singleElement().asString().contains("command-mode", "not set");
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

    /* ---------- sign-in and storage ---------- */

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "casita.example-tailnet.ts.net", "watson.<tailnet>.ts.net", "watson.<verified-tailnet>.ts.net",
            "watson.ts.net", "watson.example-tailnet.ts.net.", "WATSON.example-tailnet.ts.net", "watson.example.com", ""})
    void stagingRefusesAnRpIdThatIsntWatsonOnARealTailnet(String rpId) {
        MockEnvironment env = staging().withProperty("casita.security.rp-id", rpId)
                .withProperty("casita.security.allowed-origins", "https://" + rpId);
        assertThat(problems(env)).singleElement().asString().contains("casita.security.rp-id");
    }

    @Test
    void stagingRefusesAMissingRpId() {
        assertThat(problems(staging("casita.security.rp-id"))).anySatisfy(p -> assertThat(p).contains("rp-id", "not set"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://watson.other-tailnet.ts.net", "http://watson.example-tailnet.ts.net",
            "https://watson.example-tailnet.ts.net:8443", "https://watson.example-tailnet.ts.net,https://evil.example-tailnet.ts.net",
            "https://watson.example-tailnet.ts.net/", "http://localhost:8080"})
    void stagingRefusesAnyOriginButExactlyTheWatsonOne(String origins) {
        assertThat(problems(staging().withProperty("casita.security.allowed-origins", origins))).singleElement().asString()
                .contains("allowed-origins");
    }

    @Test
    void stagingRefusesMissingOrigins() {
        assertThat(problems(staging("casita.security.allowed-origins"))).singleElement().asString().contains("allowed-origins");
    }

    @Test
    void stagingRefusesAnEmptyFamily() {
        assertThat(problems(staging("casita.security.family"))).singleElement().asString().contains("casita.security.family");
        assertThat(problems(staging().withProperty("casita.security.family", ""))).singleElement().asString().contains("family");
    }

    @ParameterizedTest
    @ValueSource(strings = {"development", "DEVELOPMENT", ""})
    void stagingRefusesAnyDeviceIdentityButTailscale(String identity) {
        assertThat(problems(staging().withProperty("casita.security.device-identity", identity))).singleElement().asString()
                .contains("device-identity");
        assertThat(problems(staging("casita.security.device-identity"))).singleElement().asString().contains("device-identity");
    }

    @Test
    void stagingRefusesARelativeOrMissingDataDir() {
        assertThat(problems(staging().withProperty("casita.security.data-dir", "data"))).singleElement().asString().contains("data-dir");
        assertThat(problems(staging("casita.security.data-dir"))).singleElement().asString().contains("data-dir");
    }

    @ParameterizedTest
    @ValueSource(strings = {"file", "dynamodb", "FIRESTORE", ""})
    void stagingRefusesAnyStorageButFirestore(String type) {
        assertThat(problems(staging().withProperty("casita.storage.type", type))).singleElement().asString().contains("casita.storage.type");
        assertThat(problems(staging("casita.storage.type"))).singleElement().asString().contains("casita.storage.type");
    }

    @Test
    void stagingRefusesAFirestoreEmulator() {
        assertThat(problems(staging().withProperty("casita.storage.firestore.emulator-host", "127.0.0.1:18085"))).singleElement()
                .asString().contains("emulator");
        assertThat(StagingGuard.problems(staging(), false, false, Map.of("FIRESTORE_EMULATOR_HOST", "127.0.0.1:18085")))
                .singleElement().asString().contains("emulator");
    }

    @Test
    void stagingRefusesCookiesThatArentSecure() {
        assertThat(problems(staging().withProperty("server.servlet.session.cookie.secure", "false"))).singleElement().asString()
                .contains("Secure");
        assertThat(problems(staging("server.servlet.session.cookie.secure"))).singleElement().asString().contains("Secure");
    }

    @Test
    void stagingRefusesTheDevProfile() {
        MockEnvironment env = staging();
        env.setActiveProfiles("dev");
        assertThat(problems(env)).singleElement().asString().contains("dev profile");
    }

    /* ---------- where it runs ---------- */

    @Test
    void everyProblemIsReportedAtOnce() {
        MockEnvironment env = staging().withProperty("casita.door", "meross").withProperty("casita.meross.command-mode", "live")
                .withProperty("casita.meross.email", "placeholder@example.com").withProperty("casita.storage.type", "file");
        assertThat(StagingGuard.problems(env, true, true, Map.of())).hasSize(5);
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
        assertThat(StagingGuard.problems(staging("casita.deployment"), true, false, Map.of())).singleElement().asString()
                .contains("not set");
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
        assertThatThrownBy(() -> guard.postProcessEnvironment(staging("casita.deployment"), new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Compute Engine").hasMessageContaining("docs/STAGING_GUARD.md");

        guard.postProcessEnvironment(staging(), new SpringApplication());
    }
}
