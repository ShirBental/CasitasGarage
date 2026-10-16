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

    /** The environment (CASITA_MEROSS_*, bound into the properties) first, then config/meross.local.json.
     *  Startup fails here, with a message naming what's missing, when there is no email or password. */
    @Bean
    MerossLocalCredentials merossLocalCredentials(MerossProperties properties) {
        return MerossLocalCredentials.resolve(properties.email(), properties.password(), properties.deviceUuid(),
                MerossLocalCredentials.FILE, "CASITA_MEROSS_EMAIL and CASITA_MEROSS_PASSWORD");
    }

    @Bean
    MerossSession merossSession(MerossCloudApi api, MerossProperties properties, MerossLocalCredentials credentials, Clock clock) {
        return new MerossSession(api, new HiveMqConnector(), properties.withCredentials(credentials), clock);
    }

    @Bean
    DoorDevice merossDoor(MerossSession session, MerossProperties properties, MerossLocalCredentials credentials) {
        return new MerossDoor(session, credentials.deviceUuid(), properties.channel(), properties.commandsEnabled());
    }
}
