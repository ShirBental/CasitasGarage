package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import home.casita.garage.DoorCommandRejectedException;
import home.casita.garage.DoorDevice;
import home.casita.garage.DoorException;

class MerossDoorTest {

    private final FakeTransport transport = new FakeTransport();

    @Test
    void readsStateReportedAsAList() throws Exception {
        transport.reply = Map.of("state", List.of(Map.of("channel", 0, "open", 1, "lmTime", 1760572800)));
        MerossDoor door = new MerossDoor(transport, "uuid-1", 0, false);

        DoorDevice.Reading reading = door.read();

        assertThat(reading.open()).isTrue();
        assertThat(reading.changedAt()).isEqualTo(Instant.ofEpochSecond(1760572800));
        assertThat(transport.sent).containsExactly("GET uuid-1 Appliance.GarageDoor.State");
    }

    @Test
    void readsStateReportedAsASingleObject() throws Exception {
        transport.reply = Map.of("state", Map.of("channel", 0, "open", 0));

        DoorDevice.Reading reading = new MerossDoor(transport, "uuid-1", 0, false).read();

        assertThat(reading.open()).isFalse();
        assertThat(reading.changedAt()).isNull();
    }

    @Test
    void picksTheConfiguredChannel() throws Exception {
        transport.reply = Map.of("state", List.of(Map.of("channel", 0, "open", 0), Map.of("channel", 1, "open", 1)));

        assertThat(new MerossDoor(transport, "uuid-1", 1, false).read().open()).isTrue();
    }

    @Test
    void aMissingOrGarbledStateIsAProtocolError() {
        for (Map<String, Object> reply : List.<Map<String, Object>>of(
                Map.of("state", List.of(Map.of("channel", 3, "open", 1))),
                Map.of("state", List.of(Map.of("channel", 0, "open", 7))),
                Map.of())) {
            transport.reply = reply;
            assertThatThrownBy(() -> new MerossDoor(transport, "uuid-1", 0, false).read())
                    .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(MerossException.Kind.PROTOCOL));
        }
    }

    @Test
    void commandsAreOffByDefaultAndNothingIsSent() {
        MerossDoor door = new MerossDoor(transport, "uuid-1", 0, false);

        assertThatThrownBy(() -> door.command(true)).isInstanceOf(DoorCommandRejectedException.class);
        assertThatThrownBy(() -> door.command(false)).isInstanceOf(DoorCommandRejectedException.class);
        assertThat(transport.sent).isEmpty();
    }

    @Test
    void whenEnabledOpenAndCloseAreSentOnceEach() throws Exception {
        MerossDoor door = new MerossDoor(transport, "uuid-1", 0, true);

        door.command(true);
        door.command(false);

        assertThat(transport.sent).containsExactly(
                "SET uuid-1 Appliance.GarageDoor.State {state={channel=0, open=1, uuid=uuid-1}}",
                "SET uuid-1 Appliance.GarageDoor.State {state={channel=0, open=0, uuid=uuid-1}}");
    }

    @Test
    void aCommandWhoseOpenerCantBeFoundIsRejectedNotUnknown() {
        transport.devices = List.of(new MerossDeviceInfo("plug-1", "Lamp", "mss310", true));

        assertThatThrownBy(() -> new MerossDoor(transport, null, 0, true).command(true))
                .isInstanceOf(DoorCommandRejectedException.class);
        assertThat(transport.sent).isEmpty();
    }

    @Test
    void findsTheOnlyOpenerOnTheAccount() throws Exception {
        transport.devices = List.of(
                new MerossDeviceInfo("plug-1", "Lamp", "mss310", true),
                new MerossDeviceInfo("door-1", "Garage", "msg100", true));
        transport.reply = Map.of("state", List.of(Map.of("channel", 0, "open", 0)));

        new MerossDoor(transport, null, 0, false).read();

        assertThat(transport.sent.get(0)).isEqualTo("GET door-1 Appliance.GarageDoor.State");
    }

    @Test
    void findsAnMsg200AsTheOnlyOpener() throws Exception {
        transport.devices = List.of(
                new MerossDeviceInfo("plug-1", "Lamp", "mss310", true),
                new MerossDeviceInfo("door-200", "Garage", "msg200", true));
        transport.reply = Map.of("state", List.of(Map.of("channel", 0, "open", 0)));

        new MerossDoor(transport, null, 0, false).read();

        assertThat(transport.sent.get(0)).isEqualTo("GET door-200 Appliance.GarageDoor.State");
    }

    @Test
    void openerTypesAreMatchedIgnoringCase() throws Exception {
        transport.devices = List.of(new MerossDeviceInfo("door-200", "Garage", "MSG200", true));
        transport.reply = Map.of("state", List.of(Map.of("channel", 0, "open", 0)));

        new MerossDoor(transport, null, 0, false).read();

        assertThat(transport.sent.get(0)).startsWith("GET door-200 ");
    }

    @Test
    void asksForAUuidWhenThereAreSeveralOpenersWithoutPrintingTheirUuids() {
        transport.devices = List.of(
                new MerossDeviceInfo("1912089283245425180234298f1a0001", "Front", "msg100", true),
                new MerossDeviceInfo("1912089283245425180234298f1a0002", "Back", "MSG200", true),
                new MerossDeviceInfo("plug-1", "Lamp", "mss310", true));

        assertThatThrownBy(() -> new MerossDoor(transport, "", 0, false).read())
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(MerossException.Kind.NOT_FOUND))
                .hasMessageContaining("casita.meross.device-uuid")
                .hasMessageContaining("Front").hasMessageContaining("Back").hasMessageNotContaining("Lamp")
                .hasMessageContaining("…0001").hasMessageContaining("…0002")
                .hasMessageNotContaining("1912089283245425180234298f1a");
    }

    @Test
    void aConfiguredUuidIsUsedWithoutListingDevices() throws Exception {
        transport.devices = List.of(
                new MerossDeviceInfo("door-1", "Front", "msg100", true),
                new MerossDeviceInfo("door-2", "Back", "msg200", true));
        transport.reply = Map.of("state", List.of(Map.of("channel", 0, "open", 0)));

        new MerossDoor(transport, "door-2", 0, false).read();

        assertThat(transport.sent).containsExactly("GET door-2 Appliance.GarageDoor.State");
    }

    @Test
    void readsOneDoorOfAMultiDoorOpener() throws Exception {
        // The shape community code describes for multi-door openers: one entry per channel, doors from 1.
        transport.reply = Map.of("state", List.of(
                Map.of("channel", 1, "open", 0, "lmTime", 1700000000),
                Map.of("channel", 2, "open", 1, "lmTime", 1700000100),
                Map.of("channel", 3, "open", 0, "lmTime", 0)));

        DoorDevice.Reading reading = new MerossDoor(transport, "door-200", 2, false).read();

        assertThat(reading.open()).isTrue();
        assertThat(reading.changedAt()).isEqualTo(Instant.ofEpochSecond(1700000100));
    }

    @Test
    void aMissingChannelErrorNamesTheChannelsTheOpenerReported() {
        transport.reply = Map.of("state", List.of(Map.of("channel", 1, "open", 0), Map.of("channel", 2, "open", 0)));

        assertThatThrownBy(() -> new MerossDoor(transport, "door-200", 0, false).read())
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(MerossException.Kind.PROTOCOL))
                .hasMessageContaining("door channel 0")
                .hasMessageContaining("reported channels [1, 2]");
    }

    @Test
    void deviceInfoNeverPrintsAFullUuid() {
        String shown = new MerossDeviceInfo("1912089283245425180234298f1a2b3c", "Garage", "msg200", true).toString();

        assertThat(shown).contains("Garage", "msg200", "…2b3c").doesNotContain("1912089283245425180234298f1a");
    }

    @Test
    void commandsStayOffForAnMsg200Too() {
        transport.devices = List.of(new MerossDeviceInfo("door-200", "Garage", "msg200", true));

        assertThatThrownBy(() -> new MerossDoor(transport, null, 1, false).command(true))
                .isInstanceOf(DoorCommandRejectedException.class);
        assertThat(transport.sent).isEmpty();
    }

    @Test
    void failsWhenThereIsNoOpener() {
        transport.devices = List.of(new MerossDeviceInfo("plug-1", "Lamp", "mss310", true));

        assertThatThrownBy(() -> new MerossDoor(transport, null, 0, false).read())
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(MerossException.Kind.NOT_FOUND));
    }

    static final class FakeTransport implements MerossTransport {
        List<MerossDeviceInfo> devices = List.of();
        Map<String, Object> reply = Map.of();
        final List<String> sent = new ArrayList<>();

        @Override
        public List<MerossDeviceInfo> devices() {
            return devices;
        }

        @Override
        public Map<String, Object> get(String deviceUuid, String namespace) {
            sent.add("GET " + deviceUuid + " " + namespace);
            return reply;
        }

        @Override
        public Map<String, Object> setOnce(String deviceUuid, String namespace, Map<String, Object> payload) {
            sent.add("SET " + deviceUuid + " " + namespace + " " + payload);
            return Map.of();
        }
    }
}
