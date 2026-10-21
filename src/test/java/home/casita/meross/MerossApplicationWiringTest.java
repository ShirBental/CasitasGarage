package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import home.casita.garage.DoorDevice;
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
        "casita.meross.password=placeholder"})
class MerossApplicationWiringTest {

    @Autowired
    private DoorDevice door;
    @Autowired
    private GarageService garage;
    @Autowired
    private MerossProperties properties;

    @Test
    void garageServiceDrivesTheMerossDoorForFordWithCommandsOff() {
        assertThat(door).isInstanceOf(MerossDoor.class);
        assertThat(garage).isNotNull();
        assertThat(properties.channel()).isEqualTo(1);
        assertThat(properties.doors()).containsEntry(1, "Ford");
        assertThat(properties.commandMode()).isEqualTo(CommandMode.OFF);
        assertThat(properties.commandChannels()).containsExactly(1);
    }
}
