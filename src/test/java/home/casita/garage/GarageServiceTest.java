package home.casita.garage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GarageServiceTest {

    private static final Duration TRAVEL = Duration.ofSeconds(10);
    private static final Duration GRACE = Duration.ofSeconds(2);

    private FakeDoor door;
    private InMemoryActivityLog activity;
    private List<String> pushes;
    private MutableClock clock;
    private ManualTasks tasks;
    private GarageService garage;

    @BeforeEach
    void setUp() {
        newGarage(true);
    }

    private void newGarage(boolean allowRemoteClose) {
        door = new FakeDoor();
        activity = new InMemoryActivityLog();
        pushes = new ArrayList<>();
        clock = new MutableClock(Instant.parse("2026-10-15T21:00:00Z"));
        tasks = new ManualTasks(clock);
        garage = new GarageService(door, activity, pushes::add, tasks, clock,
                new GarageProperties(TRAVEL, GRACE, allowRemoteClose));
    }

    @Test
    void opensAClosedDoor() {
        CommandResult result = garage.open("Shir");

        assertThat(result.outcome()).isEqualTo(Outcome.MOVING);
        assertThat(door.commands).containsExactly(true);
        assertThat(garage.status().door()).isEqualTo(DoorState.OPENING);
        assertThat(pushes).containsExactly("Opening the garage for Shir");
        assertLatest("Shir", Action.OPEN, Outcome.MOVING);
    }

    @Test
    void showsOpeningUntilTheTravelTimeEvenThoughTheSensorTripsEarly() {
        garage.open("Shir");
        door.open = true; // MSG100 sensor reads open as soon as the door lifts

        clock.advance(Duration.ofSeconds(9));
        assertThat(garage.status().door()).isEqualTo(DoorState.OPENING);

        clock.advance(Duration.ofSeconds(1));
        assertThat(garage.status().door()).isEqualTo(DoorState.OPEN);
    }

    @Test
    void showsClosingUntilTheSensorReadsClosed() {
        door.open = true;
        garage.close("Shir");
        assertThat(garage.status().door()).isEqualTo(DoorState.CLOSING);

        clock.advance(Duration.ofSeconds(8));
        door.open = false;
        assertThat(garage.status().door()).isEqualTo(DoorState.CLOSED);
    }

    @Test
    void ignoresCommandsWhileTheDoorIsMoving() {
        garage.open("Shir");
        door.open = true;

        CommandResult result = garage.close("Hubby");

        assertThat(result.outcome()).isEqualTo(Outcome.BUSY);
        assertThat(door.commands).containsExactly(true);
    }

    @Test
    void openingAnOpenDoorDoesNothing() {
        door.open = true;

        CommandResult result = garage.open("Shir");

        assertThat(result.outcome()).isEqualTo(Outcome.ALREADY);
        assertThat(door.commands).isEmpty();
        assertThat(pushes).isEmpty();
    }

    @Test
    void closingAClosedDoorDoesNothing() {
        CommandResult result = garage.close("Shir");

        assertThat(result.outcome()).isEqualTo(Outcome.ALREADY);
        assertThat(door.commands).isEmpty();
    }

    @Test
    void refusesWhenTheDoorCantBeRead() {
        door.readFails = true;

        CommandResult result = garage.open("Shir");

        assertThat(result.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(result.status().door()).isEqualTo(DoorState.UNKNOWN);
        assertThat(door.commands).isEmpty();
        assertLatest("Shir", Action.OPEN, Outcome.UNKNOWN);
    }

    @Test
    void aFailedCommandMeansNothingMoved() {
        door.commandFails = true;

        CommandResult result = garage.open("Shir");

        assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(garage.status().door()).isEqualTo(DoorState.CLOSED);
        assertThat(pushes).isEmpty();
    }

    @Test
    void confirmsWhenTheDoorFinishesClosing() {
        door.open = true;
        garage.close("Shir");
        clock.advance(Duration.ofSeconds(9));
        door.open = false;

        clock.advance(GRACE.plus(Duration.ofSeconds(1)));
        tasks.runDue();

        assertThat(pushes).containsExactly("Closing the garage for Shir", "The garage is closed. All tucked in.");
    }

    @Test
    void warnsWhenTheDoorDoesntClose() {
        door.open = true;
        garage.close("Shir");
        // Something is in the way: the sensor never reads closed.

        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue();

        assertThat(lastPush()).contains("didn't close");
        assertLatest(GarageService.CASITA, Action.CLOSE, Outcome.STUCK);
        assertThat(garage.status().door()).isEqualTo(DoorState.OPEN);
    }

    @Test
    void warnsWhenTheDoorDoesntOpen() {
        garage.open("Shir");

        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue();

        assertThat(lastPush()).contains("didn't open");
        assertLatest(GarageService.CASITA, Action.OPEN, Outcome.STUCK);
    }

    @Test
    void warnsWhenTheDoorCantBeReadAfterMoving() {
        garage.open("Shir");
        door.readFails = true;

        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue();

        assertLatest(GarageService.CASITA, Action.OPEN, Outcome.LOST);
        assertThat(lastPush()).contains("lost sight");
    }

    @Test
    void anOlderCheckIsIgnoredOnceANewerCommandIsSent() {
        garage.open("Shir");
        door.open = true;
        clock.advance(TRAVEL); // done moving, but the open check hasn't run yet
        garage.close("Hubby");

        clock.advance(GRACE);
        tasks.runDue(); // the open check: must not report anything about the close

        assertLatest("Hubby", Action.CLOSE, Outcome.MOVING);
    }

    @Test
    void closingCanBeSwitchedOff() {
        newGarage(false);
        door.open = true;

        CommandResult result = garage.close("Shir");

        assertThat(result.outcome()).isEqualTo(Outcome.DISABLED);
        assertThat(result.status().canClose()).isFalse();
        assertThat(door.commands).isEmpty();
    }

    @Test
    void canCloseOnlyWhenOpenAndAllowed() {
        assertThat(garage.status().canClose()).isFalse();
        door.open = true;
        assertThat(garage.status().canClose()).isTrue();
    }

    private void assertLatest(String who, Action action, Outcome outcome) {
        ActivityEvent latest = activity.recent(1).get(0);
        assertThat(List.of(latest.who(), latest.action(), latest.outcome())).containsExactly(who, action, outcome);
    }

    private String lastPush() {
        return pushes.get(pushes.size() - 1);
    }

    /* ---------- test doubles ---------- */

    /** A door the test moves by hand: commands are recorded, the sensor changes only when the test says. */
    static final class FakeDoor implements DoorDevice {
        boolean open;
        boolean readFails;
        boolean commandFails;
        final List<Boolean> commands = new ArrayList<>();

        @Override
        public Reading read() throws DoorException {
            if (readFails) throw new DoorException("offline");
            return new Reading(open, null);
        }

        @Override
        public void command(boolean wantOpen) throws DoorException {
            if (commandFails) throw new DoorException("offline");
            commands.add(wantOpen);
        }
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Runs delayed tasks only when the test calls {@link #runDue()}. */
    static final class ManualTasks implements DelayedTasks {
        private record Task(Instant when, Runnable task) {}

        private final Clock clock;
        private final List<Task> pending = new ArrayList<>();

        ManualTasks(Clock clock) {
            this.clock = clock;
        }

        @Override
        public void runAt(Instant when, Runnable task) {
            pending.add(new Task(when, task));
        }

        void runDue() {
            List<Runnable> due = new ArrayList<>();
            for (Iterator<Task> it = pending.iterator(); it.hasNext(); ) {
                Task t = it.next();
                if (!t.when().isAfter(clock.instant())) {
                    due.add(t.task());
                    it.remove();
                }
            }
            due.forEach(Runnable::run);
        }
    }
}
