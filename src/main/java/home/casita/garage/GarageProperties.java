package home.casita.garage;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Checked when bound, so a bad value stops Casita from starting instead of quietly
 * breaking the safety rules.
 *
 * @param travelTime       how long a door takes to fully open or close; must be positive
 * @param verifyGrace      extra wait after the travel time before checking the sensor; zero or more
 * @param allowRemoteClose whether the app may close a door (every door)
 * @param travelTimes      a door's own travel time, by name (e.g. Toyota: 15s), where it differs from
 *                         {@code travelTime}; each must be positive
 */
@ConfigurationProperties("casita.garage")
public record GarageProperties(
        @DefaultValue("10s") Duration travelTime,
        @DefaultValue("2s") Duration verifyGrace,
        @DefaultValue("true") boolean allowRemoteClose,
        Map<String, Duration> travelTimes) {

    @ConstructorBinding
    public GarageProperties {
        if (travelTime == null || travelTime.isZero() || travelTime.isNegative()) {
            throw new IllegalArgumentException("casita.garage.travel-time must be positive "
                    + "(how long the door takes to fully open or close), but was " + travelTime);
        }
        if (verifyGrace == null || verifyGrace.isNegative()) {
            throw new IllegalArgumentException("casita.garage.verify-grace must be zero or positive, but was "
                    + verifyGrace);
        }
        TreeMap<String, Duration> byKey = new TreeMap<>();
        if (travelTimes != null) {
            travelTimes.forEach((door, time) -> {
                if (time == null || time.isZero() || time.isNegative()) {
                    throw new IllegalArgumentException("casita.garage.travel-times." + door + " must be positive, but was " + time);
                }
                byKey.put(DoorRoster.Door.key(door), time);
            });
        }
        travelTimes = Map.copyOf(byKey);
    }

    public GarageProperties(Duration travelTime, Duration verifyGrace, boolean allowRemoteClose) {
        this(travelTime, verifyGrace, allowRemoteClose, Map.of());
    }

    /** The named door's travel time: its own when configured, otherwise {@code travelTime}. */
    public Duration travelTime(String door) {
        return travelTimes.getOrDefault(DoorRoster.Door.key(door), travelTime);
    }
}
