package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import home.casita.garage.DoorControl;
import home.casita.garage.DoorRoster;
import home.casita.garage.Garage;
import home.casita.garage.Outcome;

/**
 * The whole app with the real doors selected, as committed: Ford and Toyota each behind their own
 * GarageService, with commands off for both. Nothing connects at startup (the session signs in on the first
 * read), and nothing here reads a door, so this runs offline. Placeholder credentials keep it independent of
 * local files.
 */
@SpringBootTest(properties = {
        "casita.door=meross",
        "casita.meross.email=placeholder@example.com",
        "casita.meross.password=placeholder",
        "casita.security.data-dir=target/test-data/wiring"})
class MerossApplicationWiringTest {

    @Autowired
    private Garage garage;
    @Autowired
    private MerossProperties properties;
    @Autowired
    private DoorRoster roster;

    @Test
    void asCommittedCommandsAreOffAndOnlyFordIsSwitchedOn() {
        assertThat(properties.doors()).containsEntry(1, "Ford").containsEntry(2, "Toyota");
        assertThat(properties.commandMode()).isEqualTo(CommandMode.OFF);
        assertThat(properties.commandChannels()).containsExactly(1);
    }

    @Test
    void bothDoorsAreDrivenSeparatelyEachOnItsOwnChannelAndNeverChannel3() {
        assertThat(roster.doors()).extracting(DoorRoster.Door::name).containsExactly("Ford", "Toyota");
        assertThat(roster.doors()).allMatch(d -> d.device() instanceof MerossDoor);
        assertThat(roster.doors()).extracting(DoorRoster.Door::control).containsExactly(DoorControl.OFF, DoorControl.OFF);
        assertThat(garage.doors()).extracting(g -> g.name()).containsExactly("Ford", "Toyota");
    }

    @Test
    void commandsToEitherDoorAreRefusedBeforeAnythingIsReadOrSent() {
        // OFF is refused before the door is read, so this needs no network.
        assertThat(garage.door("ford").orElseThrow().open("Shir").outcome()).isEqualTo(Outcome.DISABLED);
        assertThat(garage.door("toyota").orElseThrow().open("Shir").outcome()).isEqualTo(Outcome.DISABLED);
        assertThat(garage.door("toyota").orElseThrow().close("Shir").outcome()).isEqualTo(Outcome.DISABLED);
    }
}
