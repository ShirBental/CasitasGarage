package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import home.casita.garage.DoorDevice;
import home.casita.garage.DoorRoster;
import home.casita.garage.GarageService;

/**
 * The whole app with the real door selected for Ford: the Meross door sits behind GarageService, with
 * commands off and only Ford allowed, as committed. Nothing connects at startup (the session signs in on
 * the first read), so this runs offline. Placeholder credentials keep it independent of local files.
 */
@SpringBootTest(properties = {
        "casita.door=meross",
        "casita.meross.channel=1",
        "casita.meross.email=placeholder@example.com",
        "casita.meross.password=placeholder",
        "casita.security.data-dir=target/test-data/wiring"})
class MerossApplicationWiringTest {

    @Autowired
    private DoorDevice door;
    @Autowired
    private GarageService garage;
    @Autowired
    private MerossProperties properties;
    @Autowired
    private DoorRoster roster;

    @Test
    void garageServiceDrivesTheMerossDoorForFordWithCommandsOff() {
        assertThat(door).isInstanceOf(MerossDoor.class);
        assertThat(garage).isNotNull();
        assertThat(properties.channel()).isEqualTo(1);
        assertThat(properties.doors()).containsEntry(1, "Ford");
        assertThat(properties.commandMode()).isEqualTo(CommandMode.OFF);
        assertThat(properties.commandChannels()).containsExactly(1);
    }

    @Test
    void theAppShowsFordControlledAndToyotaReadOnlyAndNeverChannel3() throws Exception {
        assertThat(roster.controlled()).isEqualTo("Ford");
        assertThat(roster.control()).isEqualTo("off");
        assertThat(roster.watched()).extracting(DoorRoster.Watched::name).containsExactly("Toyota");
        DoorDevice toyota = roster.watched().get(0).door();
        assertThat(toyota).isInstanceOf(MerossDoor.class);
        // Read-only whatever the command mode: refused before it even looks for the opener.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> toyota.command(true))
                .isInstanceOf(home.casita.garage.DoorCommandRejectedException.class).hasMessageContaining("command-mode");
    }
}
