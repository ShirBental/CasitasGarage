package home.casita.garage;

import java.time.Clock;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The practice garage ({@code casita.door=simulated}, the default): Ford and Toyota are two separate
 * simulated doors, so the app can show both controls. Nothing reaches Meross.
 */
@Configuration
@ConditionalOnProperty(name = "casita.door", havingValue = "simulated", matchIfMissing = true)
class SimulatedRosterConfig {

    @Bean(destroyMethod = "destroy")
    SimulatedDoor simulatedFord(Clock clock, GarageProperties properties) {
        return new SimulatedDoor("Ford", clock, properties.travelTime("Ford"));
    }

    @Bean(destroyMethod = "destroy")
    SimulatedDoor simulatedToyota(Clock clock, GarageProperties properties) {
        return new SimulatedDoor("Toyota", clock, properties.travelTime("Toyota"));
    }

    @Bean
    DoorRoster doorRoster(SimulatedDoor simulatedFord, SimulatedDoor simulatedToyota) {
        return new DoorRoster(List.of(
                new DoorRoster.Door("Ford", simulatedFord, DoorControl.SIMULATED),
                new DoorRoster.Door("Toyota", simulatedToyota, DoorControl.SIMULATED)));
    }
}
