package home.casita.security.access;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import home.casita.security.store.FileSecurityStore;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Who may administer access: only someone on the server itself. A request is accepted when it comes
 * straight to 127.0.0.1 (never through the reverse proxy: Caddy answers {@code /admin} itself with 404, and
 * anything it does forward carries the client's address and X-Forwarded-* headers) and presents the admin
 * secret from {@code <data-dir>/admin.secret}, a random value created on first start, readable by Casita's
 * user only. The {@code casita-access} script reads it.
 */
public class LocalAdmin implements AuthorizationManager<RequestAuthorizationContext> {

    public static final String HEADER = "X-Casita-Admin";

    private final byte[] secret;

    public LocalAdmin(Path dataDir) {
        Path file = dataDir.resolve("admin.secret");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(dataDir);
                FileSecurityStore.restrict(dataDir, "rwx------");
                byte[] raw = new byte[32];
                new SecureRandom().nextBytes(raw);
                Files.createFile(file);
                FileSecurityStore.restrict(file, "rw-------");
                Files.writeString(file, HexFormat.of().formatHex(raw) + "\n", StandardCharsets.UTF_8);
            }
            this.secret = Files.readString(file, StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't set up the admin secret", e);
        }
    }

    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        return new AuthorizationDecision(allowed(context.getRequest()));
    }

    boolean allowed(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        boolean local = "127.0.0.1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote);
        String given = request.getHeader(HEADER);
        return local && !proxied(request) && given != null
                && MessageDigest.isEqual(secret, given.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** Headers only a proxy adds. Any of them on a request means it didn't come straight from this server. */
    static final List<String> PROXY_HEADERS = List.of("X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Port",
            "X-Real-IP", "Forwarded", "Via");

    /** True when the request carries any sign of having been forwarded. */
    static boolean proxied(HttpServletRequest request) {
        return PROXY_HEADERS.stream().anyMatch(h -> request.getHeader(h) != null);
    }
}
