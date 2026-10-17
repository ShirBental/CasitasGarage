package home.casita.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccessStoreTest {

    @TempDir
    Path dir;

    private final InvitesTest.TestClock clock = new InvitesTest.TestClock();

    @Test
    void anAuthorizationIsForOneMemberOnOneNode() {
        AccessStore store = new AccessStore(dir, clock);
        store.authorize("shir", "Shir's iPhone", TestTailnet.SHIR_PHONE.identity(), "Y3JlZA", "i-1");

        assertThat(store.find("shir", TestTailnet.SHIR_PHONE.nodeId())).isPresent();
        assertThat(store.find("shir", TestTailnet.SHIR_IPAD.nodeId())).isEmpty();
        assertThat(store.find("hubby", TestTailnet.SHIR_PHONE.nodeId())).isEmpty();
    }

    @Test
    void itSurvivesARestartInAPrivateFile() throws Exception {
        new AccessStore(dir, clock).authorize("shir", "Shir's iPhone", TestTailnet.SHIR_PHONE.identity(), "Y3JlZA", "i-1");

        AccessStore restarted = new AccessStore(dir, clock);

        assertThat(restarted.find("shir", TestTailnet.SHIR_PHONE.nodeId())).get().satisfies(d -> {
            assertThat(d.label()).isEqualTo("Shir's iPhone");
            assertThat(d.inviteId()).isEqualTo("i-1");
            assertThat(d.authorizedAt()).isEqualTo(clock.instant());
        });
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("devices.json")))).isEqualTo("rw-------");
    }

    @Test
    void aRevokedDeviceStaysRevokedAfterARestart() {
        AccessStore store = new AccessStore(dir, clock);
        AccessStore.Device device = store.authorize("shir", "Shir's iPhone", TestTailnet.SHIR_PHONE.identity(), "Y3JlZA", "i-1");

        assertThat(store.revokeDevice(device.id())).isTrue();
        assertThat(store.revokeDevice(device.id())).isFalse();

        AccessStore restarted = new AccessStore(dir, clock);
        assertThat(restarted.find("shir", TestTailnet.SHIR_PHONE.nodeId())).isEmpty();
        assertThat(restarted.all()).singleElement().satisfies(d -> assertThat(d.revokedAt()).isEqualTo(clock.instant()));
    }

    @Test
    void revokingAMemberRevokesAllTheirDevicesAndNobodyElses() {
        AccessStore store = new AccessStore(dir, clock);
        store.authorize("shir", "phone", TestTailnet.SHIR_PHONE.identity(), "YQ", "i-1");
        store.authorize("shir", "ipad", TestTailnet.SHIR_IPAD.identity(), "YQ", "i-2");
        store.authorize("hubby", "phone", TestTailnet.HUBBY_PHONE.identity(), "Yg", "i-3");

        assertThat(store.revokeMember("shir")).isEqualTo(2);

        AccessStore restarted = new AccessStore(dir, clock);
        assertThat(restarted.find("shir", TestTailnet.SHIR_PHONE.nodeId())).isEmpty();
        assertThat(restarted.find("shir", TestTailnet.SHIR_IPAD.nodeId())).isEmpty();
        assertThat(restarted.find("hubby", TestTailnet.HUBBY_PHONE.nodeId())).isPresent();
    }

    @Test
    void reAuthorizingANodeReplacesTheOldAuthorization() {
        AccessStore store = new AccessStore(dir, clock);
        store.authorize("shir", "old", TestTailnet.SHIR_PHONE.identity(), "YQ", "i-1");
        store.authorize("shir", "new", TestTailnet.SHIR_PHONE.identity(), "Yg", "i-2");

        assertThat(store.find("shir", TestTailnet.SHIR_PHONE.nodeId())).get().extracting(AccessStore.Device::label).isEqualTo("new");
        assertThat(store.all()).filteredOn(AccessStore.Device::active).hasSize(1);
    }
}
