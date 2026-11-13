package home.casita.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long an enrolled browser stays signed in without Face ID (docs/ACCESS.md, "Staying signed in"). Checked when
 * bound, so a bad value stops Casita from starting.
 *
 * @param maxAge how long after a real passkey sign-in (Face ID or passcode) the browser may sign itself back in, at
 *               most {@value #MAX_DAYS} days; never extended by use. {@code 0} turns remembered sign-ins off: every
 *               session then starts with Face ID, as before.
 * @param idle   how long a browser may go unused and still sign itself back in; at most {@code maxAge}
 */
@ConfigurationProperties("casita.security.remember")
public record RememberProperties(
        @DefaultValue("30d") Duration maxAge,
        @DefaultValue("14d") Duration idle) {

    static final long MAX_DAYS = 90;

    public RememberProperties {
        if (maxAge == null || maxAge.isNegative() || maxAge.compareTo(Duration.ofDays(MAX_DAYS)) > 0) {
            throw new IllegalArgumentException("casita.security.remember.max-age must be 0 (off) to " + MAX_DAYS + " days, but was " + maxAge);
        }
        if (idle == null || idle.isNegative() || (!maxAge.isZero() && (idle.isZero() || idle.compareTo(maxAge) > 0))) {
            throw new IllegalArgumentException("casita.security.remember.idle must be positive and at most max-age, but was " + idle);
        }
    }

    public boolean enabled() {
        return !maxAge.isZero();
    }
}
