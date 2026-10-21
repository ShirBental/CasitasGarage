package home.casita.garage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Checked when bound, so a bad value stops Casita from starting instead of quietly
 * breaking the safety rules.
 *
 * @param travelTime       how long the door takes to fully open or close; must be positive
 * @param verifyGrace      extra wait after the travel time before checking the sensor; zero or more
 * @param allowRemoteClose whether the app may close the door
 */
@ConfigurationProperties("casita.garage")
public record GarageProperties(
        @DefaultValue("10s") Duration travelTime,
        @DefaultValue("2s") Duration verifyGrace,
        @DefaultValue("true") boolean allowRemoteClose) {

    public GarageProperties {
        if (travelTime == null || travelTime.isZero() || travelTime.isNegative()) {
            throw new IllegalArgumentException("casita.garage.travel-time must be positive "
                    + "(how long the door takes to fully open or close), but was " + travelTime);
        }
        if (verifyGrace == null || verifyGrace.isNegative()) {
            throw new IllegalArgumentException("casita.garage.verify-grace must be zero or positive, but was "
                    + verifyGrace);
        }
    }
}
