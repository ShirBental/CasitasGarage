package home.casita.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.web.authentication.ott.OneTimeTokenGenerationSuccessHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Delivers an invite (a Spring Security one-time token, single use, short-lived) by writing its link to
 * {@code <data-dir>/invites/<name>.txt}, readable by Casita's user only. Whoever runs the server reads
 * it there and sends it to the person. The token is never logged and never returned to the caller, so
 * asking for an invite gets nobody anything; names outside the family get nothing written at all, and
 * the same answer.
 */
class InviteLinks implements OneTimeTokenGenerationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(InviteLinks.class);

    private final Path dir;
    private final String baseUrl;
    private final List<String> family;

    InviteLinks(Path dataDir, String baseUrl, List<String> family) {
        this.dir = dataDir.resolve("invites");
        this.baseUrl = baseUrl;
        this.family = family;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, OneTimeToken token) throws IOException {
        String name = token.getUsername();
        if (family.contains(name)) {
            Files.createDirectories(dir);
            FilePasskeyStore.restrict(dir, "rwx------");
            Path file = dir.resolve(name + ".txt");
            Files.deleteIfExists(file);
            Files.createFile(file);
            FilePasskeyStore.restrict(file, "rw-------");
            Files.writeString(file, baseUrl + "/login/ott?token=" + token.getTokenValue() + "\n", StandardCharsets.UTF_8);
            log.info("Invite for {} written to {} (expires {})", name, file, token.getExpiresAt());
        } else {
            log.warn("Invite asked for someone outside casita.security.family; nothing written");
        }
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write("If that name is in the family, an invite link is waiting on the server.\n");
    }
}
