package home.casita.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits on the public sign-in and invite endpoints ({@link AuthRateLimitFilter}).
 *
 * @param perClient requests one client address may make to them in a window
 * @param total     requests everyone together may make to them in a window
 * @param window    the window
 */
@ConfigurationProperties("casita.security.rate-limit")
public record AuthRateLimitProperties(
        @DefaultValue("30") int perClient,
        @DefaultValue("300") int total,
        @DefaultValue("10m") Duration window) {

    public AuthRateLimitProperties {
        if (perClient < 1 || total < perClient) {
            throw new IllegalArgumentException("casita.security.rate-limit needs per-client >= 1 and total >= per-client");
        }
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("casita.security.rate-limit.window must be positive");
        }
    }
}
