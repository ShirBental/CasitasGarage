package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import home.casita.garage.DoorDevice;

/**
 * Checks the Meross client against a real account. READ-ONLY: it signs in, lists devices and
 * reads the garage door's state. Commands stay disabled, so it can't move the door.
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
        MerossProperties props = new MerossProperties(
                System.getenv().getOrDefault("MEROSS_API", "https://iotx-us.meross.com"),
                null, null, null, 0, Duration.ofSeconds(10), false).withCredentials(credentials);
        MerossCloudApi api = new MerossCloudApi(props.apiBaseUrl(), props.requestTimeout());
        MerossSession session = new MerossSession(api, new HiveMqConnector(), props, Clock.systemUTC());
        try {
            List<MerossDeviceInfo> devices = session.devices();
            System.out.println("Meross devices: " + devices);
            assertThat(devices).isNotEmpty();

            DoorDevice.Reading reading = new MerossDoor(session, props.deviceUuid(), props.channel(), false).read();
            System.out.println("Garage door sensor: " + (reading.open() ? "NOT CLOSED" : "CLOSED") + " (lmTime " + reading.changedAt() + ")");
        } finally {
            session.destroy();
        }
    }
}
