package home.casita.meross;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import home.casita.garage.DoorDevice;

/** Wires the Meross door in when {@code casita.door=meross}. Commands stay off unless enabled. */
@Configuration
@ConditionalOnProperty(name = "casita.door", havingValue = "meross")
class MerossConfig {

    @Bean
    MerossCloudApi merossCloudApi(MerossProperties properties) {
        return new MerossCloudApi(properties.apiBaseUrl(), properties.requestTimeout());
    }

    @Bean
    MerossSession merossSession(MerossCloudApi api, MerossProperties properties, Clock clock) {
        return new MerossSession(api, new HiveMqConnector(), properties, clock);
    }

    @Bean
    DoorDevice merossDoor(MerossSession session, MerossProperties properties) {
        return new MerossDoor(session, properties.deviceUuid(), properties.channel(), properties.commandsEnabled());
    }
}
