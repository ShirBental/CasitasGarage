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
    void asksForAUuidWhenThereAreSeveralOpeners() {
        transport.devices = List.of(
                new MerossDeviceInfo("door-1", "Front", "msg100", true),
                new MerossDeviceInfo("door-2", "Back", "MSG100", true));

        assertThatThrownBy(() -> new MerossDoor(transport, "", 0, false).read())
                .isInstanceOf(DoorException.class)
                .hasMessageContaining("casita.meross.device-uuid");
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
