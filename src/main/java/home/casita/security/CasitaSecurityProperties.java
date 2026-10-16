package home.casita.security;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sign-in settings. See docs/APP.md.
 *
 * @param rpId           the passkey relying-party id: the host name passkeys are bound to. In production the
 *                       Tailscale name, e.g. {@code casita.example-tailnet.ts.net}; if it changes, everyone
 *                       enrolls again. {@code localhost} for local development.
 * @param allowedOrigins the exact origins the web app is served from (scheme, host, port)
 * @param family         who may sign in, by short name (e.g. shir, hubby). Nobody else can, even with an invite.
 * @param dataDir        where passkeys and invite links are kept: outside git, readable by Casita's user only
 */
@ConfigurationProperties("casita.security")
public record CasitaSecurityProperties(
        @DefaultValue("localhost") String rpId,
        @DefaultValue("http://localhost:8080") Set<String> allowedOrigins,
        List<String> family,
        @DefaultValue("data") Path dataDir) {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    public CasitaSecurityProperties {
        family = family == null ? List.of() : List.copyOf(family);
        allowedOrigins = Set.copyOf(allowedOrigins);
        for (String name : family) {
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("casita.security.family names are lower-case letters, digits and '-', "
                        + "starting with a letter, up to 32 characters: " + name);
            }
        }
        if (rpId == null || rpId.isBlank()) {
            throw new IllegalArgumentException("casita.security.rp-id is required");
        }
        for (String origin : allowedOrigins) {
            URI uri = URI.create(origin);
            boolean local = "localhost".equals(uri.getHost());
            if (!("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))) {
                throw new IllegalArgumentException("casita.security.allowed-origins must be https (http only for localhost): " + origin);
            }
            if (uri.getHost() == null || !(uri.getHost().equals(rpId) || uri.getHost().endsWith("." + rpId))) {
                throw new IllegalArgumentException("casita.security.allowed-origins " + origin + " isn't on the rp-id " + rpId);
            }
        }
    }
}
