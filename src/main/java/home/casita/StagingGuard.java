package home.casita;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Staging never drives the real garage, and production drives it only as set up for it (docs/STAGING_GUARD.md).
 * Runs before any bean is created, after every
 * property source (application.yml, environment variables, command line) is loaded, and refuses to start when:
 * <ul>
 *   <li>Casita is on a Compute Engine VM and {@code casita.deployment} is neither {@code staging} nor
 *       {@code production}. The VM is
 *       recognised by its hardware ({@value #GCE_PRODUCT_NAME} in {@code /sys/class/dmi/id/product_name}),
 *       which no setting can change, so an environment variable can't make a VM look like a laptop;</li>
 *   <li>{@code casita.deployment} is anything but empty, {@code local}, {@code staging} or {@code production};</li>
 *   <li>in production: {@code casita.door} isn't exactly {@code meross}, {@code casita.meross.command-mode} isn't
 *       exactly {@code off}, {@code dry-run} or {@code live}, the doors aren't exactly 1 Ford and 2 Toyota, a command
 *       channel isn't one of them, the transport isn't {@code cloud} or a valid {@code local}, the account isn't set
 *       in the service's own environment (never {@code MEROSS_*} or {@code config/meross.local.json}), or, with
 *       {@code live}, a commanded door lacks its measured {@code travel-times} and {@code sensor-lags};</li>
 *   <li>in staging: {@code casita.door} isn't exactly {@code simulated}, {@code casita.meross.command-mode}
 *       isn't exactly {@code off}, or Meross credentials are present anywhere Casita or its live tests would
 *       read them (properties, {@code MEROSS_EMAIL}/{@code MEROSS_PASSWORD}, {@code config/meross.local.json});</li>
 *   <li>in staging or production, sign-in or storage isn't set up for the server: passkeys bound to
 *       {@code watson.<tailnet>.ts.net} with that one https origin, Tailscale device identity, a family, an absolute
 *       data directory, Firestore (never an emulator), Secure session cookies, and not the {@code dev} profile.</li>
 * </ul>
 * Local development (no {@code casita.deployment}, not on Compute Engine) is unchanged.
 */
public class StagingGuard implements EnvironmentPostProcessor, Ordered {

    static final String GCE_PRODUCT_NAME = "Google Compute Engine";
    static final Path DMI_PRODUCT_NAME = Path.of("/sys/class/dmi/id/product_name");
    static final Path MEROSS_LOCAL_FILE = Path.of("config", "meross.local.json");
    /** The server's permanent name on the tailnet: watson.<tailnet>.ts.net, with the real tailnet, no placeholder. */
    static final Pattern WATSON_RP_ID = Pattern.compile("watson\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?\\.ts\\.net");

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
        String deployment = environment.getProperty("casita.deployment", "").trim();
        if ("staging".equals(deployment)) {
            log.info("staging guard: ok deployment=staging compute_engine=" + computeEngine
                    + " door=simulated command_mode=off meross_credentials=absent storage=firestore rp_id="
                    + environment.getProperty("casita.security.rp-id"));
        } else if ("production".equals(deployment)) {
            Binder binder = Binder.get(environment);
            log.info("production guard: ok deployment=production compute_engine=" + computeEngine + " door=meross command_mode="
                    + environment.getProperty("casita.meross.command-mode") + " command_channels="
                    + new java.util.TreeSet<>(binder.bind("casita.meross.command-channels", Bindable.setOf(Integer.class)).orElse(Set.of()))
                    + " transport=" + environment.getProperty("casita.meross.transport", "cloud") + " storage=firestore rp_id="
                    + environment.getProperty("casita.security.rp-id"));
        }
    }

    /** Runs last, once application.yml and every override are in the environment. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** Everything wrong with this configuration, or nothing. */
    static List<String> problems(ConfigurableEnvironment env, boolean computeEngine, boolean merossLocalFileExists,
                                 Map<String, String> processEnv) {
        List<String> problems = new ArrayList<>();
        Settings settings = new Settings(env, problems);
        String deployment = deployment(settings);
        if (!deployment.isEmpty() && !deployment.equals("local") && !deployment.equals("staging") && !deployment.equals("production")) {
            problems.add("casita.deployment is '" + deployment + "'; it must be empty, 'local', 'staging' or 'production'.");
            return problems;
        }
        boolean server = deployment.equals("staging") || deployment.equals("production");
        if (computeEngine && !server) {
            problems.add("This is a Compute Engine VM, where Casita runs only as staging or production, but casita.deployment is "
                    + (deployment.isEmpty() ? "not set" : "'" + deployment + "'") + "; set it to 'staging' or 'production'.");
            return problems;
        }
        if (!server) return problems;
        if (deployment.equals("production")) {
            production(env, settings, merossLocalFileExists, processEnv, problems);
            signInAndStorage(env, settings, processEnv, problems, "Production");
            return problems;
        }

        String door = settings.get("casita.door");
        if (!"simulated".equals(door)) {
            problems.add("Staging needs casita.door=simulated exactly, but it is " + shown(door) + ".");
        }
        String commandMode = settings.get("casita.meross.command-mode");
        if (!"off".equals(commandMode)) {
            problems.add("Staging needs casita.meross.command-mode=off exactly, but it is " + shown(commandMode) + ".");
        }
        for (String key : List.of("casita.meross.email", "casita.meross.password")) {
            if (present(settings.get(key))) problems.add("Staging must have no Meross credentials, but " + key + " is set.");
        }
        for (String name : List.of("MEROSS_EMAIL", "MEROSS_PASSWORD")) {
            if (present(processEnv.get(name))) problems.add("Staging must have no Meross credentials, but " + name + " is set.");
        }
        if (merossLocalFileExists) {
            problems.add("Staging must have no Meross credentials, but " + MEROSS_LOCAL_FILE + " exists.");
        }
        signInAndStorage(env, settings, processEnv, problems, "Staging");
        return problems;
    }

    /** The real doors, as set up for them: exactly Ford and Toyota, credentials only from the service's environment. */
    private static void production(ConfigurableEnvironment env, Settings settings, boolean merossLocalFileExists,
                                   Map<String, String> processEnv, List<String> problems) {
        Binder binder = Binder.get(env);
        String door = settings.get("casita.door");
        if (!"meross".equals(door)) {
            problems.add("Production needs casita.door=meross exactly, but it is " + shown(door) + ".");
        }
        String commandMode = settings.get("casita.meross.command-mode");
        if (!Set.of("off", "dry-run", "live").contains(String.valueOf(commandMode))) {
            problems.add("Production needs casita.meross.command-mode off, dry-run or live exactly, but it is " + shown(commandMode) + ".");
        }
        Map<Integer, String> doors = binder.bind("casita.meross.doors", Bindable.mapOf(Integer.class, String.class)).orElse(Map.of());
        if (!doors.equals(Map.of(1, "Ford", 2, "Toyota"))) {
            problems.add("Production needs casita.meross.doors exactly 1: Ford and 2: Toyota (channel 3 is disabled), but it is "
                    + new java.util.TreeMap<>(doors) + ".");
        }
        Set<Integer> channels = binder.bind("casita.meross.command-channels", Bindable.setOf(Integer.class)).orElse(Set.of());
        if (!Set.of(1, 2).containsAll(channels)) {
            problems.add("Production's casita.meross.command-channels may only be 1 (Ford) and 2 (Toyota), but it is "
                    + new java.util.TreeSet<>(channels) + ".");
        }
        String transport = settings.get("casita.meross.transport");
        if (transport != null && !Set.of("cloud", "local").contains(transport)) {
            problems.add("Production's casita.meross.transport must be cloud or local exactly, but it is " + shown(transport) + ".");
        } else if ("local".equals(transport)) {
            String url = settings.get("casita.meross.local-url");
            try {
                home.casita.meross.MerossLinkProperties.checkLocalUrl(url == null ? null : java.net.URI.create(url));
            } catch (IllegalArgumentException e) {
                problems.add("Production's local transport: " + e.getMessage() + ".");
            }
        }
        for (String key : List.of("casita.meross.email", "casita.meross.password")) {
            if (!present(settings.get(key))) problems.add("Production needs " + key + " (CASITA_MEROSS_* in /etc/casita/meross.env).");
        }
        for (String name : List.of("MEROSS_EMAIL", "MEROSS_PASSWORD")) {
            if (present(processEnv.get(name))) problems.add("Production takes the account only from CASITA_MEROSS_*, but " + name + " is set.");
        }
        if (merossLocalFileExists) {
            problems.add("Production takes the account only from CASITA_MEROSS_*, but " + MEROSS_LOCAL_FILE + " exists.");
        }
        if ("live".equals(commandMode)) {
            // H1/H3: a door moves for real only on timings measured on it (MerossConfig refuses too; this says so earlier).
            Map<String, String> travel = lowerKeys(binder.bind("casita.garage.travel-times", Bindable.mapOf(String.class, String.class)).orElse(Map.of()));
            Map<String, String> lags = lowerKeys(binder.bind("casita.garage.sensor-lags", Bindable.mapOf(String.class, String.class)).orElse(Map.of()));
            for (Integer channel : new java.util.TreeSet<>(channels)) {
                String name = doors.get(channel);
                if (name == null) continue;
                String key = name.toLowerCase(java.util.Locale.ROOT);
                if (!travel.containsKey(key) || !lags.containsKey(key)) {
                    problems.add("Production live commands to " + name + " need its measured casita.garage.travel-times." + name
                            + " and casita.garage.sensor-lags." + name + ".");
                }
            }
        }
    }

    private static Map<String, String> lowerKeys(Map<String, String> m) {
        Map<String, String> out = new java.util.HashMap<>();
        m.forEach((k, v) -> out.put(k.toLowerCase(java.util.Locale.ROOT), v));
        return out;
    }

    /** Staging is reached only through tailscale serve at https://watson.<tailnet>.ts.net, and keeps its security store in Firestore. */
    private static void signInAndStorage(ConfigurableEnvironment env, Settings settings, Map<String, String> processEnv,
                                         List<String> problems, String who) {
        Binder binder = Binder.get(env);
        String rpId = settings.get("casita.security.rp-id");
        if (rpId == null || !WATSON_RP_ID.matcher(rpId).matches()) {
            problems.add(who + " needs casita.security.rp-id=watson.<tailnet>.ts.net with the real tailnet name, but it is "
                    + shown(rpId) + ".");
        }
        Set<String> origins = binder.bind("casita.security.allowed-origins", Bindable.setOf(String.class)).orElse(Set.of());
        if (rpId == null || !origins.equals(Set.of("https://" + rpId))) {
            problems.add(who + " needs casita.security.allowed-origins to be exactly https://<rp-id>, but it is " + origins + ".");
        }
        List<String> family = binder.bind("casita.security.family", Bindable.listOf(String.class)).orElse(List.of());
        if (family.stream().noneMatch(StagingGuard::present)) {
            problems.add(who + " needs casita.security.family (who may sign in), but it is empty.");
        }
        String identity = settings.get("casita.security.device-identity");
        if (identity == null || !identity.equalsIgnoreCase("tailscale")) {
            problems.add(who + " needs casita.security.device-identity=tailscale, but it is " + shown(identity) + ".");
        }
        String dataDir = settings.get("casita.security.data-dir");
        if (dataDir == null || dataDir.equals(Settings.AMBIGUOUS) || !Path.of(dataDir).isAbsolute()) {
            problems.add(who + " needs an absolute casita.security.data-dir, but it is " + shown(dataDir) + ".");
        }
        String storage = settings.get("casita.storage.type");
        if (!"firestore".equals(storage)) {
            problems.add(who + " needs casita.storage.type=firestore exactly, but it is " + shown(storage) + ".");
        }
        if (present(settings.get("casita.storage.firestore.emulator-host")) || present(processEnv.get("FIRESTORE_EMULATOR_HOST"))) {
            problems.add(who + " must use the real Firestore, but an emulator is configured.");
        }
        if (!"true".equals(settings.get("server.servlet.session.cookie.secure"))) {
            problems.add(who + " needs Secure session cookies (server.servlet.session.cookie.secure=true).");
        }
        if (Arrays.asList(env.getActiveProfiles()).contains("dev")) {
            problems.add(who + " must not run with the dev profile.");
        }
    }

    /**
     * Each setting read the two ways Casita itself reads settings: as {@code @ConditionalOnProperty} does (the
     * environment) and as {@code @ConfigurationProperties} does (the binder, with its own mapping of environment
     * variable names). If the two disagree, a setting could look safe here and be bound unsafe there, so it is refused.
     */
    private static final class Settings {
        static final String AMBIGUOUS = "<ambiguous>";

        private final ConfigurableEnvironment env;
        private final Binder binder;
        private final List<String> problems;

        Settings(ConfigurableEnvironment env, List<String> problems) {
            this.env = env;
            this.binder = Binder.get(env);
            this.problems = problems;
        }

        String get(String key) {
            String direct = blankToNull(env.getProperty(key));
            String bound = blankToNull(binder.bind(key, Bindable.of(String.class)).orElse(null));
            if (Objects.equals(direct, bound)) return env.getProperty(key);
            problems.add("Staging guard can't tell what " + key + " is: it reads " + shown(direct) + " one way and "
                    + shown(bound) + " the other; set it once, by its documented name.");
            return AMBIGUOUS;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
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

    private static String deployment(Settings settings) {
        String value = settings.get("casita.deployment");
        return value == null ? "" : value.trim();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String shown(String value) {
        return value == null ? "not set" : "'" + value + "'";
    }
}
