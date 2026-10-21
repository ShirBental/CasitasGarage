package home.casita.meross;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import home.casita.garage.DoorControl;
import home.casita.garage.DoorRoster;
import home.casita.garage.GarageProperties;

/** Wires the Meross doors in when {@code casita.door=meross}. Commands stay off unless command-mode and
 *  command-channels both say otherwise, per door. */
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

    /** Meross's cloud (the default), or the opener itself on the home network (casita.meross.transport=local). */
    @Bean
    MerossTransport merossTransport(MerossCloudApi api, MerossProperties properties, MerossLinkProperties link,
                                    MerossLocalCredentials credentials, Clock clock) {
        if (link.transport() == MerossLinkProperties.Transport.LOCAL) {
            return new MerossLocalTransport(api, credentials.email(), credentials.password(), link.localUrl(),
                    properties.requestTimeout(), clock);
        }
        return new MerossSession(api, new HiveMqConnector(), properties.withCredentials(credentials), clock);
    }

    /**
     * Every named door, in channel order (1 Ford, 2 Toyota), each its own {@link MerossDoor} on its own channel.
     * A door outside {@code command-channels} is {@link MerossDoor#readOnly}: off whatever the command mode, and
     * reported as off, so the app never offers it live. Channel 3 isn't named, so it isn't shown or driven.
     * With no doors named (an MSG100), the one door is {@code channel}, called "Garage".
     */
    @Bean
    DoorRoster doorRoster(MerossTransport session, MerossProperties properties, MerossLocalCredentials credentials,
                          GarageProperties garage) {
        Map<Integer, String> named = properties.doors().isEmpty()
                ? Collections.singletonMap(properties.channel(), "Garage")
                : new TreeMap<>(properties.doors());
        List<DoorRoster.Door> doors = new ArrayList<>();
        named.forEach((channel, name) -> {
            CommandMode mode = properties.commandModeFor(channel);
            if (mode == CommandMode.LIVE && !garage.measured(name)) {
                // H1/H3: the defaults (one travel time for every door, no sensor lag) are for practice doors. A real
                // door moves only on timings measured on it, so a close can't reach a door that is still moving.
                throw new IllegalStateException("Live commands to " + name + " need its measured timings, "
                        + "casita.garage.travel-times." + name + " and casita.garage.sensor-lags." + name
                        + " (docs/MEROSS.md); Casita won't drive a real door on the defaults");
            }
            MerossDoor door = mode == CommandMode.OFF
                    ? MerossDoor.readOnly(session, credentials.deviceUuid(), channel)
                    : new MerossDoor(session, credentials.deviceUuid(), channel, mode, properties.commandChannels());
            doors.add(new DoorRoster.Door(name, door, control(mode)));
        });
        return new DoorRoster(doors);
    }

    static DoorControl control(CommandMode mode) {
        return switch (mode) {
            case OFF -> DoorControl.OFF;
            case DRY_RUN -> DoorControl.DRY_RUN;
            case LIVE -> DoorControl.LIVE;
        };
    }
}
