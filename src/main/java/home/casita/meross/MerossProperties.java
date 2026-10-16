package home.casita.meross;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

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
 * @param channel         the door this Casita reads, by channel. No default: an MSG200 has several doors
 *                        (channels 1-3) and must be told which; an MSG100 uses 0 when it's unset.
 * @param requestTimeout  how long to wait for Meross or the opener to answer
 * @param commandsEnabled whether Casita may send open/close to the real door. Off until the
 *                        hardware prerequisites in docs/DESIGN.md are checked.
 * @param doors           the opener's doors by channel, with a name each (e.g. 1: Ford, 2: Toyota).
 *                        When given, {@code channel} must be one of them.
 */
@ConfigurationProperties("casita.meross")
public record MerossProperties(
        @DefaultValue("https://iotx-us.meross.com") String apiBaseUrl,
        String email,
        String password,
        String deviceUuid,
        Integer channel,
        @DefaultValue("8s") Duration requestTimeout,
        @DefaultValue("false") boolean commandsEnabled,
        Map<Integer, String> doors) {

    public MerossProperties {
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("casita.meross.request-timeout must be positive, but was " + requestTimeout);
        }
        doors = doors == null ? Map.of() : Map.copyOf(doors);
        if (channel != null && !doors.isEmpty() && !doors.containsKey(channel)) {
            throw new IllegalArgumentException("casita.meross.channel " + channel + " isn't one of casita.meross.doors " + new TreeMap<>(doors));
        }
    }

    /** The same settings with the account and device from {@code credentials}. Everything else, including
     *  {@code commandsEnabled}, stays as configured. */
    MerossProperties withCredentials(MerossLocalCredentials credentials) {
        return new MerossProperties(apiBaseUrl, credentials.email(), credentials.password(), credentials.deviceUuid(), channel,
                requestTimeout, commandsEnabled, doors);
    }

    @Override
    public String toString() {
        return "MerossProperties[apiBaseUrl=" + apiBaseUrl + ", deviceUuid=" + MerossDeviceInfo.masked(deviceUuid) + ", channel=" + channel
                + ", doors=" + new TreeMap<>(doors) + ", requestTimeout=" + requestTimeout + ", commandsEnabled=" + commandsEnabled + "]";
    }
}
