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

    /** Staging as the VM sets it up (deploy/watson/casita.env.example, with the public host filled in). */
    static Map<String, String> stagingSettings() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("casita.deployment", "staging");
        s.put("casita.door", "simulated");
        s.put("casita.meross.command-mode", "off");
        s.put("casita.security.rp-id", "casita.example.org");
        s.put("casita.security.allowed-origins", "https://casita.example.org");
        s.put("casita.security.family", "shir,hubby");
        s.put("casita.security.device-identity", "browser");
        s.put("server.address", "127.0.0.1");
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
    @ValueSource(strings = {"localhost", "<PUBLIC_HOST>", "watson.<tailnet>.ts.net", "casita.example.org.", "Casita.Example.org",
            "127.0.0.1", "casita", "casita.example.123", "-casita.example.org", "casita_x.example.org", ""})
    void stagingRefusesAnRpIdThatIsntAPublicHostName(String rpId) {
        MockEnvironment env = staging().withProperty("casita.security.rp-id", rpId)
                .withProperty("casita.security.allowed-origins", "https://" + rpId);
        assertThat(problems(env)).singleElement().asString().contains("casita.security.rp-id");
    }

    @Test
    void stagingRefusesAMissingRpId() {
        assertThat(problems(staging("casita.security.rp-id"))).anySatisfy(p -> assertThat(p).contains("rp-id", "not set"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://other.example.org", "http://casita.example.org",
            "https://casita.example.org:8443", "https://casita.example.org,https://evil.casita.example.org",
            "https://casita.example.org/", "http://localhost:8080"})
    void stagingRefusesAnyOriginButExactlyThePublicOne(String origins) {
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
    @ValueSource(strings = {"development", "DEVELOPMENT", "tailscale", ""})
    void stagingRefusesAnyDeviceIdentityButBrowser(String identity) {
        assertThat(problems(staging().withProperty("casita.security.device-identity", identity))).singleElement().asString()
                .contains("device-identity");
        assertThat(problems(staging("casita.security.device-identity"))).singleElement().asString().contains("device-identity");
    }

    @Test
    void theWatsonHostNameIsAccepted() {
        assertThat(problems(staging().withProperty("casita.security.rp-id", "watsonbental.duckdns.org")
                .withProperty("casita.security.allowed-origins", "https://watsonbental.duckdns.org"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "10.80.0.2", "::", ""})
    void stagingRefusesListeningAnywhereButLoopback(String address) {
        assertThat(problems(staging().withProperty("server.address", address))).singleElement().asString().contains("server.address");
        assertThat(problems(staging("server.address"))).singleElement().asString().contains("server.address");
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
    @ValueSource(strings = {"prod", "Production", "PRODUCTION", "stage", "Staging", "STAGING", "dev"})
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

    /* ---------- production: the real doors, Ford on channel 1 and Toyota on channel 2 ---------- */

    /** Production as deploy/watson/LIVE_DOORS.md sets it up, in the given command mode, without the named settings. */
    private static MockEnvironment production(String commandMode, String... without) {
        Map<String, String> s = stagingSettings();
        s.put("casita.deployment", "production");
        s.put("casita.door", "meross");
        s.put("casita.meross.command-mode", commandMode);
        s.put("casita.meross.doors.1", "Ford");
        s.put("casita.meross.doors.2", "Toyota");
        s.put("casita.meross.command-channels", "1,2");
        s.put("casita.meross.email", "placeholder@example.com");
        s.put("casita.meross.password", "placeholder");
        s.put("casita.garage.travel-times.ford", "20s");
        s.put("casita.garage.sensor-lags.ford", "5s");
        s.put("casita.garage.travel-times.toyota", "18s");
        s.put("casita.garage.sensor-lags.toyota", "25s");
        MockEnvironment env = new MockEnvironment();
        s.forEach((k, v) -> {
            if (!List.of(without).contains(k)) env.setProperty(k, v);
        });
        return env;
    }

    private static List<String> onWatson(MockEnvironment env) {
        return StagingGuard.problems(env, true, false, Map.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"off", "dry-run", "live"})
    void productionWithBothRealDoorsStartsInEachCommandMode(String mode) {
        assertThat(onWatson(production(mode))).isEmpty();
        assertThat(onWatson(production(mode).withProperty("casita.meross.transport", "cloud"))).isEmpty();
        assertThat(onWatson(production(mode).withProperty("casita.meross.transport", "local")
                .withProperty("casita.meross.local-url", "http://192.168.1.50"))).isEmpty();
    }

    @Test
    void productionNeedsTheRealDoorsExactly() {
        assertThat(onWatson(production("off").withProperty("casita.door", "simulated"))).singleElement().asString().contains("casita.door=meross");
        assertThat(onWatson(production("off").withProperty("casita.meross.command-mode", "LIVE"))).singleElement().asString().contains("command-mode");
        assertThat(onWatson(production("off").withProperty("casita.meross.doors.3", "Spare"))).singleElement().asString().contains("channel 3");
        assertThat(onWatson(production("off").withProperty("casita.meross.doors.2", "Ford"))).singleElement().asString().contains("2: Toyota");
        assertThat(onWatson(production("off", "casita.meross.doors.2"))).singleElement().asString().contains("2: Toyota");
        assertThat(onWatson(production("off").withProperty("casita.meross.command-channels", "1,3"))).isNotEmpty()
                .anySatisfy(p -> assertThat(p).contains("command-channels"));
    }

    @Test
    void productionTakesTheAccountOnlyFromItsOwnSettings() {
        assertThat(onWatson(production("off", "casita.meross.password"))).singleElement().asString().contains("casita.meross.password");
        assertThat(StagingGuard.problems(production("off"), true, false, Map.of("MEROSS_EMAIL", "x@example.com")))
                .singleElement().asString().contains("MEROSS_EMAIL");
        assertThat(StagingGuard.problems(production("off"), true, true, Map.of())).singleElement().asString().contains("meross.local.json");
    }

    @Test
    void productionLiveNeedsEachCommandedDoorsMeasuredTimings() {
        assertThat(onWatson(production("live", "casita.garage.sensor-lags.ford"))).singleElement().asString()
                .contains("Ford").contains("sensor-lags");
        assertThat(onWatson(production("live", "casita.garage.travel-times.toyota"))).singleElement().asString()
                .contains("Toyota").contains("travel-times");
        // Only the commanded doors: Toyota may stay read-only while Ford is tested.
        assertThat(onWatson(production("live", "casita.garage.travel-times.toyota")
                .withProperty("casita.meross.command-channels", "1"))).isEmpty();
        assertThat(onWatson(production("dry-run", "casita.garage.sensor-lags.ford", "casita.garage.sensor-lags.toyota"))).isEmpty();
    }

    @Test
    void productionsLocalTransportReachesOnlyAPrivateAddress() {
        for (String bad : List.of("https://192.168.1.50", "http://8.8.8.8", "http://opener.lan", "http://192.168.1.50/config")) {
            assertThat(onWatson(production("off").withProperty("casita.meross.transport", "local")
                    .withProperty("casita.meross.local-url", bad))).as(bad).singleElement().asString().contains("local-url");
        }
        assertThat(onWatson(production("off").withProperty("casita.meross.transport", "local"))).singleElement().asString()
                .contains("local-url");
        assertThat(onWatson(production("off").withProperty("casita.meross.transport", "lan"))).singleElement().asString()
                .contains("cloud or local");
    }

    @Test
    void productionKeepsWatsonSignInAndFirestore() {
        assertThat(onWatson(production("off").withProperty("casita.storage.type", "file"))).isNotEmpty();
        assertThat(onWatson(production("off").withProperty("casita.security.rp-id", "localhost"))).isNotEmpty()
                .allSatisfy(p -> assertThat(p).startsWith("Production"));
    }
}
