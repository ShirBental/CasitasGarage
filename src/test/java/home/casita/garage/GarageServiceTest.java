package home.casita.garage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The garage rules against a fake door the test moves by hand. These tests show the service
 * logic does what the rules say; they are not evidence about the real opener's behavior.
 */
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
        pushes = new CopyOnWriteArrayList<>();
        clock = new MutableClock(Instant.parse("2026-10-15T21:00:00Z"));
        tasks = new ManualTasks(clock);
        garage = new GarageService(door, activity, pushes::add, tasks, clock,
                new GarageProperties(TRAVEL, GRACE, allowRemoteClose));
    }

    /** The door has been open, and Casita has watched it, long enough to be settled. */
    private void doorSettledOpen() {
        door.open = true;
        garage.status();
        clock.advance(TRAVEL);
    }

    /* ---------- basics ---------- */

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
    void showsOpeningUntilTheMovementIsChecked() {
        garage.open("Shir");
        door.open = true; // the sensor trips as soon as the door lifts; that proves nothing

        clock.advance(TRAVEL);
        assertThat(garage.status().door()).isEqualTo(DoorState.OPENING);

        clock.advance(GRACE);
        GarageStatus status = garage.status(); // check is due: runs before answering
        assertThat(status.door()).isEqualTo(DoorState.OPEN);
        assertThat(status.canClose()).isTrue();
    }

    @Test
    void finishesAClosingEarlyOnceTheSensorReadsClosed() {
        doorSettledOpen();
        garage.close("Shir");
        assertThat(garage.status().door()).isEqualTo(DoorState.CLOSING);

        clock.advance(Duration.ofSeconds(8));
        door.open = false;

        GarageStatus status = garage.status();
        assertThat(status.door()).isEqualTo(DoorState.CLOSED);
        assertThat(status.canOpen()).isTrue();
        assertThat(pushes).containsExactly("Closing the garage for Shir", "The garage is closed. All tucked in.");
    }

    @Test
    void neverSendsACommandTowardWhatTheSensorAlreadyReports() {
        // The device may treat any command as a button press, so these must send nothing.
        assertThat(garage.close("Shir").outcome()).isEqualTo(Outcome.ALREADY);
        door.open = true;
        assertThat(garage.open("Shir").outcome()).isEqualTo(Outcome.ALREADY);

        assertThat(door.commands).isEmpty();
        assertThat(pushes).isEmpty();
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
    void closingCanBeSwitchedOff() {
        newGarage(false);
        doorSettledOpen();

        CommandResult result = garage.close("Shir");

        assertThat(result.outcome()).isEqualTo(Outcome.DISABLED);
        assertThat(result.status().canClose()).isFalse();
        assertThat(door.commands).isEmpty();
    }

    /* ---------- finding 1: a failed command isn't proof nothing moved ---------- */

    @Test
    void aRejectedCommandMeansNothingWasSent() {
        door.rejectCommands = true;

        CommandResult result = garage.open("Shir");

        assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(result.message()).contains("Nothing was sent");
        assertThat(door.delivered).isEmpty();
        assertThat(pushes).isEmpty();

        // Nothing is pending, so a retry by the person goes straight through.
        door.rejectCommands = false;
        assertThat(garage.open("Shir").outcome()).isEqualTo(Outcome.MOVING);
    }

    @Test
    void aCommandDeliveredButNotAcknowledgedIsTreatedAsPossiblyMoving() {
        door.timeoutAfterDelivery = true;

        CommandResult result = garage.open("Shir");

        assertThat(door.delivered).containsExactly(true); // the device did get it
        assertThat(result.outcome()).isEqualTo(Outcome.UNCONFIRMED);
        assertThat(result.message()).doesNotContainIgnoringCase("nothing moved");
        assertThat(result.status().door()).isEqualTo(DoorState.CHECKING);
        assertThat(pushes).hasSize(1);
        assertThat(pushes.get(0)).contains("not sure");

        // Blocked until the outcome is known: no second activation.
        door.timeoutAfterDelivery = false;
        door.open = true; // the door really is opening
        clock.advance(TRAVEL);
        assertThat(garage.open("Hubby").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(garage.close("Hubby").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(door.delivered).containsExactly(true);

        // The check finds it went through.
        clock.advance(GRACE);
        tasks.runDue();
        assertLatest(GarageService.CASITA, Action.OPEN, Outcome.CONFIRMED);
        assertThat(lastPush()).contains("went through");
        assertThat(garage.status().door()).isEqualTo(DoorState.OPEN);
    }

    @Test
    void anUnacknowledgedCommandThatDidntMoveTheDoorIsReported() {
        door.timeoutAfterDelivery = true;
        garage.open("Shir");

        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue();

        assertLatest(GarageService.CASITA, Action.OPEN, Outcome.STUCK);
        assertThat(lastPush()).contains("may not have reached it");
    }

    /* ---------- finding 2: wall button, remote and startup ---------- */

    @Test
    void afterStartupAnOpenDoorIsWatchedBeforeClosing() {
        door.open = true; // Casita starts and finds the door not closed: it may be moving

        GarageStatus status = garage.status();
        assertThat(status.door()).isEqualTo(DoorState.CHECKING);
        assertThat(status.canClose()).isFalse();
        assertThat(garage.close("Shir").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(door.commands).isEmpty();

        clock.advance(TRAVEL);
        assertThat(garage.status().door()).isEqualTo(DoorState.OPEN);
        assertThat(garage.close("Shir").outcome()).isEqualTo(Outcome.MOVING);
    }

    @Test
    void afterStartupAClosedDoorCanBeOpened() {
        // "Closed" is the one reading that proves the door's position.
        assertThat(garage.status().canOpen()).isTrue();
        assertThat(garage.open("Shir").outcome()).isEqualTo(Outcome.MOVING);
    }

    @Test
    void aDoorOpenedByTheWallButtonCountsAsMovingUntilItSettles() {
        garage.status(); // Casita sees it closed
        clock.advance(Duration.ofMinutes(5));
        door.open = true; // someone presses the wall button

        assertThat(garage.status().door()).isEqualTo(DoorState.OPENING);
        assertThat(garage.close("Shir").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(door.commands).isEmpty();

        clock.advance(TRAVEL);
        assertThat(garage.status().door()).isEqualTo(DoorState.OPEN);
        assertThat(garage.close("Shir").outcome()).isEqualTo(Outcome.MOVING);
    }

    /* ---------- finding 4: a check is never skipped ---------- */

    @Test
    void aFailedCloseIsReportedEvenIfSomeoneAsksAgainBeforeTheCheck() {
        doorSettledOpen();
        garage.close("Shir");
        // Something is in the way: the sensor never reports closed.

        clock.advance(TRAVEL.plus(Duration.ofSeconds(1))); // after travel time, before the check
        CommandResult second = garage.close("Hubby");

        assertThat(second.outcome()).isEqualTo(Outcome.BUSY);
        assertThat(door.commands).containsExactly(false);

        clock.advance(Duration.ofSeconds(1));
        tasks.runDue();
        assertLatest(GarageService.CASITA, Action.CLOSE, Outcome.STUCK);
        assertThat(lastPush()).contains("didn't close");

        // Once checked and reported, a new attempt is allowed.
        assertThat(garage.close("Hubby").outcome()).isEqualTo(Outcome.MOVING);
    }

    @Test
    void anOverdueCheckRunsBeforeTheNextCommandAndIsntMixedUpWithIt() {
        doorSettledOpen();
        garage.close("Shir");

        // The scheduled check is late (busy scheduler); a new request arrives first.
        clock.advance(TRAVEL.plus(GRACE).plus(Duration.ofSeconds(5)));
        CommandResult second = garage.close("Hubby");

        List<ActivityEvent> events = activity.recent(2);
        assertThat(events.get(1).who()).isEqualTo(GarageService.CASITA);
        assertThat(events.get(1).outcome()).isEqualTo(Outcome.STUCK); // Shir's close, reported first
        assertThat(second.outcome()).isEqualTo(Outcome.MOVING);      // then Hubby's starts fresh
        assertThat(events.get(0).who()).isEqualTo("Hubby");

        // The late task for Shir's close finally runs: it must not report again or touch Hubby's.
        tasks.runDue();
        assertThat(activity.recent(10)).filteredOn(e -> e.outcome() == Outcome.STUCK).hasSize(1);
        assertThat(garage.status().door()).isEqualTo(DoorState.CLOSING);
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

    /* ---------- concurrency ---------- */

    @Test
    void simultaneousRequestsActivateTheDoorOnlyOnce() throws Exception {
        door.commandDelayMillis = 100; // widen the window while the command is in flight
        int requests = 8;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<CommandResult>> results = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                String who = "phone-" + i;
                results.add(pool.submit(() -> {
                    go.await();
                    return garage.open(who);
                }));
            }
            go.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<CommandResult> f : results) outcomes.add(f.get(10, TimeUnit.SECONDS).outcome());

            assertThat(door.delivered).containsExactly(true);
            assertThat(outcomes).filteredOn(o -> o == Outcome.MOVING).hasSize(1);
            assertThat(outcomes).filteredOn(o -> o == Outcome.BUSY).hasSize(requests - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    /* ---------- helpers ---------- */

    private void assertLatest(String who, Action action, Outcome outcome) {
        ActivityEvent latest = activity.recent(1).get(0);
        assertThat(List.<Object>of(latest.who(), latest.action(), latest.outcome())).containsExactly(who, action, outcome);
    }

    private String lastPush() {
        return pushes.get(pushes.size() - 1);
    }

    /* ---------- test doubles ---------- */

    /** A door the test moves by hand: the sensor changes only when the test says. */
    static final class FakeDoor implements DoorDevice {
        volatile boolean open;
        volatile boolean readFails;
        /** Fail before sending anything. */
        volatile boolean rejectCommands;
        /** The device gets the command, then the acknowledgement times out. */
        volatile boolean timeoutAfterDelivery;
        volatile long commandDelayMillis;
        /** Commands the device received. */
        final List<Boolean> delivered = Collections.synchronizedList(new ArrayList<>());
        /** Alias used by most tests. */
        final List<Boolean> commands = delivered;

        @Override
        public Reading read() throws DoorException {
            if (readFails) throw new DoorException("offline");
            return new Reading(open, null);
        }

        @Override
        public void command(boolean wantOpen) throws DoorException {
            if (rejectCommands) throw new DoorCommandRejectedException("not connected");
            delivered.add(wantOpen);
            if (commandDelayMillis > 0) {
                try {
                    Thread.sleep(commandDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (timeoutAfterDelivery) throw new DoorException("no acknowledgement within 8s");
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

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
        private final List<Task> pending = Collections.synchronizedList(new ArrayList<>());

        ManualTasks(Clock clock) {
            this.clock = clock;
        }

        @Override
        public void runAt(Instant when, Runnable task) {
            pending.add(new Task(when, task));
        }

        void runDue() {
            List<Runnable> due = new ArrayList<>();
            synchronized (pending) {
                for (Iterator<Task> it = pending.iterator(); it.hasNext(); ) {
                    Task t = it.next();
                    if (!t.when().isAfter(clock.instant())) {
                        due.add(t.task());
                        it.remove();
                    }
                }
            }
            due.forEach(Runnable::run);
        }
    }
}
