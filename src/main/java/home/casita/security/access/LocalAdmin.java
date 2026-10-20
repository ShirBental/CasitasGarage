package home.casita.security.access;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.function.Supplier;

import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import home.casita.security.store.FileSecurityStore;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Who may administer access: only someone on the server itself. A request is accepted when it comes
 * straight to 127.0.0.1 (never through tailscale serve, whose requests carry the phone's tailnet address
 * and Tailscale headers) and presents the admin secret from {@code <data-dir>/admin.secret}, a random value
 * created on first start, readable by Casita's user only. The {@code casita-access} script reads it.
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
        boolean proxied = request.getHeader("X-Forwarded-For") != null || request.getHeader("X-Forwarded-Host") != null
                || request.getHeader(TailscaleDeviceIdentity.USER_HEADER) != null || request.getHeader(TailscaleDeviceIdentity.FUNNEL_HEADER) != null;
        String given = request.getHeader(HEADER);
        return local && !proxied && given != null
                && MessageDigest.isEqual(secret, given.trim().getBytes(StandardCharsets.UTF_8));
    }
}
