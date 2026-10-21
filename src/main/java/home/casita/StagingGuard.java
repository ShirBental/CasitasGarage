package home.casita;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertyResolver;

/**
 * Staging never drives the real garage (docs/STAGING_GUARD.md). Runs before any bean is created, after every
 * property source (application.yml, environment variables, command line) is loaded, and refuses to start when:
 * <ul>
 *   <li>Casita is on a Compute Engine VM and {@code casita.deployment} isn't {@code staging}. The VM is
 *       recognised by its hardware ({@value #GCE_PRODUCT_NAME} in {@code /sys/class/dmi/id/product_name}),
 *       which no setting can change, so an environment variable can't make a VM look like a laptop;</li>
 *   <li>{@code casita.deployment} is anything but empty, {@code local} or {@code staging};</li>
 *   <li>in staging: {@code casita.door} isn't exactly {@code simulated}, {@code casita.meross.command-mode}
 *       isn't exactly {@code off}, or Meross credentials are present anywhere Casita or its live tests would
 *       read them (properties, {@code MEROSS_EMAIL}/{@code MEROSS_PASSWORD}, {@code config/meross.local.json}).</li>
 * </ul>
 * Local development (no {@code casita.deployment}, not on Compute Engine) is unchanged.
 */
public class StagingGuard implements EnvironmentPostProcessor, Ordered {

    static final String GCE_PRODUCT_NAME = "Google Compute Engine";
    static final Path DMI_PRODUCT_NAME = Path.of("/sys/class/dmi/id/product_name");
    static final Path MEROSS_LOCAL_FILE = Path.of("config", "meross.local.json");

    private final Log log;
    private final Path dmiProductName;
    private final Path merossLocalFile;

    public StagingGuard(DeferredLogFactory logs) {
        this(logs.getLog(StagingGuard.class), DMI_PRODUCT_NAME, MEROSS_LOCAL_FILE);
    }

    /** For tests: where to look for the hardware's product name and the local Meross file. */
    StagingGuard(Log log, Path dmiProductName, Path merossLocalFile) {
        this.log = log;
        this.dmiProductName = dmiProductName;
        this.merossLocalFile = merossLocalFile;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean computeEngine = onComputeEngine(dmiProductName);
        List<String> problems = problems(environment, computeEngine, Files.exists(merossLocalFile), System.getenv());
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Staging guard: Casita won't start. " + String.join(" ", problems)
                    + " See docs/STAGING_GUARD.md.");
        }
        if ("staging".equals(deployment(environment))) {
            log.info("staging guard: ok deployment=staging compute_engine=" + computeEngine
                    + " door=simulated command_mode=off meross_credentials=absent");
        }
    }

    /** Runs last, once application.yml and every override are in the environment. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** Everything wrong with this configuration, or nothing. */
    static List<String> problems(PropertyResolver env, boolean computeEngine, boolean merossLocalFileExists,
                                 Map<String, String> processEnv) {
        List<String> problems = new ArrayList<>();
        String deployment = deployment(env);
        if (!deployment.isEmpty() && !deployment.equals("local") && !deployment.equals("staging")) {
            problems.add("casita.deployment is '" + deployment + "'; it must be empty, 'local' or 'staging'.");
            return problems;
        }
        if (computeEngine && !deployment.equals("staging")) {
            problems.add("This is a Compute Engine VM, where Casita runs only as staging, but casita.deployment is "
                    + (deployment.isEmpty() ? "not set" : "'" + deployment + "'") + "; set it to 'staging'.");
            return problems;
        }
        if (!deployment.equals("staging")) return problems;

        String door = env.getProperty("casita.door");
        if (!"simulated".equals(door)) {
            problems.add("Staging needs casita.door=simulated exactly, but it is " + shown(door) + ".");
        }
        String commandMode = env.getProperty("casita.meross.command-mode");
        if (!"off".equals(commandMode)) {
            problems.add("Staging needs casita.meross.command-mode=off exactly, but it is " + shown(commandMode) + ".");
        }
        for (String key : List.of("casita.meross.email", "casita.meross.password")) {
            if (present(env.getProperty(key))) problems.add("Staging must have no Meross credentials, but " + key + " is set.");
        }
        for (String name : List.of("MEROSS_EMAIL", "MEROSS_PASSWORD")) {
            if (present(processEnv.get(name))) problems.add("Staging must have no Meross credentials, but " + name + " is set.");
        }
        if (merossLocalFileExists) {
            problems.add("Staging must have no Meross credentials, but " + MEROSS_LOCAL_FILE + " exists.");
        }
        return problems;
    }

    /** True on a Compute Engine VM. A product name that exists but can't be read counts as one: fail closed. */
    static boolean onComputeEngine(Path dmiProductName) {
        if (!Files.exists(dmiProductName)) return false;
        try {
            return Files.readString(dmiProductName).trim().equals(GCE_PRODUCT_NAME);
        } catch (IOException e) {
            return true;
        }
    }

    private static String deployment(PropertyResolver env) {
        String value = env.getProperty("casita.deployment");
        return value == null ? "" : value.trim();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String shown(String value) {
        return value == null ? "not set" : "'" + value + "'";
    }
}
