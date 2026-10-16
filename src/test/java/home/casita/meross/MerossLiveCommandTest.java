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
import home.casita.garage.DoorDevice;
import home.casita.garage.GarageProperties;
import home.casita.garage.GarageService;
import home.casita.garage.InMemoryActivityLog;
import home.casita.garage.Outcome;
import tools.jackson.databind.json.JsonMapper;

/**
 * The command path against the real MSG200, for Ford (channel 1) only. Skipped unless asked for.
 *
 * <h2>Dry run (sends only GETs)</h2>
 * <pre>
 * mvn test -Dtest=MerossLiveCommandTest#dryRunForFordBuildsTheCommandAndPublishesNoSet -Dmeross.live=true
 * </pre>
 * Reads Ford, builds and signs the open (or close) command, and checks on the MQTT connection itself
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
 * the SETACK. Toyota and channel 3 are never sent anything.
 */
@EnabledIf("home.casita.meross.MerossLiveCheckTest#requested")
class MerossLiveCommandTest {

    private static final int FORD = 1;
    private static final Map<Integer, String> DOORS = Map.of(1, "Ford", 2, "Toyota");

    /** "open-ford-channel-1-<today>" or "close-ford-channel-1-<today>", or null. */
    static String confirmedAction() {
        String given = System.getProperty("meross.command.confirm", "");
        for (String action : List.of("open", "close")) {
            if (given.equals(action + "-ford-channel-1-" + LocalDate.now())) return action;
        }
        return null;
    }

    /** Counts every publish on the real MQTT connection, by method, without keeping the payload. */
    static final class CountingConnector implements MqttConnector {
        final MqttConnector real = new HiveMqConnector();
        final List<String> publishedMethods = new CopyOnWriteArrayList<>();
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
                    publishedMethods.add(String.valueOf(((Map<?, ?>) message.get("header")).get("method")));
                    return c.publish(topic, payload, atMostOnce);
                }
            };
        }
    }

    private record Live(MerossSession session, CountingConnector connector, MerossDoor.Opener opener, MerossProperties props) {}

    private static Live connect(CommandMode mode) throws Exception {
        MerossLocalCredentials credentials = MerossLocalCredentials.resolve(System.getenv("MEROSS_EMAIL"),
                System.getenv("MEROSS_PASSWORD"), System.getenv("MEROSS_DEVICE_UUID"), MerossLocalCredentials.FILE,
                "MEROSS_EMAIL and MEROSS_PASSWORD");
        MerossProperties props = new MerossProperties(System.getenv().getOrDefault("MEROSS_API", "https://iotx-us.meross.com"),
                null, null, null, FORD, Duration.ofSeconds(10), mode, DOORS, Set.of(FORD)).withCredentials(credentials);
        CountingConnector connector = new CountingConnector();
        MerossSession session = new MerossSession(new MerossCloudApi(props.apiBaseUrl(), props.requestTimeout()), connector, props,
                Clock.systemUTC());
        MerossDoor.Opener opener = MerossDoor.readOnly(session, props.deviceUuid(), FORD).opener();
        assertThat(opener.type()).as("this test is for the MSG200").isEqualTo(MerossDoor.MSG200);
        return new Live(session, connector, opener, props);
    }

    @Test
    void dryRunForFordBuildsTheCommandAndPublishesNoSet() throws Exception {
        Live live = connect(CommandMode.DRY_RUN);
        try {
            MerossDoor ford = new MerossDoor(live.session(), live.opener().uuid(), FORD, CommandMode.DRY_RUN, Set.of(FORD));
            DoorDevice.Reading before = ford.read();
            boolean target = !before.open(); // toward the opposite, as GarageService would
            System.out.println("Ford (channel 1) reads " + (before.open() ? "NOT CLOSED" : "CLOSED") + "; dry-running a "
                    + (target ? "open" : "close"));

            assertThatThrownBy(() -> ford.command(target))
                    .isInstanceOf(DoorCommandRejectedException.class)
                    .hasMessageContaining("Dry run")
                    .satisfies(e -> System.out.println("Result: " + e.getMessage()));

            System.out.println("Published on MQTT: " + live.connector().publishedMethods);
            assertThat(live.connector().publishedMethods).isNotEmpty().allMatch("GET"::equals);
            assertThat(ford.read().open()).as("Ford unchanged").isEqualTo(before.open());
        } finally {
            live.session().destroy();
        }
    }

    @Test
    void confirmedOpenOfFord() throws Exception {
        runConfirmed("open");
    }

    /**
     * Ford must already be open (by hand, wall button or the open test) and stay "not closed" while it is
     * watched for the travel time; then exactly one close, judged by the sensor afterwards.
     */
    @Test
    void confirmedCloseOfFord() throws Exception {
        runConfirmed("close");
    }

    /** One command to Ford through GarageService, only with today's confirmation for exactly this action. */
    private static void runConfirmed(String action) throws Exception {
        assumeThat(confirmedAction()).as("needs -Dmeross.command.confirm=" + action + "-ford-channel-1-" + LocalDate.now())
                .isEqualTo(action);
        boolean open = "open".equals(action);
        Duration travel = Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("MEROSS_TRAVEL_SECONDS", "20")));

        Live live = connect(CommandMode.LIVE);
        try {
            MerossDoor ford = new MerossDoor(live.session(), live.opener().uuid(), FORD, CommandMode.LIVE, Set.of(FORD));

            // 1. The starting state, read before anything else: open needs CLOSED, close needs NOT CLOSED.
            DoorDevice.Reading start = ford.read();
            System.out.println("Ford (channel 1) reads " + (start.open() ? "NOT CLOSED" : "CLOSED") + " before the " + action);
            assertThat(start.open()).as(open ? "Ford must be closed before an open test" : "Ford must be open before a close test "
                    + "(open it by hand or with the open test first); nothing was sent").isEqualTo(!open);

            InMemoryActivityLog activity = new InMemoryActivityLog();
            List<String> notifications = new ArrayList<>();
            List<Runnable> checks = new CopyOnWriteArrayList<>();
            GarageService garage = new GarageService(ford, activity, notifications::add, (when, task) -> checks.add(task),
                    Clock.systemUTC(), new GarageProperties(travel, Duration.ofSeconds(3), true));
            System.out.println("Status: " + garage.status());

            if (!open) {
                // 2. GarageService closes only a door it has watched report "not closed" for the travel time.
                //    Watch it every 2 s: if it ever reads closed, someone moved it, and nothing is sent.
                long until = System.nanoTime() + travel.plusSeconds(2).toNanos();
                while (System.nanoTime() < until) {
                    Thread.sleep(2000);
                    assertThat(ford.read().open()).as("Ford stayed not closed while watched; it changed, so nothing was sent").isTrue();
                }
                System.out.println("Watched Ford not closed for " + travel.toSeconds() + "s: " + garage.status());
                System.out.println("Closing Ford now. Keep the doorway clear; the opener's photo eyes are the physical safety.");
            }

            // 3. Exactly one command, through all of GarageService's rules.
            CommandResult result = open ? garage.open("live test") : garage.close("live test");
            long sets = live.connector().publishedMethods.stream().filter("SET"::equals).count();
            System.out.println("Command: " + result.outcome() + " (" + result.message() + ")");
            System.out.println("Published on MQTT: " + live.connector().publishedMethods);
            assertThat(result.outcome()).as("the command was sent").isIn(Outcome.MOVING, Outcome.UNCONFIRMED);
            assertThat(sets).as("exactly one SET").isEqualTo(1);

            // 4. Judged by the sensor after the travel time, never by the SETACK. No retry, whatever happens.
            Thread.sleep(travel.plusSeconds(4).toMillis());
            checks.forEach(Runnable::run); // the check GarageService scheduled
            List<Outcome> outcomes = activity.recent(10).reversed().stream().map(ActivityEvent::outcome).toList();
            DoorDevice.Reading after = ford.read();
            System.out.println("Activity: " + outcomes);
            System.out.println("Notifications: " + notifications);
            System.out.println("Ford (channel 1) now reads " + (after.open() ? "NOT CLOSED" : "CLOSED") + "; status " + garage.status());

            assertThat(live.connector().publishedMethods.stream().filter("SET"::equals).count()).as("never retried").isEqualTo(1);
            assertThat(after.open()).as("the sensor shows Ford " + (open ? "not closed" : "closed")).isEqualTo(open);
            assertThat(outcomes).as("the final check").last().isEqualTo(open ? Outcome.OPEN_INFERRED : Outcome.CLOSED_CONFIRMED);
        } finally {
            live.session().destroy();
        }
    }
}
