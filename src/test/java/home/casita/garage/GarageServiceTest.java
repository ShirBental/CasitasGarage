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
import java.util.Map;
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
        garage = new GarageService("Ford", door, DoorControl.SIMULATED, activity, pushes::add, tasks, clock,
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
        assertThat(pushes).containsExactly("Opening the Ford door for Shir");
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
        assertThat(status.door()).isEqualTo(DoorState.NOT_CLOSED);
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
        assertThat(pushes).containsExactly("Closing the Ford door for Shir", "The Ford door is closed. All tucked in.");
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
        assertLatest(GarageService.CASITA, Action.OPEN, Outcome.OPEN_INFERRED);
        assertThat(lastPush()).contains("went through");
        assertThat(garage.status().door()).isEqualTo(DoorState.NOT_CLOSED);
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
        assertThat(garage.status().door()).isEqualTo(DoorState.NOT_CLOSED);
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
        assertThat(garage.status().door()).isEqualTo(DoorState.NOT_CLOSED);
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
        assertThat(lastPush()).contains("didn't close").contains("won't move it again");

        // Reported, but not cleared: the door may be auto-reversing or blocked. Nothing is sent until a person
        // has checked it and resumed, and then the door is watched for the travel time again before a close.
        assertThat(garage.close("Hubby").outcome()).isEqualTo(Outcome.HELD);
        assertThat(door.commands).containsExactly(false);
        garage.resume("Hubby");
        assertThat(garage.close("Hubby").outcome()).isEqualTo(Outcome.BUSY);
        clock.advance(TRAVEL);
        assertThat(garage.close("Hubby").outcome()).isEqualTo(Outcome.MOVING);
        assertThat(door.commands).containsExactly(false, false);
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
        assertThat(second.outcome()).isEqualTo(Outcome.HELD);        // then Hubby's is held, not sent
        assertThat(events.get(0).who()).isEqualTo("Hubby");
        assertThat(door.commands).containsExactly(false);

        // The late task for Shir's close finally runs: it must not report again.
        tasks.runDue();
        assertThat(activity.recent(10)).filteredOn(e -> e.outcome() == Outcome.STUCK).hasSize(1);
        assertThat(garage.status().held()).isTrue();
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

    /* ---------- the state model: every movement ends with one final outcome ---------- */

    @Test
    void anAcknowledgedOpenEndsWithOpenInferredNotMoving() {
        garage.open("Shir");
        door.open = true;

        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue();

        assertThat(outcomes()).containsExactly(Outcome.MOVING, Outcome.OPEN_INFERRED);
        assertLatest(GarageService.CASITA, Action.OPEN, Outcome.OPEN_INFERRED);
        GarageStatus status = garage.status();
        assertThat(status.door()).isEqualTo(DoorState.NOT_CLOSED); // inferred open: the sensor can't say "fully open"
        assertThat(status.canClose()).isTrue();
    }

    @Test
    void anAcknowledgedCloseEndsWithClosedConfirmedWhenTheSensorReadsClosed() {
        doorSettledOpen();
        garage.close("Shir");
        door.open = false;

        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue();

        assertThat(outcomes()).containsExactly(Outcome.MOVING, Outcome.CLOSED_CONFIRMED);
        assertThat(garage.status().door()).isEqualTo(DoorState.CLOSED);
    }

    @Test
    void aCloseFinishedEarlyIsRecordedOnceEvenWhenTheScheduledCheckRunsLater() {
        doorSettledOpen();
        garage.close("Shir");
        clock.advance(Duration.ofSeconds(6));
        door.open = false;
        garage.status(); // reads closed: the close is checked now

        clock.advance(TRAVEL);
        tasks.runDue(); // the scheduled check finds nothing left to check

        assertThat(outcomes()).containsExactly(Outcome.MOVING, Outcome.CLOSED_CONFIRMED);
    }

    @Test
    void aMovementNeverStaysMovingEvenIfTheScheduledCheckNeverRuns() {
        garage.open("Shir");
        door.open = true;

        clock.advance(TRAVEL.plus(GRACE).plusSeconds(60)); // the scheduled task is lost
        GarageStatus status = garage.status(); // the overdue check runs first

        assertThat(status.door()).isEqualTo(DoorState.NOT_CLOSED);
        assertThat(outcomes()).containsExactly(Outcome.MOVING, Outcome.OPEN_INFERRED);
    }

    @Test
    void aCloseThatTimesOutEndsStuckAndTheDoorStaysNotClosed() {
        doorSettledOpen();
        garage.close("Shir");

        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue();

        assertThat(outcomes()).containsExactly(Outcome.MOVING, Outcome.STUCK);
        assertThat(lastPush()).contains("didn't close");
        assertThat(garage.status().door()).isEqualTo(DoorState.NOT_CLOSED);
        assertThat(garage.status().held()).isTrue();
        assertThat(door.commands).containsExactly(false); // never retried, never reversed
    }

    @Test
    void anUnacknowledgedCloseThatDidCloseEndsClosedConfirmed() {
        doorSettledOpen();
        door.timeoutAfterDelivery = true;
        assertThat(garage.close("Shir").outcome()).isEqualTo(Outcome.UNCONFIRMED);
        assertThat(garage.status().door()).isEqualTo(DoorState.CHECKING);
        door.open = false;

        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue();

        assertThat(outcomes()).containsExactly(Outcome.UNCONFIRMED, Outcome.CLOSED_CONFIRMED);
        assertThat(lastPush()).isEqualTo("It went through after all: the Ford door is closed.");
    }

    @Test
    void anUnreadableSensorAtTheCheckEndsLostAndTheStatusIsUnknown() {
        garage.open("Shir");
        door.readFails = true;

        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue();

        assertThat(outcomes()).containsExactly(Outcome.MOVING, Outcome.LOST);
        assertThat(garage.status().door()).isEqualTo(DoorState.UNKNOWN);
    }

    @Test
    void noOppositeCommandIsEverSentAutomatically() {
        garage.open("Shir");
        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue(); // STUCK: the door didn't open
        garage.status();
        clock.advance(Duration.ofMinutes(5));
        tasks.runDue();
        garage.status();

        assertThat(door.commands).containsExactly(true);
    }

    /* ---------- after STUCK or LOST: held until a person resumes ---------- */

    @Test
    void afterAStuckOpenANewOpenIsHeldEvenThoughTheSensorReadsClosed() {
        garage.open("Shir");
        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue(); // STUCK: still closed

        CommandResult again = garage.open("Shir");

        assertThat(again.outcome()).isEqualTo(Outcome.HELD);
        assertThat(again.message()).contains("checked it");
        assertThat(door.commands).containsExactly(true);
        assertThat(outcomes()).containsExactly(Outcome.MOVING, Outcome.STUCK, Outcome.HELD);
    }

    @Test
    void afterALostCheckCommandsAreHeldEvenOnceTheSensorIsBack() {
        garage.open("Shir");
        door.readFails = true;
        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue(); // LOST
        door.readFails = false;
        door.open = true;
        clock.advance(TRAVEL.multipliedBy(3));

        assertThat(garage.close("Shir").outcome()).isEqualTo(Outcome.HELD);
        assertThat(garage.open("Shir").outcome()).isEqualTo(Outcome.HELD);
        assertThat(door.commands).containsExactly(true);
    }

    @Test
    void whileHeldTheStatusSaysSoAndNothingCanBeDone() {
        garage.open("Shir");
        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue(); // STUCK, door closed

        GarageStatus status = garage.status();

        assertThat(status.door()).isEqualTo(DoorState.CLOSED); // where the sensor says it is
        assertThat(status.held()).isTrue();
        assertThat(status.canOpen()).isFalse();
        assertThat(status.canClose()).isFalse();
    }

    @Test
    void resumeIsRecordedSendsNothingAndAllowsCommandsAgain() {
        garage.open("Shir");
        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue(); // STUCK

        GarageStatus status = garage.resume("Hubby");

        assertThat(status.held()).isFalse();
        assertThat(status.canOpen()).isTrue();
        assertLatest("Hubby", Action.OPEN, Outcome.RESUMED);
        assertThat(door.commands).containsExactly(true);
        assertThat(garage.open("Hubby").outcome()).isEqualTo(Outcome.MOVING);
    }

    @Test
    void resumeNeedsAReadableSensor() {
        garage.open("Shir");
        door.readFails = true;
        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue(); // LOST

        GarageStatus status = garage.resume("Hubby");

        assertThat(status.door()).isEqualTo(DoorState.UNKNOWN);
        assertThat(status.held()).isTrue();
        assertThat(outcomes()).doesNotContain(Outcome.RESUMED);
    }

    @Test
    void resumeWhenNothingIsHeldChangesNothing() {
        GarageStatus status = garage.resume("Hubby");

        assertThat(status.held()).isFalse();
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void successfulMovementsNeverHold() {
        garage.open("Shir");
        door.open = true;
        clock.advance(TRAVEL.plus(GRACE));
        tasks.runDue(); // OPEN_INFERRED

        assertThat(garage.status().held()).isFalse();
        assertThat(garage.close("Shir").outcome()).isEqualTo(Outcome.MOVING);
    }

    /** Outcomes in the order they happened, ignoring who. */
    private List<Outcome> outcomes() {
        return activity.recent(50).reversed().stream().map(ActivityEvent::outcome).toList();
    }

    private void assertLatest(String who, Action action, Outcome outcome) {
        ActivityEvent latest = activity.recent(1).get(0);
        assertThat(List.<Object>of(latest.door(), latest.who(), latest.action(), latest.outcome())).containsExactly("Ford", who, action, outcome);
    }

    private String lastPush() {
        return pushes.get(pushes.size() - 1);
    }

    /* ---------- test doubles ---------- */

    /** A door the test moves by hand: the sensor changes only when the test says. */
    /* ---------- measured timings (H1, H3) ---------- */

    /** Ford with its own travel time and sensor lag, as live mode requires. */
    private void newGarage(Duration travel, Duration lag) {
        door = new FakeDoor();
        activity = new InMemoryActivityLog();
        pushes = new CopyOnWriteArrayList<>();
        clock = new MutableClock(Instant.parse("2026-10-15T21:00:00Z"));
        tasks = new ManualTasks(clock);
        garage = new GarageService("Ford", door, DoorControl.SIMULATED, activity, pushes::add, tasks, clock,
                new GarageProperties(TRAVEL, GRACE, true, Map.of("Ford", travel), Map.of("Ford", lag)));
    }

    /**
     * H1, as it stands with the defaults: a door that really takes 20 s, configured as 10 s. The open's check at
     * 12 s reads "not closed", the door counts as settled, and a close goes to a door that is still opening, where
     * the opener can take it as a button press and stop or reverse it. This is why live commands need each door's
     * measured timings (MerossConfig refuses to start without them); the practice doors keep the defaults.
     */
    @Test
    void withATravelTimeShorterThanTheDoorsACloseCanReachADoorStillOpening() {
        garage.open("Shir");
        door.open = true;
        clock.advance(Duration.ofSeconds(12)); // the real door is still opening until 20 s

        assertThat(garage.close("Hubby").outcome()).isEqualTo(Outcome.MOVING);
        assertThat(door.commands).containsExactly(true, false);
    }

    @Test
    void withTheDoorsOwnTravelTimeACloseWaitsUntilItHasFinishedOpening() {
        newGarage(Duration.ofSeconds(20), Duration.ZERO);
        garage.open("Shir");
        door.open = true;

        clock.advance(Duration.ofSeconds(12));
        assertThat(garage.close("Hubby").outcome()).isEqualTo(Outcome.BUSY);
        clock.advance(Duration.ofSeconds(9)); // 21 s: still before the check at 20 + 2
        assertThat(garage.close("Hubby").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(door.commands).containsExactly(true);

        clock.advance(Duration.ofSeconds(1)); // 22 s: checked, settled
        assertThat(garage.close("Hubby").outcome()).isEqualTo(Outcome.MOVING);
        assertThat(door.commands).containsExactly(true, false);
    }

    @Test
    void aLaggingReadingIsWaitedForInsteadOfReportedStuck() {
        // H3: the reading trails the door. Without the lag, the check at 12 s would read "closed" and hold the door.
        newGarage(Duration.ofSeconds(10), Duration.ofSeconds(15));
        garage.open("Shir");

        clock.advance(Duration.ofSeconds(12));
        assertThat(garage.status().door()).isEqualTo(DoorState.OPENING);
        clock.advance(Duration.ofSeconds(10)); // 22 s: the reading finally changes
        door.open = true;
        clock.advance(Duration.ofSeconds(5)); // 27 s = 10 + 15 + 2: checked

        GarageStatus status = garage.status();
        assertThat(status.door()).isEqualTo(DoorState.NOT_CLOSED);
        assertThat(status.held()).isFalse();
        assertLatest(GarageService.CASITA, Action.OPEN, Outcome.OPEN_INFERRED);
    }

    @Test
    void aDoorSeenNotClosedSettlesOnlyAfterTravelTimePlusLag() {
        // Covers the remote or wall button, and a restart: Casita first sees "not closed" now.
        newGarage(Duration.ofSeconds(10), Duration.ofSeconds(15));
        door.open = true;
        garage.status();

        clock.advance(Duration.ofSeconds(10));
        assertThat(garage.close("Shir").outcome()).isEqualTo(Outcome.BUSY);
        clock.advance(Duration.ofSeconds(14));
        assertThat(garage.close("Shir").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(door.commands).isEmpty();

        clock.advance(Duration.ofSeconds(1)); // 25 s
        assertThat(garage.close("Shir").outcome()).isEqualTo(Outcome.MOVING);
    }

    /* ---------- a person at the door stands in for the sensor's delay, never the door's travel (rule 9) ---------- */

    /** Ford as live mode would have it: 10 s of travel, a reading that can trail the door by 15 s. */
    private static final Duration LAG = Duration.ofSeconds(15);

    @Test
    void afterItsOwnOpenHasTravelledACheckedCloseNeedNotWaitForTheSensorsDelay() {
        newGarage(TRAVEL, LAG);
        garage.open("Shir");
        clock.advance(Duration.ofSeconds(3));
        door.open = true; // the sensor has left "closed"

        clock.advance(Duration.ofSeconds(7)); // 10 s: the door has had its full travel time; the check is at 25 s
        GarageStatus status = garage.status();
        assertThat(status.door()).isEqualTo(DoorState.OPENING);
        assertThat(status.canClose()).isFalse();
        assertThat(status.canCloseIfChecked()).isTrue();
        assertThat(garage.close("Hubby").outcome()).isEqualTo(Outcome.BUSY); // without the check: wait for the sensor
        assertThat(door.commands).containsExactly(true);

        CommandResult result = garage.close("Hubby", true);

        assertThat(result.outcome()).isEqualTo(Outcome.MOVING);
        assertThat(door.commands).containsExactly(true, false);
        assertThat(outcomes()).containsExactly(Outcome.MOVING, Outcome.OPEN_INFERRED, Outcome.CHECKED, Outcome.MOVING);
        assertThat(activity.recent(2).get(1).who()).isEqualTo("Hubby");
        assertThat(garage.status().door()).isEqualTo(DoorState.CLOSING);
    }

    @Test
    void beforeTheTravelTimeEvenACheckedCloseIsRefused() {
        newGarage(TRAVEL, LAG);
        garage.open("Shir");
        door.open = true;
        clock.advance(Duration.ofSeconds(9)); // the door may still be opening

        assertThat(garage.status().canCloseIfChecked()).isFalse();
        CommandResult result = garage.close("Hubby", true);

        assertThat(result.outcome()).isEqualTo(Outcome.BUSY);
        assertThat(result.message()).contains("still be opening");
        assertThat(door.commands).containsExactly(true);
        assertThat(outcomes()).containsExactly(Outcome.MOVING);
    }

    @Test
    void aSensorStillReadingClosedRefusesEvenACheckedCloseAndKeepsTheOpenPending() {
        // The person's word never overrides a reading: the opener itself may still think the door is closed.
        newGarage(TRAVEL, LAG);
        garage.open("Shir");
        clock.advance(Duration.ofSeconds(12)); // travelled, but the reading hasn't caught up

        assertThat(garage.status().canCloseIfChecked()).isFalse();
        CommandResult result = garage.close("Hubby", true);
        assertThat(result.outcome()).isEqualTo(Outcome.BUSY);
        assertThat(result.message()).contains("hasn't caught up");
        // A second open is still refused as busy: it could reach an open door as a button press.
        assertThat(garage.open("Shir").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(door.commands).containsExactly(true);

        door.open = true; // the reading catches up at 20 s
        clock.advance(Duration.ofSeconds(8));
        assertThat(garage.close("Hubby", true).outcome()).isEqualTo(Outcome.MOVING);
        assertThat(door.commands).containsExactly(true, false);
    }

    @Test
    void theOpensScheduledCheckDoesNothingOnceACheckedCloseEndedIt() {
        newGarage(TRAVEL, LAG);
        garage.open("Shir");
        door.open = true;
        clock.advance(TRAVEL);
        garage.close("Hubby", true);
        clock.advance(Duration.ofSeconds(6));
        door.open = false;

        clock.advance(Duration.ofSeconds(21));
        tasks.runDue(); // the open's check (27 s) and the close's check (10 + 10 + 15 + 2 = 37 s) are both due now

        assertThat(outcomes()).containsExactly(Outcome.MOVING, Outcome.OPEN_INFERRED, Outcome.CHECKED, Outcome.MOVING,
                Outcome.CLOSED_CONFIRMED);
        assertThat(garage.status().door()).isEqualTo(DoorState.CLOSED);
        assertThat(garage.status().held()).isFalse();
    }

    @Test
    void aCheckedCloseThatDoesntCloseEndsStuckAndHolds() {
        // Obstruction or auto-reverse: the person's check changes nothing about how the close is judged.
        newGarage(TRAVEL, LAG);
        garage.open("Shir");
        door.open = true;
        clock.advance(TRAVEL);
        garage.close("Hubby", true);

        clock.advance(TRAVEL.plus(LAG).plus(GRACE));
        tasks.runDue();

        assertLatest(GarageService.CASITA, Action.CLOSE, Outcome.STUCK);
        assertThat(garage.close("Hubby", true).outcome()).isEqualTo(Outcome.HELD);
        assertThat(garage.status().canCloseIfChecked()).isFalse();
        assertThat(door.commands).containsExactly(true, false);
    }

    @Test
    void aSecondCheckedCloseIsBusyWhileTheFirstIsUnchecked() {
        newGarage(TRAVEL, LAG);
        garage.open("Shir");
        door.open = true;
        clock.advance(TRAVEL);

        assertThat(garage.close("Hubby", true).outcome()).isEqualTo(Outcome.MOVING);
        assertThat(garage.close("Shir", true).outcome()).isEqualTo(Outcome.BUSY);
        assertThat(garage.open("Shir").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(door.commands).containsExactly(true, false);
    }

    @Test
    void simultaneousCheckedClosesSendOneCommand() throws Exception {
        newGarage(TRAVEL, LAG);
        garage.open("Shir");
        door.open = true;
        clock.advance(TRAVEL);
        door.commandDelayMillis = 50;

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CommandResult>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return garage.close("Hubby", true);
            }));
        }
        start.countDown();
        List<Outcome> got = new ArrayList<>();
        for (Future<CommandResult> f : results) got.add(f.get(5, TimeUnit.SECONDS).outcome());
        pool.shutdown();

        assertThat(got).containsOnlyOnce(Outcome.MOVING);
        assertThat(door.commands).containsExactly(true, false);
    }

    @Test
    void anOpenOfUnknownOutcomeIsNeverEndedByACheckedClose() {
        // The command may still reach the opener late: only the scheduled check may decide.
        newGarage(TRAVEL, LAG);
        door.timeoutAfterDelivery = true;
        garage.open("Shir");
        door.open = true;
        clock.advance(Duration.ofSeconds(12));

        assertThat(garage.status().canCloseIfChecked()).isFalse();
        assertThat(garage.close("Hubby", true).outcome()).isEqualTo(Outcome.BUSY);
        assertThat(door.commands).containsExactly(true);
    }

    @Test
    void aDoorOpenedByTheWallButtonMayBeClosedWhenCheckedOnceItHasHadItsTravelTime() {
        newGarage(TRAVEL, LAG);
        garage.status();
        door.open = true; // the wall button: Casita sees the change now
        garage.status();

        clock.advance(Duration.ofSeconds(9));
        assertThat(garage.status().canCloseIfChecked()).isFalse();
        assertThat(garage.close("Shir", true).outcome()).isEqualTo(Outcome.BUSY);

        clock.advance(Duration.ofSeconds(1));
        assertThat(garage.status().canCloseIfChecked()).isTrue();
        assertThat(garage.close("Shir").outcome()).isEqualTo(Outcome.BUSY); // unchecked: still travel + lag
        assertThat(garage.close("Shir", true).outcome()).isEqualTo(Outcome.MOVING);
        assertThat(outcomes()).containsExactly(Outcome.CHECKED, Outcome.MOVING);
    }

    @Test
    void afterARestartTheCheckDoesntShortenTheWait() {
        // Casita didn't see this door open, and a command from before the restart could still land: travel + lag.
        newGarage(TRAVEL, LAG);
        door.open = true;

        assertThat(garage.close("Shir", true).outcome()).isEqualTo(Outcome.BUSY);
        clock.advance(TRAVEL);
        assertThat(garage.status().canCloseIfChecked()).isFalse();
        assertThat(garage.close("Shir", true).outcome()).isEqualTo(Outcome.BUSY);
        clock.advance(LAG);
        assertThat(garage.close("Shir", true).outcome()).isEqualTo(Outcome.MOVING);
        assertThat(outcomes()).containsExactly(Outcome.MOVING); // an ordinary close by then
    }

    @Test
    void aWallButtonReversalDuringItsOwnOpenIsANewMovementTheCheckCantSkip() {
        // Tony's case: Casita opens; the wall button closes it again, then opens it again. The open's start no longer
        // says when the door last started moving.
        newGarage(TRAVEL, LAG);
        garage.open("Shir");
        clock.advance(Duration.ofSeconds(4));
        door.open = true;
        garage.status();
        clock.advance(Duration.ofSeconds(5)); // 9 s: back to closed
        door.open = false;
        garage.status();
        clock.advance(Duration.ofSeconds(3)); // 12 s: opening again
        door.open = true;
        garage.status();
        clock.advance(Duration.ofSeconds(1)); // 13 s: past the open's travel time, 1 s into the new movement

        assertThat(garage.status().canCloseIfChecked()).isFalse();
        assertThat(garage.close("Hubby", true).outcome()).isEqualTo(Outcome.BUSY);
        assertThat(door.commands).containsExactly(true);

        clock.advance(Duration.ofSeconds(14)); // 27 s: the open's check; the new movement still counts from 12 s
        tasks.runDue();
        clock.advance(Duration.ofSeconds(1)); // 28 s: 16 s since the new "not closed", past its travel time
        assertThat(garage.close("Hubby").outcome()).isEqualTo(Outcome.BUSY); // unchecked: settles at 12 + 25 = 37 s
        assertThat(garage.close("Hubby", true).outcome()).isEqualTo(Outcome.MOVING);
    }

    @Test
    void anOpenerReportingARecentChangeRefusesTheShortcut() {
        newGarage(TRAVEL, LAG);
        garage.status();
        door.open = true;
        garage.status();
        clock.advance(TRAVEL);
        door.changedAt = clock.instant().minusSeconds(3); // a change Casita missed between two reads

        assertThat(garage.status().canCloseIfChecked()).isFalse();
        assertThat(garage.close("Shir", true).outcome()).isEqualTo(Outcome.BUSY);
        door.changedAt = clock.instant().minus(TRAVEL);
        assertThat(garage.close("Shir", true).outcome()).isEqualTo(Outcome.MOVING);
    }

    @Test
    void aCheckedCloseThatCantBeSentLeavesTheCheckOnRecordAndTheDoorWaitsAsBefore() {
        newGarage(TRAVEL, LAG);
        garage.open("Shir");
        door.open = true;
        clock.advance(TRAVEL);
        door.rejectCommands = true;

        assertThat(garage.close("Hubby", true).outcome()).isEqualTo(Outcome.ERROR);
        assertThat(outcomes()).containsExactly(Outcome.MOVING, Outcome.OPEN_INFERRED, Outcome.CHECKED, Outcome.ERROR);
        door.rejectCommands = false;
        // The open is over, but an unchecked close still waits for open + travel + lag.
        assertThat(garage.close("Hubby").outcome()).isEqualTo(Outcome.BUSY);
        clock.advance(LAG);
        assertThat(garage.close("Hubby").outcome()).isEqualTo(Outcome.MOVING);
    }

    @Test
    void withAGraceASettledButUncheckedOpenStillTakesACheckedCloseWithoutRecordingTheCheck() {
        newGarage(TRAVEL, LAG); // grace 2 s: settled at 25 s, checked at 27 s
        garage.open("Shir");
        door.open = true;
        clock.advance(TRAVEL.plus(LAG).plusSeconds(1));

        assertThat(garage.close("Hubby").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(garage.close("Hubby", true).outcome()).isEqualTo(Outcome.MOVING);
        assertThat(outcomes()).containsExactly(Outcome.MOVING, Outcome.OPEN_INFERRED, Outcome.MOVING);
    }

    @Test
    void aCheckedCloseOfASettledDoorIsAnOrdinaryClose() {
        newGarage(TRAVEL, LAG);
        door.open = true;
        garage.status();
        clock.advance(TRAVEL.plus(LAG));

        GarageStatus status = garage.status();
        assertThat(status.canClose()).isTrue();
        assertThat(status.canCloseIfChecked()).isFalse();
        assertThat(garage.close("Shir", true).outcome()).isEqualTo(Outcome.MOVING);
        assertThat(outcomes()).containsExactly(Outcome.MOVING); // nothing to check: no CHECKED entry
    }

    @Test
    void aCheckedCloseStillNeedsTheSensorAndRespectsTheSwitches() {
        newGarage(TRAVEL, LAG);
        door.open = true;
        garage.status();
        clock.advance(TRAVEL);
        door.readFails = true;
        assertThat(garage.close("Shir", true).outcome()).isEqualTo(Outcome.UNKNOWN);
        door.readFails = false;

        newGarage(false);
        door.open = true;
        garage.status();
        clock.advance(TRAVEL);
        assertThat(garage.status().canCloseIfChecked()).isFalse();
        assertThat(garage.close("Shir", true).outcome()).isEqualTo(Outcome.DISABLED);

        garage = new GarageService("Ford", door, DoorControl.OFF, activity, pushes::add, tasks, clock,
                new GarageProperties(TRAVEL, GRACE, true, Map.of("Ford", TRAVEL), Map.of("Ford", LAG)));
        assertThat(garage.close("Shir", true).outcome()).isEqualTo(Outcome.DISABLED);
        assertThat(door.commands).isEmpty();
    }

    @Test
    void afterResumeTheCheckDoesntShortenTheWait() {
        newGarage(TRAVEL, LAG);
        garage.open("Shir"); // the door never moves: stuck at the check, held
        clock.advance(TRAVEL.plus(LAG).plus(GRACE));
        tasks.runDue();
        door.open = true; // someone opened it by hand, then resumes
        garage.resume("Shir");

        clock.advance(TRAVEL);
        assertThat(garage.close("Shir", true).outcome()).isEqualTo(Outcome.BUSY); // a lost command could still land
        clock.advance(LAG);
        assertThat(garage.close("Shir", true).outcome()).isEqualTo(Outcome.MOVING);
    }

    @Test
    void withNoGraceTheOpenIsCheckedAtTravelTimePlusLag() {
        door = new FakeDoor();
        garage = new GarageService("Ford", door, DoorControl.SIMULATED, activity, pushes::add, tasks, clock,
                new GarageProperties(TRAVEL, Duration.ZERO, true, Map.of("Ford", TRAVEL), Map.of("Ford", Duration.ofSeconds(2))));
        garage.open("Shir");
        door.open = true;

        clock.advance(Duration.ofSeconds(11));
        assertThat(garage.status().door()).isEqualTo(DoorState.OPENING);
        clock.advance(Duration.ofSeconds(1)); // 12 s = 10 + 2 + 0
        GarageStatus status = garage.status();
        assertThat(status.door()).isEqualTo(DoorState.NOT_CLOSED);
        assertThat(status.canClose()).isTrue();
    }

    /* ---------- the user's scenario: at the garage, the door stopped, how long until Close works ---------- */

    /** The first second, after an open at 0 s, at which a checked close is sent; the reading changes at {@code flipAt}. */
    private long firstCheckedCloseAfterOpen(Duration travel, Duration lag, long flipAt) {
        newGarage(travel, lag);
        garage.open("Shir");
        for (long t = 1; t <= 120; t++) {
            clock.advance(Duration.ofSeconds(1));
            if (t == flipAt) door.open = true;
            if (garage.close("Shir", true).outcome() == Outcome.MOVING) return t;
        }
        return -1;
    }

    @Test
    void atTheGarageCloseWorksOnceTheDoorHasTravelledWhenMerossReportsPromptly() {
        // Ford-like: 10 s of travel, the reading changes within 3 s; the configured lag (25 s) is only a worst case.
        assertThat(firstCheckedCloseAfterOpen(TRAVEL, Duration.ofSeconds(25), 3)).isEqualTo(10);
        // Before: the first close at all was the check at 10 + 25 + 2 s.
        newGarage(TRAVEL, Duration.ofSeconds(25));
        garage.open("Shir");
        door.open = true;
        clock.advance(Duration.ofSeconds(36));
        assertThat(garage.close("Shir").outcome()).isEqualTo(Outcome.BUSY);
        clock.advance(Duration.ofSeconds(1));
        assertThat(garage.close("Shir").outcome()).isEqualTo(Outcome.MOVING);
    }

    @Test
    void atTheGarageAStaleClosedReadingStillBlocksCloseUntilMerossCatchesUp() {
        // Toyota-like: the reading stays "closed" for 25 s after the door is up. The binary sensor can't support an
        // earlier close: the opener itself may think the door is closed. Close works the moment the reading changes.
        assertThat(firstCheckedCloseAfterOpen(TRAVEL, Duration.ofSeconds(30), 25)).isEqualTo(25);
        assertThat(door.commands).containsExactly(true, false);
    }

    @Test
    void withTheOldConfiguredTravelTimeTheWaitIsThatTravelTime() {
        // Production's travel time must come down to the measured value: rule 9 never skips travel time.
        assertThat(firstCheckedCloseAfterOpen(Duration.ofSeconds(22), Duration.ofSeconds(5), 3)).isEqualTo(22);
    }

    /** One way the door got where it is; each probe replays it from scratch up to {@code seconds}. */
    private interface Scenario {
        void play(long seconds);
    }

    private void tick(long seconds, Runnable each) {
        for (long t = 1; t <= seconds; t++) {
            clock.advance(Duration.ofSeconds(1));
            each.run();
        }
    }

    @Test
    void theAppOffersACheckedCloseExactlyWhenTheServerWouldSendIt() {
        Map<String, Scenario> scenarios = new java.util.LinkedHashMap<>();
        scenarios.put("own open, prompt reading", t -> {
            garage.open("Shir");
            long[] now = {0};
            tick(t, () -> { if (++now[0] == 3) door.open = true; garage.status(); });
        });
        scenarios.put("own open, stale reading", t -> {
            garage.open("Shir");
            long[] now = {0};
            tick(t, () -> { if (++now[0] == 20) door.open = true; garage.status(); });
        });
        scenarios.put("own open, wall-button reversal", t -> {
            garage.open("Shir");
            long[] now = {0};
            tick(t, () -> {
                now[0]++;
                if (now[0] == 3) door.open = true;
                if (now[0] == 8) door.open = false;
                if (now[0] == 11) door.open = true;
                garage.status();
            });
        });
        scenarios.put("wall button", t -> {
            garage.status();
            door.open = true;
            tick(t, garage::status);
        });
        scenarios.put("restart", t -> {
            door.open = true;
            tick(t, garage::status);
        });
        for (Map.Entry<String, Scenario> e : scenarios.entrySet()) {
            for (long t = 0; t <= 40; t++) {
                newGarage(TRAVEL, LAG);
                e.getValue().play(t);
                tasks.runDue();
                GarageStatus status = garage.status();
                Outcome outcome = garage.close("Shir", true).outcome();
                boolean offered = status.canClose() || status.canCloseIfChecked();
                assertThat(outcome == Outcome.MOVING).as(e.getKey() + " at " + t + " s: " + status + " -> " + outcome).isEqualTo(offered);
                assertThat(status.canClose() && status.canCloseIfChecked()).as("never both").isFalse();
            }
        }
    }

    static final class FakeDoor implements DoorDevice {
        volatile boolean open;
        volatile boolean readFails;
        /** What the opener says about when its reading last changed; null when it doesn't say (ours). */
        volatile Instant changedAt;
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
            return new Reading(open, changedAt);
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
