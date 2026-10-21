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
import home.casita.garage.GarageProperties;

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

    /** Ford's and Toyota's own timings, as live needs them. Test values, not measurements. */
    private static final GarageProperties MEASURED = new GarageProperties(Duration.ofSeconds(10), Duration.ofSeconds(2), true,
            Map.of("Ford", Duration.ofSeconds(30), "Toyota", Duration.ofSeconds(30), "Garage", Duration.ofSeconds(30)),
            Map.of("Ford", Duration.ofSeconds(5), "Toyota", Duration.ofSeconds(5), "Garage", Duration.ofSeconds(5)));

    private static DoorRoster roster(MerossProperties props) {
        return roster(props, MEASURED);
    }

    private static DoorRoster roster(MerossProperties props, GarageProperties garage) {
        MerossSession session = new MerossSession(new MerossCloudApi(props.apiBaseUrl(), props.requestTimeout()), new HiveMqConnector(),
                props.withCredentials(CREDENTIALS), Clock.systemUTC());
        return new MerossConfig().doorRoster(session, props, CREDENTIALS, garage);
    }

    @Test
    void liveRefusesToStartForADoorWithoutItsOwnMeasuredTimings() {
        // H1/H3: the defaults (10 s for every door, no lag) are for practice doors.
        GarageProperties defaults = new GarageProperties(Duration.ofSeconds(10), Duration.ofSeconds(2), true);
        assertThatThrownBy(() -> roster(props(null, CommandMode.LIVE, DOORS, Set.of(1)), defaults))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Ford")
                .hasMessageContaining("casita.garage.travel-times.Ford").hasMessageContaining("casita.garage.sensor-lags.Ford");
        // A travel time without a lag (or the reverse) isn't enough.
        GarageProperties travelOnly = new GarageProperties(Duration.ofSeconds(10), Duration.ofSeconds(2), true,
                Map.of("Ford", Duration.ofSeconds(30)), Map.of());
        assertThatThrownBy(() -> roster(props(null, CommandMode.LIVE, DOORS, Set.of(1)), travelOnly))
                .isInstanceOf(IllegalStateException.class);
        GarageProperties lagOnly = new GarageProperties(Duration.ofSeconds(10), Duration.ofSeconds(2), true,
                Map.of(), Map.of("Ford", Duration.ofSeconds(5)));
        assertThatThrownBy(() -> roster(props(null, CommandMode.LIVE, DOORS, Set.of(1)), lagOnly))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyLiveDoorsNeedMeasuredTimings() {
        GarageProperties defaults = new GarageProperties(Duration.ofSeconds(10), Duration.ofSeconds(2), true);
        // Off and dry run send nothing, and Toyota (not in command-channels) is off: the defaults are fine.
        assertThat(control(roster(props(null, CommandMode.OFF, DOORS, Set.of(1)), defaults), "Ford")).isEqualTo(DoorControl.OFF);
        assertThat(control(roster(props(null, CommandMode.DRY_RUN, DOORS, Set.of(1)), defaults), "Ford")).isEqualTo(DoorControl.DRY_RUN);
        GarageProperties fordOnly = new GarageProperties(Duration.ofSeconds(10), Duration.ofSeconds(2), true,
                Map.of("Ford", Duration.ofSeconds(30)), Map.of("Ford", Duration.ofSeconds(5)));
        DoorRoster roster = roster(props(null, CommandMode.LIVE, DOORS, Set.of(1)), fordOnly);
        assertThat(control(roster, "Ford")).isEqualTo(DoorControl.LIVE);
        assertThat(control(roster, "Toyota")).isEqualTo(DoorControl.OFF);
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
