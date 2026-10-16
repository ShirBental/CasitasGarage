package home.casita.garage;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The practice garage ({@code casita.door=simulated}, the default): Ford is the simulated door, Toyota a
 * pretend door that always reads closed and refuses every command. Nothing reaches Meross.
 */
@Configuration
@ConditionalOnProperty(name = "casita.door", havingValue = "simulated", matchIfMissing = true)
class SimulatedRosterConfig {

    @Bean
    DoorRoster doorRoster() {
        DoorDevice toyota = new DoorDevice() {
            @Override
            public Reading read() {
                return new Reading(false, null);
            }

            @Override
            public void command(boolean open) throws DoorException {
                throw new DoorCommandRejectedException("Toyota is read-only; nothing was sent");
            }
        };
        return new DoorRoster("Ford", "simulated", List.of(new DoorRoster.Watched("Toyota", toyota)));
    }
}
