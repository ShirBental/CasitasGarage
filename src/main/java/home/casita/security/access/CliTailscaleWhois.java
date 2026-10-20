package home.casita.security.access;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code tailscale whois --json <address>}, run against the local tailscaled, with a short cache so a page
 * load doesn't start a process per request. Any error, timeout or unexpected output is "unknown".
 */
public class CliTailscaleWhois implements TailscaleWhois {

    private static final Logger log = LoggerFactory.getLogger(CliTailscaleWhois.class);
    static final Duration TIMEOUT = Duration.ofSeconds(3);
    static final Duration CACHE = Duration.ofSeconds(30);

    private record Cached(Optional<Node> node, Instant until) {}

    private final String cli;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public CliTailscaleWhois(String cli, Clock clock) {
        this.cli = cli;
        this.clock = clock;
    }

    @Override
    public Optional<Node> whois(String address) {
        Cached hit = cache.get(address);
        if (hit != null && clock.instant().isBefore(hit.until())) return hit.node();
        Optional<Node> node = ask(address);
        cache.put(address, new Cached(node, clock.instant().plus(CACHE)));
        return node;
    }

    private Optional<Node> ask(String address) {
        Process process = null;
        try {
            process = new ProcessBuilder(List.of(cli, "whois", "--json", address)).redirectErrorStream(false).start();
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("tailscale whois timed out; treating the device as unverified");
                return Optional.empty();
            }
            if (process.exitValue() != 0) return Optional.empty();
            return parse(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Couldn't run tailscale whois ({}); treating the device as unverified", e.getClass().getSimpleName());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null) process.destroyForcibly();
        }
    }

    /** The parts of tailscaled's WhoIs answer Casita uses. Anything missing: unknown. */
    Optional<Node> parse(String output) {
        try {
            JsonNode root = json.readTree(output);
            JsonNode node = root.path("Node");
            String stableId = node.path("StableID").asText("");
            String name = node.path("ComputedName").asText(node.path("Name").asText(""));
            String login = root.path("UserProfile").path("LoginName").asText("");
            boolean tagged = node.path("Tags").isArray() && !node.path("Tags").isEmpty();
            if (stableId.isBlank() || login.isBlank()) return Optional.empty();
            return Optional.of(new Node(stableId, name, login, tagged));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
