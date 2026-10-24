package home.casita;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import home.casita.garage.GarageProperties;
import home.casita.meross.CommandMode;
import home.casita.meross.MerossProperties;

/**
 * The staging guard against real environment variables, the way the VM sets them: from deploy/watson/casita.env.example
 * (with its placeholders filled in), plus one override at a time. If an ordinary environment variable could switch on
 * a door or commands without the guard seeing it, these fail.
 */
class StagingEnvironmentVariablesTest {

    static final Path ENV_EXAMPLE = Path.of("deploy/watson/casita.env.example");

    /** The template's settings, placeholders filled in as a real public host, family and project would be. */
    static Map<String, String> stagingVariables(Path dataDir) throws IOException {
        Map<String, String> vars = new LinkedHashMap<>();
        for (String line : Files.readAllLines(ENV_EXAMPLE)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] kv = line.split("=", 2);
            String value = kv[1].replace("<PUBLIC_HOST>", "casita.example.org").replace("<MEMBERS>", "shir,hubby")
                    .replace("<PROJECT_ID>", "casita-staging-test");
            vars.put(kv[0], value);
        }
        vars.remove("JAVA_OPTS"); // for java itself, not a Spring setting
        vars.put("CASITA_SECURITY_DATADIR", dataDir.toAbsolutePath().toString());
        vars.put("SERVER_PORT", "0");
        assertThat(String.join("\n", vars.values())).as("no placeholder left").doesNotContain("<", ">");
        return vars;
    }

    /* ---------- in this JVM: what the guard reads versus what Casita binds ---------- */

    /** application.yml's committed values underneath, then environment variables on top, as on the VM. */
    private static StandardEnvironment environment(Map<String, String> variables) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        Map<String, Object> yml = new HashMap<>();
        yml.put("casita.door", "simulated");
        yml.put("casita.meross.command-mode", "off");
        yml.put("casita.meross.command-channels[0]", "1");
        yml.put("casita.meross.doors.1", "Ford");
        yml.put("casita.meross.doors.2", "Toyota");
        yml.put("casita.storage.type", "file");
        yml.put("casita.security.rp-id", "localhost");
        yml.put("casita.security.allowed-origins[0]", "http://localhost:8080");
        yml.put("server.servlet.session.cookie.secure", "true");
        env.getPropertySources().addLast(new MapPropertySource("application.yml", yml));
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new HashMap<>(variables)));
        ConfigurationPropertySources.attach(env);
        return env;
    }

    @Test
    void theTemplateAsFilledInPassesTheGuard(@TempDir Path data) throws Exception {
        assertThat(StagingGuard.problems(environment(stagingVariables(data)), true, false, Map.of())).isEmpty();
    }

    @ParameterizedTest
    // The documented name, the legacy underscore spelling, and the lower-case forms some tools pass through.
    @ValueSource(strings = {"CASITA_MEROSS_COMMANDMODE", "CASITA_MEROSS_COMMAND_MODE", "casita_meross_commandmode",
            "casita.meross.command-mode"})
    void whateverNameTurnsLiveCommandsOnForCasitaAlsoStopsTheGuard(String name, @TempDir Path data) throws Exception {
        Map<String, String> vars = stagingVariables(data);
        vars.remove("CASITA_MEROSS_COMMANDMODE");
        vars.put(name, "live");
        StandardEnvironment env = environment(vars);
        CommandMode bound = Binder.get(env).bind("casita.meross", MerossProperties.class).map(MerossProperties::commandMode)
                .orElse(CommandMode.OFF);
        List<String> problems = StagingGuard.problems(env, true, false, Map.of());
        if (bound != CommandMode.OFF) {
            assertThat(problems).as("Casita would bind " + bound + " from " + name).anySatisfy(p -> assertThat(p).contains("command-mode"));
        }
        if (name.equals("CASITA_MEROSS_COMMANDMODE")) assertThat(bound).isEqualTo(CommandMode.LIVE);
    }

    @Test
    void twoSpellingsThatDisagreeAreRefused(@TempDir Path data) throws Exception {
        Map<String, String> vars = stagingVariables(data);
        vars.put("CASITA_MEROSS_COMMAND_MODE", "live"); // next to CASITA_MEROSS_COMMANDMODE=off
        List<String> problems = StagingGuard.problems(environment(vars), true, false, Map.of());
        CommandMode bound = Binder.get(environment(vars)).bind("casita.meross", MerossProperties.class).map(MerossProperties::commandMode)
                .orElse(CommandMode.OFF);
        if (bound != CommandMode.OFF) assertThat(problems).isNotEmpty();
    }

    /** Production as LIVE_DOORS.md sets it: casita.env switched to the real doors, the account in meross.env. */
    static Map<String, String> productionVariables(Path dataDir, String commandMode) throws IOException {
        Map<String, String> vars = stagingVariables(dataDir);
        vars.put("CASITA_DEPLOYMENT", "production");
        vars.put("CASITA_DOOR", "meross");
        vars.put("CASITA_MEROSS_COMMANDMODE", commandMode);
        vars.put("CASITA_MEROSS_COMMANDCHANNELS", "1,2");
        vars.put("CASITA_MEROSS_EMAIL", "placeholder@example.com");
        vars.put("CASITA_MEROSS_PASSWORD", "placeholder");
        vars.put("CASITA_GARAGE_TRAVELTIMES_FORD", "20s");
        vars.put("CASITA_GARAGE_SENSORLAGS_FORD", "5s");
        vars.put("CASITA_GARAGE_TRAVELTIMES_TOYOTA", "18s");
        vars.put("CASITA_GARAGE_SENSORLAGS_TOYOTA", "25s");
        return vars;
    }

    @Test
    void productionThroughEnvironmentVariablesBindsBothDoorsAndTheirTimings(@TempDir Path data) throws Exception {
        StandardEnvironment env = environment(productionVariables(data, "live"));
        assertThat(StagingGuard.problems(env, true, false, Map.of())).isEmpty();
        MerossProperties meross = Binder.get(env).bind("casita.meross", MerossProperties.class).get();
        assertThat(meross.commandMode()).isEqualTo(CommandMode.LIVE);
        assertThat(meross.commandChannels()).containsExactlyInAnyOrder(1, 2);
        assertThat(meross.doors()).containsExactlyInAnyOrderEntriesOf(Map.of(1, "Ford", 2, "Toyota"));
        GarageProperties garage = Binder.get(env).bind("casita.garage", GarageProperties.class).get();
        assertThat(garage.measured("Ford")).isTrue();
        assertThat(garage.measured("Toyota")).isTrue();
        assertThat(garage.travelTime("Ford")).hasSeconds(20);
        assertThat(garage.sensorLag("Toyota")).hasSeconds(25);
    }

    @Test
    void productionLiveWithoutToyotasMeasuredLagIsRefused(@TempDir Path data) throws Exception {
        Map<String, String> vars = productionVariables(data, "live");
        vars.remove("CASITA_GARAGE_SENSORLAGS_TOYOTA");
        assertThat(StagingGuard.problems(environment(vars), true, false, Map.of()))
                .singleElement().asString().contains("Toyota").contains("sensor-lags");
        vars.put("CASITA_MEROSS_COMMANDMODE", "off");
        assertThat(StagingGuard.problems(environment(vars), true, false, Map.of())).as("reading needs no timings").isEmpty();
    }

    /* ---------- a real Casita process ---------- */

    /** Starts Casita in its own JVM with exactly these environment variables, and returns its output once it settles. */
    private static Run run(Map<String, String> variables) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-Xmx256m", "-cp", System.getProperty("java.class.path"),
                CasitaApplication.class.getName());
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("CASITA_") || k.startsWith("MEROSS_") || k.startsWith("SPRING_")
                || k.equals("FIRESTORE_EMULATOR_HOST") || k.equals("GOOGLE_APPLICATION_CREDENTIALS") || k.equals("JAVA_TOOL_OPTIONS"));
        env.putAll(variables);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (out) { out.append(new String(buf, 0, n, StandardCharsets.UTF_8)); }
                }
            } catch (IOException ignored) {
                // the process ended
            }
        });
        reader.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline && p.isAlive()) {
            synchronized (out) {
                if (out.indexOf("staging guard: ok") >= 0 || out.indexOf("Staging guard: Casita won't start") >= 0) break;
            }
            Thread.sleep(100);
        }
        p.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        reader.join(5000);
        synchronized (out) { return new Run(out.toString()); }
    }

    record Run(String output) {
        boolean refused() { return output.contains("Staging guard: Casita won't start"); }
        boolean passed() { return output.contains("staging guard: ok"); }
    }

    @Test
    void theTemplateAsFilledInStartsPastTheGuard(@TempDir Path data) throws Exception {
        Run run = run(stagingVariables(data));
        assertThat(run.passed()).as(run.output()).isTrue();
        assertThat(run.refused()).isFalse();
        assertThat(run.output()).contains("door=simulated command_mode=off", "rp_id=casita.example.org");
    }

    @Test
    void theTemplateWithItsPlaceholdersDoesNotStart(@TempDir Path data) throws Exception {
        Map<String, String> vars = stagingVariables(data);
        vars.put("CASITA_SECURITY_RPID", "<PUBLIC_HOST>");
        vars.put("CASITA_SECURITY_ALLOWEDORIGINS", "https://<PUBLIC_HOST>");
        Run run = run(vars);
        assertThat(run.refused()).as(run.output()).isTrue();
        assertThat(run.output()).contains("rp-id");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CASITA_DOOR=meross",
            "CASITA_DOOR=MEROSS",
            "CASITA_MEROSS_COMMANDMODE=live",
            "CASITA_MEROSS_COMMANDMODE=dry-run",
            "CASITA_MEROSS_EMAIL=placeholder@example.com",
            "MEROSS_EMAIL=placeholder@example.com",
            "CASITA_STORAGE_TYPE=file",
            "CASITA_STORAGE_FIRESTORE_EMULATORHOST=127.0.0.1:18085",
            "FIRESTORE_EMULATOR_HOST=127.0.0.1:18085",
            "CASITA_SECURITY_DEVICEIDENTITY=development",
            "CASITA_SECURITY_DEVICEIDENTITY=tailscale",
            "SERVER_ADDRESS=0.0.0.0",
            "SERVER_SERVLET_SESSION_COOKIE_SECURE=false",
            "SPRING_PROFILES_ACTIVE=dev",
            "CASITA_DEPLOYMENT=production",
            "SPRING_APPLICATION_JSON={\"casita\":{\"door\":\"meross\"}}",
            "SPRING_APPLICATION_JSON={\"casita\":{\"meross\":{\"command-mode\":\"live\"}}}",
            "JAVA_TOOL_OPTIONS=-Dcasita.meross.command-mode=live"})
    void anUnsafeEnvironmentVariableStopsStaging(String override, @TempDir Path data) throws Exception {
        Map<String, String> vars = stagingVariables(data);
        String[] kv = override.split("=", 2);
        vars.put(kv[0], kv[1]);
        Run run = run(vars);
        assertThat(run.refused()).as(override + "\n" + run.output()).isTrue();
        assertThat(run.passed()).isFalse();
    }
}
