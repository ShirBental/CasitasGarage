package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import home.casita.garage.DoorCommandRejectedException;
import home.casita.garage.DoorDevice;

/**
 * Checks the Meross client against a real account. READ-ONLY: it signs in, lists devices, and reads
 * each configured door through the same {@link MerossDoor} the app uses (for an MSG200,
 * {@code GET Appliance.System.All} and its {@code digest.garageDoor}). Every request is a GET; commands
 * stay disabled and are checked to be refused before anything is sent, so it can't move a door.
 * Device uuids are never printed in full, and the session's DEBUG trace shows each MQTT message
 * without secrets or payload values.
 *
 * <p>The doors default to ours, 1 = Ford and 2 = Toyota, as in application.yml; override with e.g.
 * {@code MEROSS_DOORS=1:Ford,2:Toyota}.
 *
 * <p>Skipped unless you ask for it, so a plain {@code mvn test} never signs in to the real account.
 * Run it yourself with the environment:
 * <pre>
 * MEROSS_EMAIL=you@example.com MEROSS_PASSWORD='...' mvn test -Dtest=MerossLiveCheckTest
 * </pre>
 * or with the git-ignored {@code config/meross.local.json} (see docs/MEROSS.md):
 * <pre>
 * mvn test -Dtest=MerossLiveCheckTest -Dmeross.live=true
 * </pre>
 * Each value comes from the environment when set, otherwise from the file. If the run is asked for
 * but the email or password is missing, it fails and says which.
 */
@EnabledIf("requested")
class MerossLiveCheckTest {

    static boolean requested() {
        String email = System.getenv("MEROSS_EMAIL");
        return (email != null && !email.isBlank()) || Boolean.getBoolean("meross.live");
    }

    static Map<Integer, String> doors() {
        Map<Integer, String> doors = new LinkedHashMap<>();
        for (String pair : System.getenv().getOrDefault("MEROSS_DOORS", "1:Ford,2:Toyota").split(",")) {
            String[] parts = pair.trim().split(":", 2);
            doors.put(Integer.parseInt(parts[0].trim()), parts.length > 1 ? parts[1].trim() : "channel " + parts[0].trim());
        }
        return doors;
    }

    @Test
    void signsInFindsTheOpenerAndReadsEachConfiguredDoor() throws Exception {
        // Trace every MQTT request and incoming message (safe fields only; see MerossSession's logging notes).
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MerossSession.class)).setLevel(ch.qos.logback.classic.Level.DEBUG);
        MerossLocalCredentials credentials = MerossLocalCredentials.resolve(System.getenv("MEROSS_EMAIL"),
                System.getenv("MEROSS_PASSWORD"), System.getenv("MEROSS_DEVICE_UUID"), MerossLocalCredentials.FILE,
                "MEROSS_EMAIL and MEROSS_PASSWORD");
        Map<Integer, String> doors = doors();
        MerossProperties props = new MerossProperties(
                System.getenv().getOrDefault("MEROSS_API", "https://iotx-us.meross.com"),
                null, null, null, null, Duration.ofSeconds(10), CommandMode.OFF, doors, java.util.Set.of()).withCredentials(credentials);
        MerossCloudApi api = new MerossCloudApi(props.apiBaseUrl(), props.requestTimeout());
        MerossSession session = new MerossSession(api, new HiveMqConnector(), props, Clock.systemUTC());
        try {
            List<MerossDeviceInfo> devices = session.devices();
            System.out.println("Meross devices: " + devices);

            MerossDoor.Opener opener = MerossDoor.readOnly(session, props.deviceUuid(), null).opener();
            System.out.println("Opener: " + opener);

            // The read path must be one the opener advertises.
            Map<String, Object> abilities = MerossCloudApi.map(session.get(opener.uuid(), "Appliance.System.Ability").get("ability"));
            String readNamespace = MerossDoor.MSG200.equals(opener.type()) ? MerossDoor.NS_SYSTEM_ALL : MerossDoor.NS_GARAGE_DOOR;
            assertThat(abilities).as("the opener advertises " + readNamespace).containsKey(readNamespace);

            for (Map.Entry<Integer, String> door : doors.entrySet()) {
                MerossDoor merossDoor = MerossDoor.readOnly(session, opener.uuid(), door.getKey());
                DoorDevice.Reading reading = merossDoor.read();
                System.out.println(door.getValue() + " (channel " + door.getKey() + "): " + (reading.open() ? "NOT CLOSED" : "CLOSED")
                        + " (lmTime " + reading.changedAt() + ")");

                // Commands are off: refused before anything is sent.
                assertThatThrownBy(() -> merossDoor.command(true)).isInstanceOf(DoorCommandRejectedException.class);
            }

            if (MerossDoor.MSG200.equals(opener.type())) {
                // A disabled channel must be refused, not read as "open". Our opener's channel 3 is disabled;
                // on another account this may not apply, so it's reported rather than required.
                try {
                    DoorDevice.Reading third = MerossDoor.readOnly(session, opener.uuid(), 3).read();
                    System.out.println("Channel 3 is enabled on this opener: " + (third.open() ? "NOT CLOSED" : "CLOSED"));
                } catch (MerossException e) {
                    System.out.println("Channel 3: refused, " + e.kind() + " (" + e.getMessage() + ")");
                }
            }
        } finally {
            session.destroy();
        }
    }
}
