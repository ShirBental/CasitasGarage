package home.casita.security;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Casita is on the public internet, so the endpoints anyone can call to sign in, redeem an invite or set up a
 * passkey are rate limited: per client address and in total, in fixed windows, answering 429 beyond that.
 * Invite tokens have 256 random bits, so this isn't what keeps them from being guessed; it keeps the endpoints
 * (each a Firestore read or a WebAuthn verification) from being hammered. The client address is the one Tomcat
 * took from the reverse proxy's X-Forwarded-For, trusted from 127.0.0.1 only.
 */
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    static final Set<String> LIMITED = Set.of("/login/ott", "/login/webauthn", "/webauthn/authenticate/options",
            "/webauthn/register/options", "/webauthn/register");
    /** Beyond this many tracked addresses in a window, new addresses share the total only (memory stays bounded). */
    static final int MAX_TRACKED = 10_000;

    private final AuthRateLimitProperties limits;
    private final Clock clock;
    private final Map<String, AtomicInteger> perClient = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private volatile long window = -1;

    public AuthRateLimitFilter(AuthRateLimitProperties limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && LIMITED.contains(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!allowed(request.getRemoteAddr())) {
            log.warn("Rate limit: {} {} refused for {}", request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            response.setHeader("Retry-After", Long.toString(limits.window().toSeconds()));
            response.sendError(429);
            return;
        }
        chain.doFilter(request, response);
    }

    boolean allowed(String client) {
        roll();
        if (total.incrementAndGet() > limits.total()) return false;
        String key = client == null ? "" : client;
        AtomicInteger count = perClient.get(key);
        if (count == null) {
            if (perClient.size() >= MAX_TRACKED) return true; // counted in the total above
            count = perClient.computeIfAbsent(key, k -> new AtomicInteger());
        }
        return count.incrementAndGet() <= limits.perClient();
    }

    /** A new window: every count starts again. */
    private void roll() {
        long now = clock.millis() / limits.window().toMillis();
        if (now == window) return;
        synchronized (this) {
            if (now == window) return;
            perClient.clear();
            total.set(0);
            window = now;
        }
    }
}
