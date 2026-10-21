package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import home.casita.garage.DelayedTasks;
import home.casita.garage.DoorControl;
import home.casita.garage.DoorException;
import home.casita.garage.DoorRoster;
import home.casita.garage.DoorState;
import home.casita.garage.Garage;
import home.casita.garage.GarageProperties;
import home.casita.garage.GarageService;
import home.casita.garage.InMemoryActivityLog;
import home.casita.garage.Outcome;
import home.casita.meross.MerossException.Kind;

/**
 * Both real doors live, end to end below the HTTP API: MerossConfig's roster, a GarageService per door, MerossDoor, and a
 * fake MSG200 behind the transport. The fake moves each door for its own travel time, reports each door's sensor with
 * its own lag, and counts a command that reaches a moving door (a button press that would stop or reverse it). Timings
 * here are test values, not measurements.
 */
class LiveDoorsTest {

    static final Duration FORD_TRAVEL = Duration.ofSeconds(20), FORD_LAG = Duration.ofSeconds(5);
    static final Duration TOYOTA_TRAVEL = Duration.ofSeconds(18), TOYOTA_LAG = Duration.ofSeconds(25);
    static final Duration GRACE = Duration.ofSeconds(2);

    private Clock0 clock;
    private Tasks tasks;
    private FakeMsg200 opener;
    private Garage garage;
    private GarageService ford, toyota;

    @BeforeEach
    void setUp() {
        clock = new Clock0(Instant.parse("2026-10-21T18:00:00Z"));
        tasks = new Tasks(clock);
        opener = new FakeMsg200(clock);
        garage = garage(Set.of(1, 2), measured());
        ford = garage.door("ford").orElseThrow();
        toyota = garage.door("toyota").orElseThrow();
    }

    static GarageProperties measured() {
        return new GarageProperties(Duration.ofSeconds(10), GRACE, true,
                Map.of("Ford", FORD_TRAVEL, "Toyota", TOYOTA_TRAVEL), Map.of("Ford", FORD_LAG, "Toyota", TOYOTA_LAG));
    }

    private Garage garage(Set<Integer> commandChannels, GarageProperties timings) {
        MerossProperties props = new MerossProperties("http://127.0.0.1:1", null, null, "uuid-1", null, Duration.ofSeconds(1),
                CommandMode.LIVE, Map.of(1, "Ford", 2, "Toyota"), commandChannels);
        DoorRoster roster = new MerossConfig().doorRoster(opener, props,
                new MerossLocalCredentials("placeholder@example.com", "placeholder", "uuid-1"), timings);
        return new Garage(roster, new InMemoryActivityLog(), message -> {}, tasks, clock, timings);
    }

    private void advance(long seconds) {
        clock.advance(Duration.ofSeconds(seconds));
        tasks.runDue();
    }

    /* ---------- both doors, each on its own channel ---------- */

    @Test
    void bothDoorsAreLiveAndEachCommandGoesToItsOwnChannelOnly() {
        assertThat(garage.doors()).extracting(GarageService::name).containsExactly("Ford", "Toyota");
        assertThat(ford.open("Shir").outcome()).isEqualTo(Outcome.MOVING);
        assertThat(opener.sets).containsExactly("SET channel 1 open");
        assertThat(toyota.open("Shir").outcome()).isEqualTo(Outcome.MOVING);
        assertThat(opener.sets).containsExactly("SET channel 1 open", "SET channel 2 open");
        assertThat(ford.status().door()).isEqualTo(DoorState.OPENING);
        assertThat(toyota.status().door()).isEqualTo(DoorState.OPENING);
        assertThat(opener.addressed).doesNotContain(3);
    }

    @Test
    void fordIsReportedOpenOnlyAfterItsOwnTravelTimeAndLag() {
        ford.open("Shir");
        advance(20 + 5 + 1);                     // 26 s: still before its check at 20 + 5 + 2
        assertThat(ford.status().door()).isEqualTo(DoorState.OPENING);
        advance(1);                              // 27 s
        assertThat(ford.status().door()).isEqualTo(DoorState.NOT_CLOSED); // the app shows "Open"
        assertThat(ford.status().held()).isFalse();
        assertThat(opener.pressesWhileMoving).isZero();
    }

    @Test
    void toyotasLaggingReadingIsWaitedForInsteadOfReportedStuck() {
        // Toyota's reading still says closed for 25 s after it starts opening.
        toyota.open("Shir");
        advance(18 + 2);                         // where a check without the lag would read "closed" and hold it
        assertThat(toyota.status().door()).isEqualTo(DoorState.OPENING);
        assertThat(toyota.status().held()).isFalse();
        advance(25);                             // 45 s = 18 + 25 + 2: checked
        assertThat(toyota.status().door()).isEqualTo(DoorState.NOT_CLOSED);
        assertThat(toyota.status().held()).isFalse();
    }

    @Test
    void aCloseWaitsUntilTheDoorHasSettledSoItNeverReachesAMovingDoor() {
        ford.open("Shir");
        advance(21);                             // Ford has finished opening, but its reading may still lag
        assertThat(ford.close("Hubby").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(opener.sets).containsExactly("SET channel 1 open");
        advance(6);                              // 27 s: checked and settled
        assertThat(ford.close("Hubby").outcome()).isEqualTo(Outcome.MOVING);
        assertThat(ford.status().door()).isEqualTo(DoorState.CLOSING);
        advance(20 + 5 + 2);                     // closed, and the lagging reading has caught up
        assertThat(ford.status().door()).isEqualTo(DoorState.CLOSED);
        assertThat(opener.sets).containsExactly("SET channel 1 open", "SET channel 1 close");
        assertThat(opener.pressesWhileMoving).isZero();
    }

    @Test
    void toyotaClosesOnlyAfterItsOwnLongerSettle() {
        toyota.open("Shir");
        advance(44);
        assertThat(toyota.close("Shir").outcome()).isEqualTo(Outcome.BUSY);
        advance(1);                              // 45 s
        assertThat(toyota.close("Shir").outcome()).isEqualTo(Outcome.MOVING);
        advance(18 + 25 + 2);
        assertThat(toyota.status().door()).isEqualTo(DoorState.CLOSED);
        assertThat(opener.pressesWhileMoving).isZero();
    }

    @Test
    void oneDoorMovingNeverBlocksOrDelaysTheOther() {
        ford.open("Shir");
        advance(3);
        assertThat(toyota.open("Hubby").outcome()).isEqualTo(Outcome.MOVING);
        advance(24);                             // 27 s: Ford checked; Toyota still within its 45 s
        assertThat(ford.status().door()).isEqualTo(DoorState.NOT_CLOSED);
        assertThat(toyota.status().door()).isEqualTo(DoorState.OPENING);
    }

    /* ---------- no duplicates, no overlaps, no retries ---------- */

    @Test
    void repeatedTapsSendOneCommandPerDoor() {
        assertThat(ford.open("Shir").outcome()).isEqualTo(Outcome.MOVING);
        assertThat(ford.open("Shir").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(ford.close("Hubby").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(toyota.open("Shir").outcome()).isEqualTo(Outcome.MOVING);
        assertThat(toyota.open("Hubby").outcome()).isEqualTo(Outcome.BUSY);
        assertThat(opener.sets).containsExactly("SET channel 1 open", "SET channel 2 open");
    }

    @Test
    void simultaneousRequestsForBothDoorsSendExactlyOneCommandEach() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < 16; i++) {
            GarageService door = i % 2 == 0 ? ford : toyota;
            pool.submit(() -> {
                go.await();
                return door.open("tap");
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(opener.sets).containsExactlyInAnyOrder("SET channel 1 open", "SET channel 2 open");
    }

    @Test
    void aCommandWhoseAnswerIsLostIsNeverRetriedAndIsCheckedByItsSensor() {
        opener.loseNextAnswer = true;                  // delivered and carried out, but no answer
        assertThat(toyota.open("Shir").outcome()).isEqualTo(Outcome.UNCONFIRMED);
        assertThat(toyota.open("Shir").outcome()).isEqualTo(Outcome.BUSY);
        advance(45);
        assertThat(toyota.status().door()).isEqualTo(DoorState.NOT_CLOSED);
        assertThat(opener.sets).containsExactly("SET channel 2 open");
    }

    @Test
    void anUnreadableDoorIsUnknownAndNothingIsSent() {
        opener.readsFail = true;
        assertThat(ford.open("Shir").outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(toyota.status().door()).isEqualTo(DoorState.UNKNOWN);
        assertThat(opener.sets).isEmpty();
    }

    @Test
    void aDoorThatDidntMoveIsHeldUntilSomeoneHasCheckedIt() {
        opener.ignoreCommands = true;                  // acknowledged, but the door never moves
        ford.open("Shir");
        advance(27);
        assertThat(ford.status().held()).isTrue();
        opener.ignoreCommands = false;
        assertThat(ford.open("Shir").outcome()).isEqualTo(Outcome.HELD);
        assertThat(toyota.open("Shir").outcome()).isEqualTo(Outcome.MOVING); // Toyota isn't held by Ford's failure
        assertThat(opener.sets).containsExactly("SET channel 1 open", "SET channel 2 open");
    }

    /* ---------- set-up the roster refuses ---------- */

    @Test
    void liveForBothDoorsNeedsEachDoorsOwnMeasuredTimings() {
        GarageProperties fordOnly = new GarageProperties(Duration.ofSeconds(10), GRACE, true,
                Map.of("Ford", FORD_TRAVEL), Map.of("Ford", FORD_LAG));
        assertThatThrownBy(() -> garage(Set.of(1, 2), fordOnly))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Live commands to Toyota need its measured timings");
        assertThat(garage(Set.of(1), fordOnly).door("toyota").orElseThrow().open("Shir").outcome()).isEqualTo(Outcome.DISABLED);
    }

    @Test
    void channelThreeCanNeverBeADoorOrACommandChannel() {
        assertThatThrownBy(() -> new MerossProperties("http://127.0.0.1:1", null, null, null, null, Duration.ofSeconds(1), CommandMode.LIVE,
                Map.of(1, "Ford", 2, "Toyota", 3, "Gate"), Set.of(1, 2))).hasMessageContaining("channel 3 is permanently disabled");
        assertThatThrownBy(() -> new MerossProperties("http://127.0.0.1:1", null, null, null, null, Duration.ofSeconds(1), CommandMode.LIVE,
                Map.of(1, "Ford", 2, "Toyota"), Set.of(1, 2, 3))).hasMessageContaining("channel 3 is permanently disabled");
    }

    @Test
    void theRosterReportsBothDoorsLive() {
        MerossProperties props = new MerossProperties("http://127.0.0.1:1", null, null, "uuid-1", null, Duration.ofSeconds(1),
                CommandMode.LIVE, Map.of(1, "Ford", 2, "Toyota"), Set.of(1, 2));
        DoorRoster roster = new MerossConfig().doorRoster(opener, props,
                new MerossLocalCredentials("placeholder@example.com", "placeholder", "uuid-1"), measured());
        assertThat(roster.doors()).extracting(DoorRoster.Door::control).containsExactly(DoorControl.LIVE, DoorControl.LIVE);
    }

    /* ---------- the fake opener ---------- */

    /** An MSG200 with Ford on channel 1 and Toyota on channel 2 (channel 3 disabled), both closed at the start. */
    static final class FakeMsg200 implements MerossTransport {
        private final Clock clock;
        final List<String> sets = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> addressed = Collections.synchronizedList(new ArrayList<>());
        volatile boolean readsFail, ignoreCommands, loseNextAnswer;
        volatile int pressesWhileMoving;
        /** Per channel: when the sensor's value changed, and to what (true = not closed). */
        private final Map<Integer, TreeMap<Instant, Boolean>> sensor = new TreeMap<>();
        /** Per channel: when the current movement ends (null when still). */
        private final Map<Integer, Instant> movingUntil = new TreeMap<>();

        FakeMsg200(Clock clock) {
            this.clock = clock;
            for (int ch : new int[] {1, 2}) {
                TreeMap<Instant, Boolean> t = new TreeMap<>();
                t.put(Instant.EPOCH, false);
                sensor.put(ch, t);
            }
        }

        private static Duration travel(int ch) { return ch == 1 ? FORD_TRAVEL : TOYOTA_TRAVEL; }
        private static Duration lag(int ch) { return ch == 1 ? FORD_LAG : TOYOTA_LAG; }

        /** What the opener reports now: the sensor as it was one lag ago. */
        private synchronized boolean reported(int ch) {
            return sensor.get(ch).floorEntry(clock.instant().minus(lag(ch))).getValue();
        }

        @Override
        public List<MerossDeviceInfo> devices() {
            return List.of(new MerossDeviceInfo("uuid-1", "Garage", "msg200", true));
        }

        @Override
        public synchronized Map<String, Object> get(String deviceUuid, String namespace) throws DoorException {
            if (readsFail) throw new MerossException(Kind.UNAVAILABLE, "the opener can't be reached");
            List<Map<String, Object>> doors = List.of(
                    Map.of("channel", 1, "open", reported(1) ? 1 : 0, "doorEnable", 1, "lmTime", 0),
                    Map.of("channel", 2, "open", reported(2) ? 1 : 0, "doorEnable", 1, "lmTime", 0),
                    Map.of("channel", 3, "open", 1, "doorEnable", 0, "lmTime", 0));
            return Map.of("all", Map.of("digest", Map.of("garageDoor", doors)));
        }

        @Override
        public synchronized Map<String, Object> setOnce(String deviceUuid, String namespace, Map<String, Object> payload) throws DoorException {
            Map<String, Object> state = MerossCloudApi.map(payload.get("state"));
            int ch = MerossCloudApi.number(state.get("channel"));
            boolean open = MerossCloudApi.number(state.get("open")) == 1;
            addressed.add(ch);
            sets.add("SET channel " + ch + " " + (open ? "open" : "close"));
            Instant now = clock.instant();
            Instant until = movingUntil.get(ch);
            if (until != null && now.isBefore(until)) pressesWhileMoving++;
            if (!ignoreCommands) {
                movingUntil.put(ch, now.plus(travel(ch)));
                // Opening: "not closed" as soon as it lifts. Closing: "closed" only at the end of travel.
                sensor.get(ch).put(open ? now : now.plus(travel(ch)), open);
            }
            if (loseNextAnswer) {
                loseNextAnswer = false;
                throw new MerossException(Kind.TIMEOUT, "no answer; the command may or may not have been carried out");
            }
            return Map.of("state", Map.of("channel", ch, "open", open ? 0 : 1, "execute", 1));
        }

        @Override
        public String dryRunSet(String deviceUuid, String namespace, Map<String, Object> payload) {
            return "dry run";
        }
    }

    static final class Clock0 extends Clock {
        private volatile Instant now;
        Clock0(Instant start) { now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Scheduled checks, run when the test's clock reaches them. */
    static final class Tasks implements DelayedTasks {
        private record Task(Instant when, Runnable task) {}
        private final Clock clock;
        private final List<Task> pending = Collections.synchronizedList(new ArrayList<>());
        Tasks(Clock clock) { this.clock = clock; }
        @Override public void runAt(Instant when, Runnable task) { pending.add(new Task(when, task)); }
        void runDue() {
            List<Task> due;
            synchronized (pending) {
                due = pending.stream().filter(t -> !t.when().isAfter(clock.instant())).toList();
                pending.removeAll(due);
            }
            due.forEach(t -> t.task().run());
        }
    }
}
