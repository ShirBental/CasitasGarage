package home.casita.meross;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import home.casita.garage.DoorDevice;
import home.casita.garage.DoorRoster;

/** Wires the Meross door in when {@code casita.door=meross}. Commands stay off unless command-mode says otherwise. */
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

    /**
     * The doors the app shows: the configured channel, driven by GarageService, and every other named door
     * (Toyota) read-only: {@link MerossDoor#readOnly}, so commands to it are off whatever the command mode.
     */
    @Bean
    DoorRoster doorRoster(MerossSession session, MerossProperties properties, MerossLocalCredentials credentials) {
        Integer channel = properties.channel();
        String controlled = properties.doors().getOrDefault(channel == null ? 0 : channel, "Garage");
        List<DoorRoster.Watched> watched = new TreeMap<>(properties.doors()).entrySet().stream()
                .filter(door -> !door.getKey().equals(channel))
                .map(door -> new DoorRoster.Watched(door.getValue(), MerossDoor.readOnly(session, credentials.deviceUuid(), door.getKey())))
                .toList();
        return new DoorRoster(controlled, properties.commandMode().name().toLowerCase(Locale.ROOT).replace('_', '-'), watched);
    }

    /** Several doors configured but none chosen: refuse to start rather than guess (an MSG200 has no door 0). */
    @Bean
    DoorDevice merossDoor(MerossSession session, MerossProperties properties, MerossLocalCredentials credentials) {
        if (properties.channel() == null && properties.doors().size() > 1) {
            throw new IllegalStateException("Several doors are configured (casita.meross.doors " + new java.util.TreeMap<>(properties.doors())
                    + "); set casita.meross.channel to the one this Casita reads");
        }
        return new MerossDoor(session, credentials.deviceUuid(), properties.channel(), properties.commandMode(),
                properties.commandChannels());
    }
}
