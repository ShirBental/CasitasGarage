package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import home.casita.garage.ActivityEvent;
import home.casita.garage.CommandResult;
import home.casita.garage.DoorCommandRejectedException;
import home.casita.garage.DoorException;
import home.casita.garage.GarageProperties;
import home.casita.garage.GarageService;
import home.casita.garage.InMemoryActivityLog;
import home.casita.garage.Outcome;
import home.casita.meross.MerossDoorTest.FakeTransport;
import home.casita.meross.MerossException.Kind;

/**
 * The command path, against a fake transport: nothing here reaches Meross. Ford is channel 1 and the
 * only door commands may go to; Toyota (2) and the disabled channel 3 must never be sent one.
 */
class MerossDoorCommandTest {

    private static final String MSG200_UUID = "door-200";
    private static final int FORD = 1;
    private static final int TOYOTA = 2;
    private static final Set<Integer> FORD_ONLY = Set.of(FORD);
    private static final String FORD_OPEN_SET = "SET door-200 Appliance.GarageDoor.State {state={channel=1, open=1, uuid=door-200}}";

    private final FakeTransport transport = new FakeTransport();

    /** Our MSG200 as it reported itself: Ford and Toyota enabled, channel 3 disabled but "open". */
    private void doors(int fordOpen, int toyotaOpen) {
        transport.devices = List.of(new MerossDeviceInfo(MSG200_UUID, "Garage", "msg200", true));
        transport.replies.put(MerossDoor.NS_SYSTEM_ALL, Map.of("all", Map.of("digest", Map.of("garageDoor", List.of(
                Map.of("channel", 1, "doorEnable", 1, "open", fordOpen, "lmTime", 0),
                Map.of("channel", 2, "doorEnable", 1, "open", toyotaOpen, "lmTime", 0),
                Map.of("channel", 3, "doorEnable", 0, "open", 1, "lmTime", 0))))));
    }

    private MerossDoor door(int channel, CommandMode mode, Set<Integer> allowed) {
        return new MerossDoor(transport, null, channel, mode, allowed);
    }

    /* ---------- modes ---------- */

    @Test
    void offRejectsBeforeReadingOrSendingAnything() {
        doors(0, 0);

        assertThatThrownBy(() -> door(FORD, CommandMode.OFF, FORD_ONLY).command(true))
                .isInstanceOf(DoorCommandRejectedException.class).hasMessageContaining("command-mode");
        assertThat(transport.sent).isEmpty();
    }

    @Test
    void dryRunReadsFordBuildsTheSignedSetAndPublishesNothing() {
        doors(0, 0);

        assertThatThrownBy(() -> door(FORD, CommandMode.DRY_RUN, FORD_ONLY).command(true))
                .isInstanceOf(DoorCommandRejectedException.class)
                .hasMessageContaining("Dry run").hasMessageContaining("not sent");
        assertThat(transport.sent).containsExactly(
                "GET door-200 Appliance.System.All",
                "DRYRUN door-200 Appliance.GarageDoor.State {state={channel=1, open=1, uuid=door-200}}");
        assertThat(transport.sets()).isZero();
    }

    @Test
    void liveSendsExactlyOneSetToFordWithChannelOpenAndUuid() throws Exception {
        doors(0, 0);
        transport.ack = Map.of("state", Map.of("channel", 1, "open", 0, "lmTime", 0, "execute", 1));

        door(FORD, CommandMode.LIVE, FORD_ONLY).command(true);

        assertThat(transport.sent).containsExactly("GET door-200 Appliance.System.All", FORD_OPEN_SET);
    }

    @Test
    void liveCloseSendsOpen0() throws Exception {
        doors(1, 0);

        door(FORD, CommandMode.LIVE, FORD_ONLY).command(false);

        assertThat(transport.sent).last().isEqualTo("SET door-200 Appliance.GarageDoor.State {state={channel=1, open=0, uuid=door-200}}");
    }

    /* ---------- which doors ---------- */

    @Test
    void toyotaIsNeverSentACommandWhileOnlyFordIsAllowed() {
        doors(0, 0);

        for (CommandMode mode : List.of(CommandMode.DRY_RUN, CommandMode.LIVE)) {
            assertThatThrownBy(() -> door(TOYOTA, mode, FORD_ONLY).command(true))
                    .isInstanceOf(DoorCommandRejectedException.class).hasMessageContaining("channel 2 aren't allowed");
        }
        assertThat(transport.sent).isEmpty();
    }

    @Test
    void theDisabledChannel3IsRefusedEvenIfSomeoneAllowsIt() {
        doors(0, 0);

        assertThatThrownBy(() -> door(3, CommandMode.LIVE, Set.of(3)).command(false))
                .isInstanceOf(DoorCommandRejectedException.class).hasMessageContaining("disabled");
        assertThat(transport.sets()).isZero();
    }

    @Test
    void aCommandNeedsAnExplicitChannel() {
        doors(0, 0);

        assertThatThrownBy(() -> new MerossDoor(transport, null, null, CommandMode.LIVE, FORD_ONLY).command(true))
                .isInstanceOf(DoorCommandRejectedException.class).hasMessageContaining("casita.meross.channel");
        assertThat(transport.sent).isEmpty();
    }

    /* ---------- reading first ---------- */

    @Test
    void aCommandTowardTheStateTheDoorAlreadyReportsIsRefused() {
        doors(0, 0);

        assertThatThrownBy(() -> door(FORD, CommandMode.LIVE, FORD_ONLY).command(false))
                .isInstanceOf(DoorCommandRejectedException.class).hasMessageContaining("already reports closed");
        assertThat(transport.sets()).isZero();
    }

    @Test
    void anUnreadableDoorIsNeverSentACommand() {
        doors(0, 0);
        transport.replies.put(MerossDoor.NS_SYSTEM_ALL, Map.of("all", Map.of("digest", Map.of())));

        assertThatThrownBy(() -> door(FORD, CommandMode.LIVE, FORD_ONLY).command(true))
                .isInstanceOf(DoorCommandRejectedException.class).hasMessageContaining("Couldn't read door channel 1");
        assertThat(transport.sets()).isZero();
    }

    @Test
    void anOpenerThatCantBeFoundIsNeverSentACommand() {
        transport.devices = List.of(new MerossDeviceInfo("plug-1", "Lamp", "mss310", true));

        assertThatThrownBy(() -> door(FORD, CommandMode.LIVE, FORD_ONLY).command(true)).isInstanceOf(DoorCommandRejectedException.class);
        assertThat(transport.sent).isEmpty();
    }

    /* ---------- the acknowledgement ---------- */

    @Test
    void anAcknowledgementThatSaysItDidNotExecuteIsAnUnknownOutcomeNotARejection() {
        doors(0, 0);
        transport.ack = Map.of("state", Map.of("channel", 1, "open", 0, "lmTime", 0, "execute", 0));

        assertThatThrownBy(() -> door(FORD, CommandMode.LIVE, FORD_ONLY).command(true))
                .isNotInstanceOf(DoorCommandRejectedException.class)
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(Kind.DEVICE_ERROR))
                .hasMessageContaining("execute 0");
        assertThat(transport.sets()).isOne();
    }

    @Test
    void anAcknowledgementForAnotherChannelIsAnUnknownOutcome() {
        doors(0, 0);
        transport.ack = Map.of("state", List.of(Map.of("channel", 2, "open", 0, "execute", 1)));

        assertThatThrownBy(() -> door(FORD, CommandMode.LIVE, FORD_ONLY).command(true))
                .isNotInstanceOf(DoorCommandRejectedException.class)
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(Kind.PROTOCOL));
    }

    @Test
    void anAcknowledgementAsAOneItemListOrBareIsAccepted() throws Exception {
        for (Map<String, Object> ack : List.<Map<String, Object>>of(
                Map.of("state", List.of(Map.of("channel", 1, "open", 0, "execute", 1))),
                Map.of("state", Map.of("channel", 1, "open", 0)),
                Map.of())) {
            doors(0, 0);
            transport.ack = ack;
            door(FORD, CommandMode.LIVE, FORD_ONLY).command(true);
        }
        assertThat(transport.sets()).isEqualTo(3);
    }

    @Test
    void aTimeoutIsAnUnknownOutcomeAndIsNeverRetried() {
        doors(0, 0);
        transport.setFailure = new MerossException(Kind.TIMEOUT, "No reply from the opener within 8s");

        assertThatThrownBy(() -> door(FORD, CommandMode.LIVE, FORD_ONLY).command(true))
                .isNotInstanceOf(DoorCommandRejectedException.class).isInstanceOf(DoorException.class);
        assertThat(transport.sets()).isOne();
    }

    @Test
    void aDryRunThatCouldNotBeSentNowIsStillARejection() {
        doors(0, 0);
        transport.dryRunFailure = new DoorCommandRejectedException("Not connected to Meross right now; nothing was sent");

        assertThatThrownBy(() -> door(FORD, CommandMode.DRY_RUN, FORD_ONLY).command(true))
                .isInstanceOf(DoorCommandRejectedException.class).hasMessageContaining("Not connected");
        assertThat(transport.sets()).isZero();
    }

    /* ---------- MSG100 keeps its own read path ---------- */

    @Test
    void msg100CommandsReadGarageDoorStateFirstAndNeedTheirChannelAllowed() throws Exception {
        transport.replies.put(MerossDoor.NS_GARAGE_DOOR, Map.of("state", List.of(Map.of("channel", 0, "open", 0))));

        assertThatThrownBy(() -> new MerossDoor(transport, "uuid-1", 0, CommandMode.LIVE, FORD_ONLY).command(true))
                .isInstanceOf(DoorCommandRejectedException.class);
        new MerossDoor(transport, "uuid-1", 0, CommandMode.LIVE, Set.of(0)).command(true);

        assertThat(transport.sent).containsExactly("GET uuid-1 Appliance.GarageDoor.State",
                "SET uuid-1 Appliance.GarageDoor.State {state={channel=0, open=1, uuid=uuid-1}}");
    }

    /* ---------- the live test's confirmation gate ---------- */

    @Test
    void theLiveCommandTestNeedsAConfirmationNamingTheActionFordChannel1AndToday() {
        String previous = System.getProperty("meross.command.confirm");
        try {
            System.clearProperty("meross.command.confirm");
            assertThat(MerossLiveCommandTest.confirmedAction()).isNull();
            for (String wrong : List.of("yes", "open", "open-ford-channel-1", "open-toyota-channel-2-" + java.time.LocalDate.now(),
                    "open-ford-channel-3-" + java.time.LocalDate.now(), "open-ford-channel-1-" + java.time.LocalDate.now().minusDays(1))) {
                System.setProperty("meross.command.confirm", wrong);
                assertThat(MerossLiveCommandTest.confirmedAction()).as(wrong).isNull();
            }
            System.setProperty("meross.command.confirm", "open-ford-channel-1-" + java.time.LocalDate.now());
            assertThat(MerossLiveCommandTest.confirmedAction()).isEqualTo("open");
            System.setProperty("meross.command.confirm", "close-ford-channel-1-" + java.time.LocalDate.now());
            assertThat(MerossLiveCommandTest.confirmedAction()).isEqualTo("close");
        } finally {
            if (previous == null) System.clearProperty("meross.command.confirm");
            else System.setProperty("meross.command.confirm", previous);
        }
    }

    /* ---------- with GarageService: an acknowledgement is not movement ---------- */

    @Test
    void anAcknowledgedOpenWhoseSensorNeverChangesEndsStuckNotDone() {
        doors(0, 0);
        transport.ack = Map.of("state", Map.of("channel", 1, "open", 0, "execute", 1));
        Rig rig = new Rig(door(FORD, CommandMode.LIVE, FORD_ONLY));

        CommandResult result = rig.service.open("Shir");

        assertThat(result.outcome()).isEqualTo(Outcome.MOVING);
        assertThat(transport.sets()).isOne();
        rig.runDueChecks(); // Ford still reports closed after the travel time
        assertThat(rig.outcomes()).containsExactly(Outcome.MOVING, Outcome.STUCK);
        assertThat(transport.sets()).isOne(); // never retried

        // Asking again doesn't send a second SET: held until someone checks Ford and resumes.
        assertThat(rig.service.open("Shir").outcome()).isEqualTo(Outcome.HELD);
        assertThat(transport.sets()).isOne();
    }

    @Test
    void anAcknowledgedOpenIsConfirmedOnlyByTheSensorAfterwards() {
        doors(0, 0);
        transport.ack = Map.of("state", Map.of("channel", 1, "open", 0, "execute", 1));
        Rig rig = new Rig(door(FORD, CommandMode.LIVE, FORD_ONLY));

        rig.service.open("Shir");
        doors(1, 0); // the sensor changes
        rig.runDueChecks();

        assertThat(rig.outcomes()).containsExactly(Outcome.MOVING, Outcome.OPEN_INFERRED); // judged by the sensor, recorded
        assertThat(rig.notifications).anyMatch(n -> n.startsWith("Opening the garage"));
        assertThat(rig.notifications).noneMatch(n -> n.contains("didn't open"));
    }

    @Test
    void inDryRunGarageServiceReportsThatNothingWasSent() {
        doors(0, 0);
        Rig rig = new Rig(door(FORD, CommandMode.DRY_RUN, FORD_ONLY));

        CommandResult result = rig.service.open("Shir");

        assertThat(result.outcome()).isEqualTo(Outcome.ERROR);
        assertThat(result.message()).contains("Nothing was sent");
        assertThat(transport.sets()).isZero();
    }

    /** GarageService with the door under test, a fixed clock and checks run by hand. */
    private static final class Rig {
        final InMemoryActivityLog activity = new InMemoryActivityLog();
        final List<String> notifications = new ArrayList<>();
        final List<Runnable> due = new ArrayList<>();
        final GarageService service;

        Rig(MerossDoor door) {
            service = new GarageService(door, activity, notifications::add, (when, task) -> due.add(task),
                    Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC),
                    new GarageProperties(Duration.ofSeconds(10), Duration.ofSeconds(2), true));
        }

        void runDueChecks() {
            List<Runnable> now = new ArrayList<>(due);
            due.clear();
            now.forEach(Runnable::run);
        }

        List<Outcome> outcomes() {
            return activity.recent(20).reversed().stream().map(ActivityEvent::outcome).toList();
        }
    }
}
