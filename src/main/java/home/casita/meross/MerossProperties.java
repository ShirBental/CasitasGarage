package home.casita.meross;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Meross cloud settings. The email and password move to Parameter Store in a later step;
 * until then they come from the environment ({@code CASITA_MEROSS_EMAIL},
 * {@code CASITA_MEROSS_PASSWORD}) or, for local development, the git-ignored
 * {@code config/meross.local.json} ({@link MerossLocalCredentials}). Never from a committed file.
 *
 * @param apiBaseUrl      Meross API for our region; sign-in redirects if it's the wrong one
 * @param email           Meross account email
 * @param password        Meross account password
 * @param deviceUuid      the garage opener's uuid; found automatically when there's only one
 * @param channel         the opener's door channel (0 for a single-door MSG100)
 * @param requestTimeout  how long to wait for Meross or the opener to answer
 * @param commandsEnabled whether Casita may send open/close to the real door. Off until the
 *                        hardware prerequisites in docs/DESIGN.md are checked.
 */
@ConfigurationProperties("casita.meross")
public record MerossProperties(
        @DefaultValue("https://iotx-us.meross.com") String apiBaseUrl,
        String email,
        String password,
        String deviceUuid,
        @DefaultValue("0") int channel,
        @DefaultValue("8s") Duration requestTimeout,
        @DefaultValue("false") boolean commandsEnabled) {

    public MerossProperties {
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("casita.meross.request-timeout must be positive, but was " + requestTimeout);
        }
    }

    /** The same settings with the account and device from {@code credentials}. Everything else, including
     *  {@code commandsEnabled}, stays as configured. */
    MerossProperties withCredentials(MerossLocalCredentials credentials) {
        return new MerossProperties(apiBaseUrl, credentials.email(), credentials.password(), credentials.deviceUuid(), channel,
                requestTimeout, commandsEnabled);
    }

    @Override
    public String toString() {
        return "MerossProperties[apiBaseUrl=" + apiBaseUrl + ", deviceUuid=" + deviceUuid + ", channel=" + channel
                + ", requestTimeout=" + requestTimeout + ", commandsEnabled=" + commandsEnabled + "]";
    }
}
