package home.casita.meross;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Meross cloud settings. The email and password move to Parameter Store in the next step;
 * until then they come from the environment ({@code CASITA_MEROSS_EMAIL},
 * {@code CASITA_MEROSS_PASSWORD}), never from a committed file.
 *
 * @param apiBaseUrl     Meross API for our region; login redirects if it's the wrong one
 * @param email          Meross account email
 * @param password       Meross account password
 * @param deviceUuid     the garage opener's uuid; found automatically when there's only one
 * @param channel        the opener's door channel (0 for a single-door MSG100)
 * @param requestTimeout how long to wait for the opener to answer
 */
@ConfigurationProperties("casita.meross")
public record MerossProperties(
        @DefaultValue("https://iotx-us.meross.com") String apiBaseUrl,
        String email,
        String password,
        String deviceUuid,
        @DefaultValue("0") int channel,
        @DefaultValue("8s") Duration requestTimeout) {}
