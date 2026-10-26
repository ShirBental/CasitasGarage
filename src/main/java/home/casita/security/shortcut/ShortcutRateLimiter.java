package home.casita.security.shortcut;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window limits for the Shortcut endpoints, like {@code AuthRateLimitFilter}'s for sign-in:
 * <ul>
 *   <li>every request, before its token is looked at: {@link #PER_CLIENT} per client address and {@link #TOTAL} in all;</li>
 *   <li>commands, once the token is known: {@link #COMMANDS_PER_TOKEN} per token.</li>
 * </ul>
 * Beyond a limit the answer is 429. Tokens have 256 random bits, so this isn't what stops guessing; it stops a stolen
 * token or a looping Shortcut from working the door over and over, and keeps the endpoints from being hammered.
 * The client address is the one Tomcat took from Caddy's X-Forwarded-For, trusted from 127.0.0.1 only.
 */
public class ShortcutRateLimiter {

    public static final Duration WINDOW = Duration.ofMinutes(10);
    public static final int PER_CLIENT = 20;
    public static final int TOTAL = 200;
    public static final int COMMANDS_PER_TOKEN = 6;
    /** Beyond this many client addresses in a window, new ones share the total only (memory stays bounded). */
    static final int MAX_TRACKED = 10_000;

    private final Clock clock;
    private final Map<String, AtomicInteger> clients = new ConcurrentHashMap<>();
    /** Only tokens that exist reach this, so it can't be filled by strangers (and never shares the clients' cap). */
    private final Map<String, AtomicInteger> tokens = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private volatile long window = -1;

    public ShortcutRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Counts one request from this client address. */
    public boolean allowRequest(String client) {
        roll();
        if (total.incrementAndGet() > TOTAL) return false;
        String key = client == null ? "" : client;
        AtomicInteger count = clients.get(key);
        if (count == null) {
            if (clients.size() >= MAX_TRACKED) return true; // still counted in the total above
            count = clients.computeIfAbsent(key, k -> new AtomicInteger());
        }
        return count.incrementAndGet() <= PER_CLIENT;
    }

    /** Counts one command with this token. */
    public boolean allowCommand(String tokenId) {
        roll();
        return tokens.computeIfAbsent(tokenId, k -> new AtomicInteger()).incrementAndGet() <= COMMANDS_PER_TOKEN;
    }

    /** A new window: every count starts again. */
    private void roll() {
        long now = clock.millis() / WINDOW.toMillis();
        if (now == window) return;
        synchronized (this) {
            if (now == window) return;
            clients.clear();
            tokens.clear();
            total.set(0);
            window = now;
        }
    }
}
