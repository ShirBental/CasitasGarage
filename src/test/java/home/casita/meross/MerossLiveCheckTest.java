package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import home.casita.garage.DoorDevice;

/**
 * Checks the Meross client against a real account. READ-ONLY: it signs in, lists devices and
 * reads the garage door's state. Commands stay disabled, so it can't move the door.
 *
 * <p>Skipped unless credentials are set. Run it yourself:
 * <pre>
 * MEROSS_EMAIL=you@example.com MEROSS_PASSWORD='...' mvn test -Dtest=MerossLiveCheckTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "MEROSS_EMAIL", matches = ".+")
class MerossLiveCheckTest {

    @Test
    void signsInListsDevicesAndReadsTheDoor() throws Exception {
        MerossProperties props = new MerossProperties(
                System.getenv().getOrDefault("MEROSS_API", "https://iotx-us.meross.com"),
                System.getenv("MEROSS_EMAIL"),
                System.getenv("MEROSS_PASSWORD"),
                System.getenv("MEROSS_DEVICE_UUID"),
                0,
                Duration.ofSeconds(10),
                false);
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
