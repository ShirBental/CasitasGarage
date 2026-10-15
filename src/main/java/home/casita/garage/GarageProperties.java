package home.casita.garage;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param travelTime       how long the door takes to fully open or close
 * @param verifyGrace      extra wait after the travel time before checking the sensor
 * @param allowRemoteClose whether the app may close the door
 */
@ConfigurationProperties("casita.garage")
public record GarageProperties(
        @DefaultValue("10s") Duration travelTime,
        @DefaultValue("2s") Duration verifyGrace,
        @DefaultValue("true") boolean allowRemoteClose) {}
