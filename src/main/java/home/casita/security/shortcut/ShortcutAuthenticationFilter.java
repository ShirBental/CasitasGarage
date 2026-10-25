package home.casita.security.shortcut;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import home.casita.security.store.SecurityStore;
import home.casita.security.store.StoreUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates a Shortcut request by its token, and nothing else: no session, no cookie, no browser key. In order:
 * <ol>
 *   <li>HTTPS only (behind Caddy, Tomcat knows from X-Forwarded-Proto, trusted from 127.0.0.1 only): otherwise 403.</li>
 *   <li>The client's request count ({@link ShortcutRateLimiter}): otherwise 429.</li>
 *   <li>{@code Authorization: Bearer <token>}: a token Casita created, not revoked, not expired, whose member is still in
 *       the family and active in the security store (read on every request; an unreachable store refuses, 503).
 *       Otherwise 401, the same answer whatever the reason; the reason is logged with the token's id, never the token.</li>
 *   <li>For a command, the token's command count: otherwise 429.</li>
 * </ol>
 * Which door and action the token allows is checked by the endpoint, against the request's path.
 */
public class ShortcutAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHORITY = "SHORTCUT";
    private static final Logger log = LoggerFactory.getLogger(ShortcutAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final ShortcutTokens tokens;
    private final SecurityStore store;
    private final List<String> family;
    private final ShortcutRateLimiter limits;
    private final Clock clock;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    public ShortcutAuthenticationFilter(ShortcutTokens tokens, SecurityStore store, List<String> family, ShortcutRateLimiter limits,
                                        Clock clock) {
        this.tokens = tokens;
        this.store = store;
        this.family = List.copyOf(family);
        this.limits = limits;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String client = request.getRemoteAddr();
        if (!request.isSecure()) {
            log.warn("Shortcut request refused: not HTTPS (client {})", client);
            refuse(response, 403, "https-required", "Casita only answers Shortcuts over HTTPS.");
            return;
        }
        if (!limits.allowRequest(client)) {
            log.warn("Shortcut request refused: rate limit for client {}", client);
            response.setHeader("Retry-After", Long.toString(ShortcutRateLimiter.WINDOW.toSeconds()));
            refuse(response, 429, "rate-limited", "Too many tries. Wait a few minutes, or use the app.");
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) { // the scheme is case-insensitive
            log.warn("Shortcut request refused: no bearer token (client {})", client);
            unauthorized(response);
            return;
        }
        Optional<ShortcutTokens.Token> found = tokens.lookUp(header.substring(BEARER.length()).trim());
        if (found.isEmpty()) {
            log.warn("Shortcut request refused: unknown or malformed token (client {})", client);
            unauthorized(response);
            return;
        }
        ShortcutTokens.Token token = found.get();
        String reason = token.revoked() ? "revoked" : token.expired(clock.instant()) ? "expired"
                : !family.contains(token.member()) ? "member not in casita.security.family" : null;
        if (reason == null) {
            try {
                if (store.member(token.member()).filter(SecurityStore.Member::active).isEmpty()) reason = "member revoked";
            } catch (StoreUnavailableException e) {
                log.warn("Shortcut request refused: security store unavailable, token {} (client {})", token.id(), client);
                refuse(response, 503, "unavailable", "Casita can't check this Shortcut right now. Use the app.");
                return;
            }
        }
        if (reason != null) {
            log.warn("Shortcut request refused: token {} {} (client {})", token.id(), reason, client);
            unauthorized(response);
            return;
        }
        if ("POST".equals(request.getMethod()) && !limits.allowCommand(token.id())) {
            log.warn("Shortcut command refused: rate limit for token {} ({}, client {})", token.id(), token.member(), client);
            response.setHeader("Retry-After", Long.toString(ShortcutRateLimiter.WINDOW.toSeconds()));
            refuse(response, 429, "rate-limited", "That Shortcut has been used a lot just now. Wait a few minutes, or use the app.");
            return;
        }

        SecurityContext context = contexts.createEmptyContext();
        context.setAuthentication(new PreAuthenticatedAuthenticationToken(token, null, List.of(new SimpleGrantedAuthority(AUTHORITY))));
        contexts.setContext(context);
        chain.doFilter(request, response);
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        refuse(response, 401, "unauthorized", "Casita didn't accept this Shortcut's key. It may have been revoked or expired.");
    }

    /** A fixed JSON answer: no token, nothing from the request. */
    static void refuse(HttpServletResponse response, int status, String error, String speak) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"result\":\"refused\",\"error\":\"" + error + "\",\"speak\":\"" + speak + "\"}");
    }
}
