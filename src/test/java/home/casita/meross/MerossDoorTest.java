package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import home.casita.garage.DoorDevice;
import home.casita.garage.DoorException;
import home.casita.meross.MerossException.Kind;

class MerossDoorTest {

    private static final String MSG100_UUID = "uuid-1";
    private static final String MSG200_UUID = "door-200";

    private final FakeTransport transport = new FakeTransport();

    /* ---------- MSG100: Appliance.GarageDoor.State, unchanged ---------- */

    @Test
    void msg100ReadsStateReportedAsAList() throws Exception {
        transport.replies.put(MerossDoor.NS_GARAGE_DOOR, Map.of("state", List.of(Map.of("channel", 0, "open", 1, "lmTime", 1760572800))));

        DoorDevice.Reading reading = MerossDoor.readOnly(transport, MSG100_UUID, 0).read();

        assertThat(reading.open()).isTrue();
        assertThat(reading.changedAt()).isEqualTo(Instant.ofEpochSecond(1760572800));
        assertThat(transport.sent).containsExactly("GET uuid-1 Appliance.GarageDoor.State");
    }

    @Test
    void msg100ReadsStateReportedAsASingleObject() throws Exception {
        transport.replies.put(MerossDoor.NS_GARAGE_DOOR, Map.of("state", Map.of("channel", 0, "open", 0)));

        DoorDevice.Reading reading = MerossDoor.readOnly(transport, MSG100_UUID, 0).read();

        assertThat(reading.open()).isFalse();
        assertThat(reading.changedAt()).isNull();
    }

    @Test
    void msg100WithoutAChannelSettingUsesChannel0() throws Exception {
        transport.replies.put(MerossDoor.NS_GARAGE_DOOR, Map.of("state", List.of(Map.of("channel", 0, "open", 1), Map.of("channel", 1, "open", 0))));

        assertThat(MerossDoor.readOnly(transport, MSG100_UUID, null).read().open()).isTrue();
    }

    @Test
    void msg100PicksTheConfiguredChannel() throws Exception {
        transport.replies.put(MerossDoor.NS_GARAGE_DOOR, Map.of("state", List.of(Map.of("channel", 0, "open", 0), Map.of("channel", 1, "open", 1))));

        assertThat(MerossDoor.readOnly(transport, MSG100_UUID, 1).read().open()).isTrue();
    }

    @Test
    void msg100MissingOrGarbledStateIsAProtocolError() {
        for (Map<String, Object> reply : List.<Map<String, Object>>of(
                Map.of("state", List.of(Map.of("channel", 3, "open", 1))),
                Map.of("state", List.of(Map.of("channel", 0, "open", 7))),
                Map.of())) {
            transport.replies.put(MerossDoor.NS_GARAGE_DOOR, reply);
            assertKind(() -> MerossDoor.readOnly(transport, MSG100_UUID, 0).read(), Kind.PROTOCOL);
        }
    }

    /* ---------- MSG200: Appliance.System.All, digest.garageDoor ---------- */

    /** What our MSG200 reported: 1 (Ford) and 2 (Toyota) enabled, 3 disabled but "open". */
    private void msg200(int fordOpen, int toyotaOpen) {
        transport.devices = List.of(new MerossDeviceInfo(MSG200_UUID, "Garage", "msg200", true));
        transport.replies.put(MerossDoor.NS_SYSTEM_ALL, Map.of("all", Map.of("system", Map.of(), "digest", Map.of("garageDoor", List.of(
                Map.of("channel", 1, "doorEnable", 1, "open", fordOpen, "lmTime", 1760572800),
                Map.of("channel", 2, "doorEnable", 1, "open", toyotaOpen, "lmTime", 0),
                Map.of("channel", 3, "doorEnable", 0, "open", 1, "lmTime", 0))))));
    }

    @Test
    void msg200ReadsChannel1FromSystemAllAndNeverAsksForGarageDoorState() throws Exception {
        msg200(0, 1);

        DoorDevice.Reading ford = MerossDoor.readOnly(transport, null, 1).read();

        assertThat(ford.open()).isFalse();
        assertThat(ford.changedAt()).isEqualTo(Instant.ofEpochSecond(1760572800));
        assertThat(transport.sent).containsExactly("GET door-200 Appliance.System.All");
    }

    @Test
    void msg200ReadsBothDoorStatesOnChannel2() throws Exception {
        msg200(0, 1);
        DoorDevice.Reading toyotaOpen = MerossDoor.readOnly(transport, null, 2).read();
        msg200(0, 0);
        DoorDevice.Reading toyotaClosed = MerossDoor.readOnly(transport, null, 2).read();

        assertThat(toyotaOpen.open()).isTrue();
        assertThat(toyotaOpen.changedAt()).isNull(); // lmTime 0
        assertThat(toyotaClosed.open()).isFalse();
    }

    @Test
    void msg200RefusesADisabledChannelInsteadOfReportingItOpen() {
        msg200(0, 0);

        assertThatThrownBy(() -> MerossDoor.readOnly(transport, null, 3).read())
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(Kind.CONFIG))
                .hasMessageContaining("Door channel 3 is disabled");
    }

    @Test
    void msg200WithoutAChannelSettingRefusesToGuess() {
        msg200(0, 0);

        assertThatThrownBy(() -> MerossDoor.readOnly(transport, null, null).read())
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(Kind.CONFIG))
                .hasMessageContaining("set casita.meross.channel").hasMessageContaining("[1, 2, 3]");
    }

    @Test
    void msg200HasNoChannel0AndAMissingChannelListsWhatWasReported() {
        msg200(0, 0);

        for (int missing : new int[] {0, 4}) {
            assertThatThrownBy(() -> MerossDoor.readOnly(transport, null, missing).read())
                    .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(Kind.PROTOCOL))
                    .hasMessageContaining("door channel " + missing).hasMessageContaining("reported channels [1, 2, 3]");
        }
    }

    @Test
    void msg200MalformedRepliesAreProtocolErrors() {
        transport.devices = List.of(new MerossDeviceInfo(MSG200_UUID, "Garage", "msg200", true));
        List<Map<String, Object>> replies = List.of(
                Map.of(),                                                                        // no "all"
                Map.of("all", Map.of("system", Map.of())),                                       // no digest
                Map.of("all", Map.of("digest", Map.of("garageDoor", Map.of("channel", 1)))),     // not a list
                Map.of("all", Map.of("digest", Map.of("garageDoor", List.of("door 1")))),        // entries aren't objects
                digest(Map.of("channel", 1, "doorEnable", 1, "open", 7)),                        // open neither 0 nor 1
                digest(Map.of("channel", 1, "doorEnable", 1)),                                   // open missing
                digest(Map.of("channel", 1, "doorEnable", 5, "open", 0)),                        // doorEnable neither 0 nor 1
                digest(Map.of("doorEnable", 1, "open", 0)));                                     // entry without a channel
        for (Map<String, Object> reply : replies) {
            transport.replies.put(MerossDoor.NS_SYSTEM_ALL, reply);
            assertKind(() -> MerossDoor.readOnly(transport, null, 1).read(), Kind.PROTOCOL);
        }
    }

    private static Map<String, Object> digest(Map<String, Object> entry) {
        return Map.of("all", Map.of("digest", Map.of("garageDoor", List.of(entry))));
    }

    /* ---------- discovery ---------- */

    @Test
    void findsTheOnlyMsg100OnTheAccount() throws Exception {
        transport.devices = List.of(
                new MerossDeviceInfo("plug-1", "Lamp", "mss310", true),
                new MerossDeviceInfo("door-1", "Garage", "msg100", true));
        transport.replies.put(MerossDoor.NS_GARAGE_DOOR, Map.of("state", List.of(Map.of("channel", 0, "open", 0))));

        MerossDoor.readOnly(transport, null, 0).read();

        assertThat(transport.sent).containsExactly("GET door-1 Appliance.GarageDoor.State");
    }

    @Test
    void findsTheOnlyMsg200OnTheAccountWhateverTheCaseOfItsType() throws Exception {
        msg200(0, 0);
        transport.devices = List.of(
                new MerossDeviceInfo("plug-1", "Lamp", "mss310", true),
                new MerossDeviceInfo(MSG200_UUID, "Garage", "MSG200", true));

        MerossDoor.readOnly(transport, null, 1).read();

        assertThat(transport.sent).containsExactly("GET door-200 Appliance.System.All");
    }

    @Test
    void asksForAUuidWhenThereAreSeveralOpenersWithoutPrintingTheirUuids() {
        transport.devices = List.of(
                new MerossDeviceInfo("1912089283245425180234298f1a0001", "Front", "msg100", true),
                new MerossDeviceInfo("1912089283245425180234298f1a0002", "Back", "MSG200", true),
                new MerossDeviceInfo("plug-1", "Lamp", "mss310", true));

        assertThatThrownBy(() -> MerossDoor.readOnly(transport, "", 1).read())
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NOT_FOUND))
                .hasMessageContaining("casita.meross.device-uuid")
                .hasMessageContaining("Front").hasMessageContaining("Back").hasMessageNotContaining("Lamp")
                .hasMessageContaining("…0001").hasMessageContaining("…0002")
                .hasMessageNotContaining("1912089283245425180234298f1a");
    }

    @Test
    void aConfiguredUuidPicksAmongSeveralOpenersAndItsTypeDecidesTheReadPath() throws Exception {
        msg200(0, 1);
        transport.devices = List.of(
                new MerossDeviceInfo("door-1", "Front", "msg100", true),
                new MerossDeviceInfo(MSG200_UUID, "Garage", "msg200", true));

        assertThat(MerossDoor.readOnly(transport, MSG200_UUID, 2).read().open()).isTrue();
        assertThat(transport.sent).containsExactly("GET door-200 Appliance.System.All");
    }

    @Test
    void aConfiguredUuidThatIsNotAnOpenerOnTheAccountIsNotFound() {
        String plug = "1912089283245425180234298f1a9999";
        transport.devices = List.of(new MerossDeviceInfo(plug, "Lamp", "mss310", true));

        assertThatThrownBy(() -> MerossDoor.readOnly(transport, plug, 1).read())
                .isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NOT_FOUND))
                .hasMessageContaining("…9999").hasMessageNotContaining("1912089283245425180234298f1a");
        assertThat(transport.sent).isEmpty();
    }

    @Test
    void theDeviceListIsReadOnceThenTheOpenerIsRemembered() throws Exception {
        msg200(0, 0);
        MerossDoor door = MerossDoor.readOnly(transport, null, 1);

        door.read();
        door.read();

        assertThat(transport.deviceListReads).isEqualTo(1);
    }

    @Test
    void failsWhenThereIsNoOpener() {
        transport.devices = List.of(new MerossDeviceInfo("plug-1", "Lamp", "mss310", true));

        assertKind(() -> MerossDoor.readOnly(transport, null, 0).read(), Kind.NOT_FOUND);
    }

    @Test
    void deviceInfoNeverPrintsAFullUuid() {
        String shown = new MerossDeviceInfo("1912089283245425180234298f1a2b3c", "Garage", "msg200", true).toString();

        assertThat(shown).contains("Garage", "msg200", "…2b3c").doesNotContain("1912089283245425180234298f1a");
    }

    private static void assertKind(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Kind kind) {
        assertThatThrownBy(call).isInstanceOfSatisfying(MerossException.class, e -> assertThat(e.kind()).isEqualTo(kind));
    }

    /** The account holds one MSG100, uuid-1, unless a test says otherwise. Replies by namespace. */
    static final class FakeTransport implements MerossTransport {
        List<MerossDeviceInfo> devices = List.of(new MerossDeviceInfo(MSG100_UUID, "Garage", "msg100", true));
        final Map<String, Map<String, Object>> replies = new HashMap<>();
        final List<String> sent = new ArrayList<>();
        int deviceListReads;
        /** What a SET returns, or throws (an unknown outcome, or a rejection). */
        Map<String, Object> ack = Map.of();
        DoorException setFailure;
        DoorException dryRunFailure;

        @Override
        public List<MerossDeviceInfo> devices() {
            deviceListReads++;
            return devices;
        }

        @Override
        public Map<String, Object> get(String deviceUuid, String namespace) {
            sent.add("GET " + deviceUuid + " " + namespace);
            return replies.getOrDefault(namespace, Map.of());
        }

        @Override
        public Map<String, Object> setOnce(String deviceUuid, String namespace, Map<String, Object> payload) throws DoorException {
            sent.add("SET " + deviceUuid + " " + namespace + " " + payload);
            if (setFailure != null) throw setFailure;
            return ack;
        }

        @Override
        public String dryRunSet(String deviceUuid, String namespace, Map<String, Object> payload) throws DoorException {
            if (dryRunFailure != null) throw dryRunFailure;
            sent.add("DRYRUN " + deviceUuid + " " + namespace + " " + payload);
            return "SET " + namespace + ", payload keys " + payload.keySet() + ", signed";
        }

        long sets() {
            return sent.stream().filter(m -> m.startsWith("SET ")).count();
        }
    }
}
