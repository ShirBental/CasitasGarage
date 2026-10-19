package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import home.casita.garage.DoorCommandRejectedException;
import home.casita.garage.DoorControl;
import home.casita.garage.DoorRoster;

/**
 * Which door gets which control, per door, from command-mode and the per-door switch (command-channels).
 * Toyota (channel 2) is off unless it is listed, whatever the mode. Nothing here connects to Meross: off doors
 * refuse before looking for the opener.
 */
class MerossRosterTest {

    private static final Map<Integer, String> DOORS = Map.of(1, "Ford", 2, "Toyota");
    private static final MerossLocalCredentials CREDENTIALS = new MerossLocalCredentials("placeholder@example.com", "placeholder", null);

    private static MerossProperties props(Integer channel, CommandMode mode, Map<Integer, String> doors, Set<Integer> commandChannels) {
        return new MerossProperties("http://127.0.0.1:1", null, null, null, channel, Duration.ofSeconds(1), mode, doors, commandChannels);
    }

    private static DoorRoster roster(MerossProperties props) {
        MerossSession session = new MerossSession(new MerossCloudApi(props.apiBaseUrl(), props.requestTimeout()), new HiveMqConnector(),
                props.withCredentials(CREDENTIALS), Clock.systemUTC());
        return new MerossConfig().doorRoster(session, props, CREDENTIALS);
    }

    private static DoorControl control(DoorRoster roster, String name) {
        return roster.doors().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow().control();
    }

    @Test
    void bothDoorsAreShownInChannelOrder() {
        DoorRoster roster = roster(props(null, CommandMode.OFF, DOORS, Set.of(1)));

        assertThat(roster.doors()).extracting(DoorRoster.Door::name).containsExactly("Ford", "Toyota");
    }

    @Test
    void liveWithFordAloneSwitchedOnLeavesToyotaOff() {
        DoorRoster roster = roster(props(null, CommandMode.LIVE, DOORS, Set.of(1)));

        assertThat(control(roster, "Ford")).isEqualTo(DoorControl.LIVE);
        assertThat(control(roster, "Toyota")).isEqualTo(DoorControl.OFF);
        // Toyota's device is read-only as well: refused before the opener is even looked up.
        assertThatThrownBy(() -> roster.doors().get(1).device().command(true))
                .isInstanceOf(DoorCommandRejectedException.class).hasMessageContaining("command-mode");
    }

    @Test
    void toyotaIsLiveOnlyWhenModeIsLiveAndItsChannelIsSwitchedOn() {
        assertThat(control(roster(props(null, CommandMode.LIVE, DOORS, Set.of(1, 2))), "Toyota")).isEqualTo(DoorControl.LIVE);
        assertThat(control(roster(props(null, CommandMode.DRY_RUN, DOORS, Set.of(1, 2))), "Toyota")).isEqualTo(DoorControl.DRY_RUN);
        assertThat(control(roster(props(null, CommandMode.OFF, DOORS, Set.of(1, 2))), "Toyota")).isEqualTo(DoorControl.OFF);
        assertThat(control(roster(props(null, CommandMode.LIVE, DOORS, Set.of())), "Toyota")).isEqualTo(DoorControl.OFF);
    }

    @Test
    void toyotaAloneCanBeSwitchedOnWithoutFord() {
        DoorRoster roster = roster(props(null, CommandMode.DRY_RUN, DOORS, Set.of(2)));

        assertThat(control(roster, "Ford")).isEqualTo(DoorControl.OFF);
        assertThat(control(roster, "Toyota")).isEqualTo(DoorControl.DRY_RUN);
    }

    @Test
    void withNoDoorsNamedTheOneDoorIsTheConfiguredChannel() {
        DoorRoster roster = roster(props(0, CommandMode.LIVE, Map.of(), Set.of(0)));

        assertThat(roster.doors()).extracting(DoorRoster.Door::name).containsExactly("Garage");
        assertThat(control(roster, "Garage")).isEqualTo(DoorControl.LIVE);
    }
}
