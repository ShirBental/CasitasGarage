package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Loading local Meross credentials: the file, the environment's precedence, and clear failures. */
class MerossLocalCredentialsTest {

    private static final String ENV = "CASITA_MEROSS_EMAIL and CASITA_MEROSS_PASSWORD";

    @TempDir
    Path dir;

    private Path file(String json) throws IOException {
        Path path = dir.resolve("meross.local.json");
        Files.writeString(path, json);
        return path;
    }

    @Test
    void readsEverythingFromTheFile() throws IOException {
        Path file = file("{\"email\": \"file@example.com\", \"password\": \"file-pass\", \"deviceUuid\": \"uuid-1\"}");

        MerossLocalCredentials creds = MerossLocalCredentials.resolve(null, null, null, file, ENV);

        assertThat(creds).isEqualTo(new MerossLocalCredentials("file@example.com", "file-pass", "uuid-1"));
    }

    @Test
    void environmentWinsFieldByField() throws IOException {
        Path file = file("{\"email\": \"file@example.com\", \"password\": \"file-pass\", \"deviceUuid\": \"file-uuid\"}");

        MerossLocalCredentials creds = MerossLocalCredentials.resolve("env@example.com", null, "env-uuid", file, ENV);

        assertThat(creds).isEqualTo(new MerossLocalCredentials("env@example.com", "file-pass", "env-uuid"));
    }

    @Test
    void environmentAloneIsEnoughWithoutAFile() {
        MerossLocalCredentials creds = MerossLocalCredentials.resolve("env@example.com", "env-pass", null, dir.resolve("absent.json"), ENV);

        assertThat(creds).isEqualTo(new MerossLocalCredentials("env@example.com", "env-pass", null));
    }

    @Test
    void blankValuesCountAsUnsetAndPasswordsAreKeptExactly() throws IOException {
        Path file = file("{\"email\": \"file@example.com\", \"password\": \" spaced pass \", \"deviceUuid\": \"\"}");

        MerossLocalCredentials creds = MerossLocalCredentials.resolve("  ", "", null, file, ENV);

        assertThat(creds.email()).isEqualTo("file@example.com");
        assertThat(creds.password()).isEqualTo(" spaced pass ");
        assertThat(creds.deviceUuid()).isNull();
    }

    @Test
    void missingEverywhereFailsNamingTheFieldsAndBothSources() {
        Path absent = dir.resolve("absent.json");

        assertThatThrownBy(() -> MerossLocalCredentials.resolve(null, null, null, absent, ENV))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing: email, password")
                .hasMessageContaining("CASITA_MEROSS_EMAIL and CASITA_MEROSS_PASSWORD")
                .hasMessageContaining(absent.toString())
                .hasMessageContaining("docs/MEROSS.md");
    }

    @Test
    void anIncompleteFileFailsNamingOnlyWhatIsMissingAndNoValues() throws IOException {
        Path file = file("{\"email\": \"secret-user@example.com\"}");

        assertThatThrownBy(() -> MerossLocalCredentials.resolve(null, null, null, file, ENV))
                .hasMessageContaining("missing: password")
                .hasMessageNotContaining("missing: email")
                .hasMessageNotContaining("secret-user");
    }

    @Test
    void malformedJsonFailsWithAPositionAndWithoutQuotingTheFile() throws IOException {
        Path file = file("{\"email\": \"a@example.com\", \"password\": \"hunter2-secret\" \"deviceUuid\": \"x\"}");

        assertThatThrownBy(() -> MerossLocalCredentials.resolve(null, null, null, file, ENV))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not a valid JSON object")
                .hasMessageContaining("line 1")
                .hasMessageNotContaining("hunter2")
                .hasNoCause();
    }

    @Test
    void aMisspelledKeyIsReportedByNameNotSilentlyIgnored() throws IOException {
        Path file = file("{\"email\": \"a@example.com\", \"pasword\": \"hunter2-secret\"}");

        assertThatThrownBy(() -> MerossLocalCredentials.resolve(null, null, null, file, ENV))
                .hasMessageContaining("unknown key \"pasword\"")
                .hasMessageNotContaining("hunter2");
    }

    @Test
    void nonStringValuesAreRejected() throws IOException {
        Path file = file("{\"email\": \"a@example.com\", \"password\": 12345}");

        assertThatThrownBy(() -> MerossLocalCredentials.resolve(null, null, null, file, ENV))
                .hasMessageContaining("\"password\" must be a string")
                .hasMessageNotContaining("12345");
    }

    @Test
    void theEnvironmentDoesNotRescueABrokenFile() throws IOException {
        Path file = file("not json");

        assertThatThrownBy(() -> MerossLocalCredentials.resolve("env@example.com", "env-pass", null, file, ENV))
                .hasMessageContaining("is not a valid JSON object");
    }

    @Test
    void toStringNeverShowsTheEmailOrPassword() {
        String shown = new MerossLocalCredentials("secret-user@example.com", "hunter2-secret", "uuid-1").toString();

        assertThat(shown).doesNotContain("secret-user").doesNotContain("hunter2").contains("email=set", "password=set", "uuid-1");
    }

    /** The example is committed, so it must only ever hold placeholders. Real values go in meross.local.json. */
    @Test
    void theExampleFileParsesAndHoldsOnlyPlaceholders() {
        MerossLocalCredentials example = MerossLocalCredentials.resolve(null, null, null, Path.of("config", "meross.local.example.json"), ENV);

        assertThat(example.email()).as("example email (real values belong in config/meross.local.json)").isEqualTo("you@example.com");
        assertThat(example.password()).as("example password (real values belong in config/meross.local.json)").isEqualTo("your-meross-password");
        assertThat(example.deviceUuid()).isNull();
    }

    @Test
    void credentialsDoNotChangeTheCommandSafetyDefault() {
        MerossProperties configured = new MerossProperties("https://iotx-us.meross.com", null, null, null, 0, Duration.ofSeconds(8), false);

        MerossProperties withCreds = configured.withCredentials(new MerossLocalCredentials("a@example.com", "p", "uuid-1"));

        assertThat(withCreds.commandsEnabled()).isFalse();
        assertThat(withCreds.email()).isEqualTo("a@example.com");
        assertThat(withCreds.deviceUuid()).isEqualTo("uuid-1");
        assertThat(withCreds.toString()).doesNotContain("a@example.com");
    }

    @Test
    void theAppRefusesToStartWithTheRealDoorAndNoCredentials() {
        // Real credentials on this machine would (rightly) satisfy the check, so then there's nothing to show.
        assumeThat(Files.exists(MerossLocalCredentials.FILE)).isFalse();
        assumeThat(System.getenv("CASITA_MEROSS_EMAIL")).isNullOrEmpty();
        assumeThat(System.getenv("CASITA_MEROSS_PASSWORD")).isNullOrEmpty();

        new ApplicationContextRunner()
                .withUserConfiguration(MerossConfig.class, BindProperties.class)
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("casita.door=meross")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Meross credentials missing: email, password")
                            .hasMessageContaining("CASITA_MEROSS_EMAIL and CASITA_MEROSS_PASSWORD");
                });
    }

    @EnableConfigurationProperties(MerossProperties.class)
    static class BindProperties {
    }
}
