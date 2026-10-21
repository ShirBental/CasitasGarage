package home.casita.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import home.casita.garage.GarageServiceTest.FakeDoor;
import home.casita.garage.GarageServiceTest.ManualTasks;
import home.casita.garage.GarageServiceTest.MutableClock;

/**
 * Two doors, each run separately: every rule holds per door, and one door's movement, check, hold or
 * switch never reaches the other. Each rule is run with Ford and with Toyota as the door under test.
 * Fake doors only; nothing here is evidence about the real opener.
 */
class GarageDoorsTest {

    private static final Duration TRAVEL = Duration.ofSeconds(10);
    private static final Duration GRACE = Duration.ofSeconds(2);

    private final Map<String, FakeDoor> fakes = Map.of("Ford", new FakeDoor(), "Toyota", new FakeDoor());
    private final InMemoryActivityLog activity = new InMemoryActivityLog();
    private final List<String> pushes = new CopyOnWriteArrayList<>();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T21:00:00Z"));
    private final ManualTasks tasks = new ManualTasks(clock);

    private Garage garage(DoorControl ford, DoorControl toyota) {
        return garage(ford, toyota, new GarageProperties(TRAVEL, GRACE, true));
    }

    private Garage garage(DoorControl ford, DoorControl toyota, GarageProperties properties) {
        return new Garage(new DoorRoster(List.of(
                new DoorRoster.Door("Ford", fakes.get("Ford"), ford),
                new DoorRoster.Door("Toyota", fakes.get("Toyota"), toyota))), activity, pushes::add, tasks, clock, properties);
    }

    private static String other(String door) {
        return door.equals("Ford") ? "Toyota" : "Ford";
    }

    /* ---------- the roster ---------- */

    @Test
    void doorsAreFoundByTheirPathNameInAnyCaseAndFordComesFirst() {
        Garage garage = garage(DoorControl.SIMULATED, DoorControl.SIMULATED);

        assertThat(garage.doors()).extracting(GarageService::name).containsExactly("Ford", "Toyota");
        assertThat(garage.first().name()).isEqualTo("Ford");
        assertThat(garage.door("toyota")).get().extracting(GarageService::name).isEqualTo("Toyota");
        assertThat(garage.door("FORD")).get().extracting(GarageService::name).isEqualTo("Ford");
        assertThat(garage.door("channel3")).isEmpty();
        assertThat(garage.door(null)).isEmpty();
    }

    @Test
    void twoDoorsWithTheSameNameAreRefused() {
        assertThatThrownBy(() -> new DoorRoster(List.of(
                new DoorRoster.Door("Ford", new FakeDoor(), DoorControl.OFF),
                new DoorRoster.Door("ford", new FakeDoor(), DoorControl.OFF))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /* ---------- movement ---------- */

    @ParameterizedTest
    @ValueSource(strings = {"Ford", "Toyota"})
    void oneDoorMovingDoesNotMakeTheOtherBusy(String door) {
        Garage garage = garage(DoorControl.SIMULATED, DoorControl.SIMULATED);

        assertThat(garage.door(door).orElseThrow().open("Shir").outcome()).isEqualTo(Outcome.MOVING);
        assertThat(garage.door(door).orElseThrow().close("Shir").outcome()).isEqualTo(Outcome.BUSY); // its own movement
        assertThat(garage.door(other(door)).orElseThrow().open("Shir").outcome()).isEqualTo(Outcome.MOVING);

        assertThat(fakes.get(door).commands).containsExactly(true);
        assertThat(fakes.get(other(door)).commands).containsExactly(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ford", "Toyota"})
    void eachMovementIsCheckedAgainstItsOwnDoor(String door) {
        Garage garage = garage(DoorControl.SIMULATED, DoorControl.SIMULATED);
        garage.door("Ford").orElseThrow().open("Shir");
        garage.door("Toyota").orElseThrow().open("Shir");
        fakes.get(door).open = true; // only this door rose

        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue();

        assertThat(lastFor(door).outcome()).isEqualTo(Outcome.OPEN_INFERRED);
        assertThat(lastFor(other(door)).outcome()).isEqualTo(Outcome.STUCK);
        assertThat(garage.door(door).orElseThrow().status().held()).isFalse();
        assertThat(garage.door(other(door)).orElseThrow().status().held()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ford", "Toyota"})
    void aDoorJustSeenMovingByTheWallButtonOnlyWaitsItself(String door) {
        Garage garage = garage(DoorControl.SIMULATED, DoorControl.SIMULATED);
        fakes.get(door).open = true;
        fakes.get(other(door)).open = true;
        garage.door(door).orElseThrow().status();
        garage.door(other(door)).orElseThrow().status();
        clock.advance(TRAVEL); // both watched long enough to be settled
        fakes.get(door).open = false;
        garage.door(door).orElseThrow().status();
        fakes.get(door).open = true; // this one just moved again (wall button)

        assertThat(garage.door(door).orElseThrow().close("Shir").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(garage.door(other(door)).orElseThrow().close("Shir").outcome()).isEqualTo(Outcome.MOVING);
    }

    /* ---------- hold and resume ---------- */

    @ParameterizedTest
    @ValueSource(strings = {"Ford", "Toyota"})
    void aStuckDoorHoldsOnlyItselfAndOnlyItsOwnResumeLiftsIt(String door) {
        Garage garage = garage(DoorControl.SIMULATED, DoorControl.SIMULATED);
        GarageService stuck = garage.door(door).orElseThrow();
        GarageService fine = garage.door(other(door)).orElseThrow();

        stuck.open("Shir"); // the sensor never changes
        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue();

        assertThat(stuck.status().held()).isTrue();
        assertThat(fine.status().held()).isFalse();
        assertThat(stuck.open("Shir").outcome()).isEqualTo(Outcome.HELD);
        assertThat(fine.open("Shir").outcome()).isEqualTo(Outcome.MOVING);

        fine.resume("Hubby"); // not held: does nothing, and doesn't lift the other door
        assertThat(stuck.status().held()).isTrue();
        assertThat(activity.recent(50)).noneMatch(e -> e.outcome() == Outcome.RESUMED);

        stuck.resume("Hubby");
        assertThat(stuck.status().held()).isFalse();
        assertThat(lastFor(door)).extracting(ActivityEvent::who, ActivityEvent::outcome).containsExactly("Hubby", Outcome.RESUMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ford", "Toyota"})
    void aLostDoorHoldsOnlyItself(String door) {
        Garage garage = garage(DoorControl.SIMULATED, DoorControl.SIMULATED);
        garage.door(door).orElseThrow().open("Shir");
        fakes.get(door).readFails = true;

        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue();

        assertThat(lastFor(door).outcome()).isEqualTo(Outcome.LOST);
        assertThat(garage.door(other(door)).orElseThrow().open("Shir").outcome()).isEqualTo(Outcome.MOVING);
    }

    /* ---------- the per-door switch ---------- */

    @ParameterizedTest
    @ValueSource(strings = {"Ford", "Toyota"})
    void aDoorSwitchedOffRefusesEveryCommandWithoutReadingOrSending(String door) {
        Garage garage = "Ford".equals(door) ? garage(DoorControl.OFF, DoorControl.LIVE) : garage(DoorControl.LIVE, DoorControl.OFF);
        GarageService off = garage.door(door).orElseThrow();
        fakes.get(door).readFails = true; // a read would turn the outcome into UNKNOWN

        CommandResult open = off.open("Shir");
        CommandResult close = off.close("Shir");

        assertThat(open.outcome()).isEqualTo(Outcome.DISABLED);
        assertThat(open.message()).contains(door).contains("turned off");
        assertThat(close.outcome()).isEqualTo(Outcome.DISABLED);
        assertThat(fakes.get(door).commands).isEmpty();
        assertThat(activity.recent(10)).extracting(ActivityEvent::door, ActivityEvent::action, ActivityEvent::outcome)
                .containsExactly(tuple(door, Action.CLOSE, Outcome.DISABLED),
                        tuple(door, Action.OPEN, Outcome.DISABLED));
        assertThat(pushes).isEmpty();

        // The other door, switched on, is untouched by it.
        assertThat(garage.door(other(door)).orElseThrow().open("Shir").outcome()).isEqualTo(Outcome.MOVING);
        assertThat(fakes.get(other(door)).commands).containsExactly(true);
    }

    @Test
    void eachDoorKeepsTheControlItWasGiven() {
        Garage garage = garage(DoorControl.LIVE, DoorControl.OFF);

        assertThat(garage.door("ford").orElseThrow().control()).isEqualTo(DoorControl.LIVE);
        assertThat(garage.door("toyota").orElseThrow().control()).isEqualTo(DoorControl.OFF);
        assertThat(DoorControl.OFF.live()).isFalse();
        assertThat(DoorControl.DRY_RUN.live()).isFalse();
        assertThat(DoorControl.SIMULATED.live()).isFalse();
        assertThat(DoorControl.LIVE.live()).isTrue();
        assertThat(DoorControl.DRY_RUN.id()).isEqualTo("dry-run");
    }

    @Test
    void closingSwitchedOffAppliesToEveryDoor() {
        Garage garage = garage(DoorControl.SIMULATED, DoorControl.SIMULATED, new GarageProperties(TRAVEL, GRACE, false));
        for (FakeDoor fake : fakes.values()) fake.open = true;

        for (GarageService door : garage.doors()) {
            assertThat(door.close("Shir").outcome()).isEqualTo(Outcome.DISABLED);
        }
        assertThat(fakes.values()).allMatch(fake -> fake.commands.isEmpty());
    }

    /* ---------- travel time, activity and notifications ---------- */

    @Test
    void aDoorWithItsOwnTravelTimeIsCheckedAndSettledOnIt() {
        Garage garage = garage(DoorControl.SIMULATED, DoorControl.SIMULATED,
                new GarageProperties(TRAVEL, GRACE, true, Map.of("Toyota", Duration.ofSeconds(20))));
        garage.door("Ford").orElseThrow().open("Shir");
        garage.door("Toyota").orElseThrow().open("Shir");
        fakes.get("Ford").open = true;
        fakes.get("Toyota").open = true;

        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue();
        assertThat(lastFor("Ford").outcome()).isEqualTo(Outcome.OPEN_INFERRED);
        assertThat(lastFor("Toyota").outcome()).isEqualTo(Outcome.MOVING); // still within its 20 s
        assertThat(garage.door("Toyota").orElseThrow().status().door()).isEqualTo(DoorState.OPENING);

        clock.advance(Duration.ofSeconds(10));
        tasks.runDue();
        assertThat(lastFor("Toyota").outcome()).isEqualTo(Outcome.OPEN_INFERRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ford", "Toyota"})
    void activityAndNotificationsNameTheDoor(String door) {
        Garage garage = garage(DoorControl.SIMULATED, DoorControl.SIMULATED);
        fakes.get(door).open = true;
        garage.door(door).orElseThrow().status();
        clock.advance(TRAVEL);

        garage.door(door).orElseThrow().close("Shir");
        fakes.get(door).open = false;
        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue();

        assertThat(activity.recent(10)).extracting(ActivityEvent::door, ActivityEvent::who, ActivityEvent::outcome).containsExactly(
                tuple(door, GarageService.CASITA, Outcome.CLOSED_CONFIRMED),
                tuple(door, "Shir", Outcome.MOVING));
        assertThat(pushes).containsExactly("Closing the " + door + " door for Shir", "The " + door + " door is closed. All tucked in.");
    }

    private ActivityEvent lastFor(String door) {
        return activity.recent(50).stream().filter(e -> e.door().equals(door)).findFirst().orElseThrow();
    }
}
