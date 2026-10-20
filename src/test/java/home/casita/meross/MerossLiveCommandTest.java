package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import home.casita.garage.ActivityEvent;
import home.casita.garage.CommandResult;
import home.casita.garage.DoorCommandRejectedException;
import home.casita.garage.DoorControl;
import home.casita.garage.DoorDevice;
import home.casita.garage.GarageProperties;
import home.casita.garage.GarageService;
import home.casita.garage.InMemoryActivityLog;
import home.casita.garage.Outcome;
import tools.jackson.databind.json.JsonMapper;

/**
 * The command path against the real MSG200, one door per run: Ford (channel 1) or Toyota (channel 2). Skipped
 * unless asked for. Each run may command only its own door: the other is not in its command channels.
 *
 * <h2>Dry run (sends only GETs)</h2>
 * <pre>
 * mvn test -Dtest=MerossLiveCommandTest#dryRunForFordBuildsTheCommandAndPublishesNoSet -Dmeross.live=true
 * mvn test -Dtest=MerossLiveCommandTest#dryRunForToyotaBuildsTheCommandAndPublishesNoSet -Dmeross.live=true
 * </pre>
 * Reads the door, builds and signs the open (or close) command, and checks on the MQTT connection itself
 * that no SET was published.
 *
 * <h2>One real command to Ford: moves the door</h2>
 * Only with someone at the garage watching it. Each needs a confirmation naming its own action, Ford,
 * channel 1 and today's date, so it can't be reused another day, for another door or the other way:
 * <pre>
 * mvn test -Dtest=MerossLiveCommandTest#confirmedOpenOfFord  -Dmeross.live=true -Dmeross.command.confirm=open-ford-channel-1-2026-10-16
 * mvn test -Dtest=MerossLiveCommandTest#confirmedCloseOfFord -Dmeross.live=true -Dmeross.command.confirm=close-ford-channel-1-2026-10-16
 * </pre>
 * Both go through GarageService, so every rule there applies: read first, only toward the opposite of
 * a settled reading, one command, never retried, a timeout is an unknown outcome. The close needs Ford
 * already not closed, and watches it stay that way for the travel time ({@code MEROSS_TRAVEL_SECONDS},
 * default 20) before sending. Afterwards both wait the travel time and judge by the sensor, never by
 * the SETACK. Channel 3 is never sent anything.
 *
 * <h2>One real command to Toyota: moves the door</h2>
 * The same, for channel 2, only as part of the supervised Toyota test in docs/MEROSS.md. The confirmation names
 * Toyota and channel 2, and {@code MEROSS_TRAVEL_SECONDS} must be set to Toyota's measured travel time (there is
 * no default for Toyota):
 * <pre>
 * MEROSS_TRAVEL_SECONDS=.. mvn test -Dtest=MerossLiveCommandTest#confirmedOpenOfToyota  -Dmeross.live=true -Dmeross.command.confirm=open-toyota-channel-2-YYYY-MM-DD
 * MEROSS_TRAVEL_SECONDS=.. mvn test -Dtest=MerossLiveCommandTest#confirmedCloseOfToyota -Dmeross.live=true -Dmeross.command.confirm=close-toyota-channel-2-YYYY-MM-DD
 * </pre>
 * Each checks that its one SET named channel 2, never Ford's.
 */
@EnabledIf("home.casita.meross.MerossLiveCheckTest#requested")
class MerossLiveCommandTest {

    private static final int FORD = 1;
    private static final int TOYOTA = 2;
    private static final Map<Integer, String> DOORS = Map.of(1, "Ford", 2, "Toyota");

    /** "open-ford-channel-1-<today>", "close-toyota-channel-2-<today>" and so on for this door, or null. */
    static String confirmedAction(int channel) {
        String given = System.getProperty("meross.command.confirm", "");
        for (String action : List.of("open", "close")) {
            if (given.equals(confirmation(action, channel))) return action;
        }
        return null;
    }

    static String confirmation(String action, int channel) {
        return action + "-" + DOORS.get(channel).toLowerCase(java.util.Locale.ROOT) + "-channel-" + channel + "-" + LocalDate.now();
    }

    /** Counts every publish on the real MQTT connection, by method, without keeping the payload. */
    static final class CountingConnector implements MqttConnector {
        final MqttConnector real = new HiveMqConnector();
        final List<String> publishedMethods = new CopyOnWriteArrayList<>();
        /** The channel each SET named. */
        final List<Object> setChannels = new CopyOnWriteArrayList<>();
        private final JsonMapper json = JsonMapper.builder().build();

        @Override
        public MqttConnection create(MqttConnection.Settings settings, MqttConnection.Listener listener) {
            MqttConnection c = real.create(settings, listener);
            return new MqttConnection() {
                public CompletableFuture<Void> connect() { return c.connect(); }
                public CompletableFuture<Void> subscribe(String topic, Consumer<byte[]> onMessage) { return c.subscribe(topic, onMessage); }
                public boolean isConnected() { return c.isConnected(); }
                public void close() { c.close(); }

                public CompletableFuture<Void> publish(String topic, byte[] payload, boolean atMostOnce) {
                    Map<?, ?> message = json.readValue(new String(payload, StandardCharsets.UTF_8), Map.class);
                    String method = String.valueOf(((Map<?, ?>) message.get("header")).get("method"));
                    publishedMethods.add(method);
                    if ("SET".equals(method) && message.get("payload") instanceof Map<?, ?> p && p.get("state") instanceof Map<?, ?> st) {
                        setChannels.add(st.get("channel"));
                    }
                    return c.publish(topic, payload, atMostOnce);
                }
            };
        }
    }

    private record Live(MerossSession session, CountingConnector connector, MerossDoor.Opener opener, MerossProperties props) {}

    private static Live connect(CommandMode mode, int channel) throws Exception {
        MerossLocalCredentials credentials = MerossLocalCredentials.resolve(System.getenv("MEROSS_EMAIL"),
                System.getenv("MEROSS_PASSWORD"), System.getenv("MEROSS_DEVICE_UUID"), MerossLocalCredentials.FILE,
                "MEROSS_EMAIL and MEROSS_PASSWORD");
        MerossProperties props = new MerossProperties(System.getenv().getOrDefault("MEROSS_API", "https://iotx-us.meross.com"),
                null, null, null, channel, Duration.ofSeconds(10), mode, DOORS, Set.of(channel)).withCredentials(credentials);
        CountingConnector connector = new CountingConnector();
        MerossSession session = new MerossSession(new MerossCloudApi(props.apiBaseUrl(), props.requestTimeout()), connector, props,
                Clock.systemUTC());
        MerossDoor.Opener opener = MerossDoor.readOnly(session, props.deviceUuid(), channel).opener();
        assertThat(opener.type()).as("this test is for the MSG200").isEqualTo(MerossDoor.MSG200);
        return new Live(session, connector, opener, props);
    }

    @Test
    void dryRunForFordBuildsTheCommandAndPublishesNoSet() throws Exception {
        dryRun(FORD);
    }

    @Test
    void dryRunForToyotaBuildsTheCommandAndPublishesNoSet() throws Exception {
        dryRun(TOYOTA);
    }

    private static void dryRun(int channel) throws Exception {
        String name = DOORS.get(channel);
        Live live = connect(CommandMode.DRY_RUN, channel);
        try {
            MerossDoor door = new MerossDoor(live.session(), live.opener().uuid(), channel, CommandMode.DRY_RUN, Set.of(channel));
            DoorDevice.Reading before = door.read();
            boolean target = !before.open(); // toward the opposite, as GarageService would
            System.out.println(name + " (channel " + channel + ") reads " + (before.open() ? "NOT CLOSED" : "CLOSED") + "; dry-running a "
                    + (target ? "open" : "close"));

            assertThatThrownBy(() -> door.command(target))
                    .isInstanceOf(DoorCommandRejectedException.class)
                    .hasMessageContaining("Dry run")
                    .hasMessageContaining("channel " + channel)
                    .satisfies(e -> System.out.println("Result: " + e.getMessage()));

            System.out.println("Published on MQTT: " + live.connector().publishedMethods);
            assertThat(live.connector().publishedMethods).isNotEmpty().allMatch("GET"::equals);
            assertThat(door.read().open()).as(name + " unchanged").isEqualTo(before.open());
        } finally {
            live.session().destroy();
        }
    }

    @Test
    void confirmedOpenOfFord() throws Exception {
        runConfirmed("open", FORD);
    }

    /**
     * Ford must already be open (by hand, wall button or the open test) and stay "not closed" while it is
     * watched for the travel time; then exactly one close, judged by the sensor afterwards.
     */
    @Test
    void confirmedCloseOfFord() throws Exception {
        runConfirmed("close", FORD);
    }

    /** Part of the supervised Toyota test (docs/MEROSS.md): Toyota must be closed, and its travel time measured. */
    @Test
    void confirmedOpenOfToyota() throws Exception {
        runConfirmed("open", TOYOTA);
    }

    /** As {@link #confirmedCloseOfFord}, for Toyota: watched not closed for its travel time, then one close. */
    @Test
    void confirmedCloseOfToyota() throws Exception {
        runConfirmed("close", TOYOTA);
    }

    /** One command to one door through GarageService, only with today's confirmation for exactly this action and door. */
    private static void runConfirmed(String action, int channel) throws Exception {
        assumeThat(confirmedAction(channel)).as("needs -Dmeross.command.confirm=" + confirmation(action, channel)).isEqualTo(action);
        String name = DOORS.get(channel);
        String door = name + " (channel " + channel + ")";
        String travelSeconds = System.getenv("MEROSS_TRAVEL_SECONDS");
        // Ford's travel time is known (20 s); Toyota's must be measured and given, never guessed.
        assumeThat(channel == FORD || travelSeconds != null).as("Toyota needs MEROSS_TRAVEL_SECONDS set to its measured travel time").isTrue();
        boolean open = "open".equals(action);
        Duration travel = Duration.ofSeconds(Long.parseLong(travelSeconds != null ? travelSeconds : "20"));

        Live live = connect(CommandMode.LIVE, channel);
        try {
            // Only this door's channel is in its command channels: the other door can't be sent anything.
            MerossDoor target = new MerossDoor(live.session(), live.opener().uuid(), channel, CommandMode.LIVE, Set.of(channel));

            // 1. The starting state, read before anything else: open needs CLOSED, close needs NOT CLOSED.
            DoorDevice.Reading start = target.read();
            System.out.println(door + " reads " + (start.open() ? "NOT CLOSED" : "CLOSED") + " before the " + action);
            assertThat(start.open()).as(open ? name + " must be closed before an open test" : name + " must be open before a close test "
                    + "(open it by hand or with the open test first); nothing was sent").isEqualTo(!open);

            InMemoryActivityLog activity = new InMemoryActivityLog();
            List<String> notifications = new ArrayList<>();
            List<Runnable> checks = new CopyOnWriteArrayList<>();
            GarageService garage = new GarageService(name, target, DoorControl.LIVE, activity, notifications::add, (when, task) -> checks.add(task),
                    Clock.systemUTC(), new GarageProperties(travel, Duration.ofSeconds(3), true));
            System.out.println("Status: " + garage.status());

            if (!open) {
                // 2. GarageService closes only a door it has watched report "not closed" for the travel time.
                //    Watch it every 2 s: if it ever reads closed, someone moved it, and nothing is sent.
                long until = System.nanoTime() + travel.plusSeconds(2).toNanos();
                while (System.nanoTime() < until) {
                    Thread.sleep(2000);
                    assertThat(target.read().open()).as(name + " stayed not closed while watched; it changed, so nothing was sent").isTrue();
                }
                System.out.println("Watched " + name + " not closed for " + travel.toSeconds() + "s: " + garage.status());
                System.out.println("Closing " + name + " now. Keep the doorway clear; the opener's photo eyes are the physical safety.");
            }

            // 3. Exactly one command, through all of GarageService's rules, to this door's channel only.
            CommandResult result = open ? garage.open("live test") : garage.close("live test");
            long sets = live.connector().publishedMethods.stream().filter("SET"::equals).count();
            System.out.println("Command: " + result.outcome() + " (" + result.message() + ")");
            System.out.println("Published on MQTT: " + live.connector().publishedMethods + ", SET channels " + live.connector().setChannels);
            assertThat(result.outcome()).as("the command was sent").isIn(Outcome.MOVING, Outcome.UNCONFIRMED);
            assertThat(sets).as("exactly one SET").isEqualTo(1);
            assertThat(live.connector().setChannels).as("the SET named " + door + " only").containsExactly(channel);

            // 4. Judged by the sensor after the travel time, never by the SETACK. No retry, whatever happens.
            Thread.sleep(travel.plusSeconds(4).toMillis());
            checks.forEach(Runnable::run); // the check GarageService scheduled
            List<Outcome> outcomes = activity.recent(10).reversed().stream().map(ActivityEvent::outcome).toList();
            DoorDevice.Reading after = target.read();
            System.out.println("Activity: " + outcomes);
            System.out.println("Notifications: " + notifications);
            System.out.println(door + " now reads " + (after.open() ? "NOT CLOSED" : "CLOSED") + "; status " + garage.status());

            assertThat(live.connector().publishedMethods.stream().filter("SET"::equals).count()).as("never retried").isEqualTo(1);
            assertThat(after.open()).as("the sensor shows " + name + " " + (open ? "not closed" : "closed")).isEqualTo(open);
            assertThat(outcomes).as("the final check").last().isEqualTo(open ? Outcome.OPEN_INFERRED : Outcome.CLOSED_CONFIRMED);
        } finally {
            live.session().destroy();
        }
    }
}
