package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import home.casita.garage.DoorDevice;

/**
 * Checks the Meross client against a real account. READ-ONLY: it signs in, lists devices, asks the
 * opener which namespaces it supports, and reads the garage door's state. Every request is a GET and
 * commands stay disabled, so it can't move the door. Device uuids are never printed in full.
 *
 * <p>For a multi-door MSG200, set {@code MEROSS_CHANNEL} to the door's channel; the run prints the
 * channels the opener reports.
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

    @Test
    void signsInListsDevicesAndReadsTheDoor() throws Exception {
        MerossLocalCredentials credentials = MerossLocalCredentials.resolve(System.getenv("MEROSS_EMAIL"),
                System.getenv("MEROSS_PASSWORD"), System.getenv("MEROSS_DEVICE_UUID"), MerossLocalCredentials.FILE,
                "MEROSS_EMAIL and MEROSS_PASSWORD");
        int channel = Integer.parseInt(System.getenv().getOrDefault("MEROSS_CHANNEL", "0"));
        MerossProperties props = new MerossProperties(
                System.getenv().getOrDefault("MEROSS_API", "https://iotx-us.meross.com"),
                null, null, null, channel, Duration.ofSeconds(10), false).withCredentials(credentials);
        MerossCloudApi api = new MerossCloudApi(props.apiBaseUrl(), props.requestTimeout());
        MerossSession session = new MerossSession(api, new HiveMqConnector(), props, Clock.systemUTC());
        try {
            List<MerossDeviceInfo> devices = session.devices();
            System.out.println("Meross devices: " + devices);
            assertThat(devices).isNotEmpty();

            MerossDoor door = new MerossDoor(session, props.deviceUuid(), props.channel(), false);
            String uuid = door.uuid();

            // Does this opener speak the namespace the door reads? Ask it rather than assume from its type.
            Map<String, Object> abilities = MerossCloudApi.map(session.get(uuid, "Appliance.System.Ability").get("ability"));
            List<String> garageAbilities = abilities.keySet().stream().filter(k -> k.startsWith("Appliance.GarageDoor.")).sorted().toList();
            System.out.println("Garage door namespaces the opener supports: " + garageAbilities);
            assertThat(abilities).containsKey(MerossDoor.NS_GARAGE_DOOR);

            // The reply's shape: which channels, which keys. Values only for channel and open.
            Object state = session.get(uuid, MerossDoor.NS_GARAGE_DOOR).get("state");
            List<?> entries = state instanceof List<?> list ? list : state == null ? List.of() : List.of(state);
            System.out.println("State reply: " + (state instanceof List<?> ? "a list" : "a single object") + " of " + entries.size() + " entr"
                    + (entries.size() == 1 ? "y" : "ies"));
            for (Object entry : entries) {
                Map<String, Object> e = MerossCloudApi.map(entry);
                System.out.println("  channel=" + e.get("channel") + " open=" + e.get("open") + " keys=" + new TreeSet<>(e.keySet()));
            }

            DoorDevice.Reading reading = door.read();
            System.out.println("Garage door sensor on channel " + channel + ": " + (reading.open() ? "NOT CLOSED" : "CLOSED")
                    + " (lmTime " + reading.changedAt() + ")");
        } finally {
            session.destroy();
        }
    }
}
