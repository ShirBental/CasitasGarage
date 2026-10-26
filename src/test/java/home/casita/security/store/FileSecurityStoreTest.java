package home.casita.security.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;


import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import home.casita.security.access.TestBrowsers;

/** The contract, on the file store (local development), plus what is particular to files. */
class FileSecurityStoreTest extends SecurityStoreContract {

    @TempDir
    Path dir;

    @Override
    protected SecurityStore newStore() {
        return new FileSecurityStore(dir, clock);
    }

    @Override
    protected SecurityStore restarted() {
        return new FileSecurityStore(dir, clock);
    }

    @Test
    void theFileIsOwnerOnly() throws Exception {
        invite("shir");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("security.json")))).isEqualTo("rw-------");
        assertThat(Files.readString(dir.resolve("security.json"))).doesNotContain("\"token\"");
    }

    @Test
    void aFailedWriteChangesNothingAndIsUnavailable() throws Exception {
        SecurityStore.EnrollmentClaim claim = consume(invite("shir"), TestBrowsers.SHIR_PHONE);
        var record = passkey("shir", "c2hpcnMta2V5");
        // The file can't be replaced: a non-empty directory sits where it goes.
        Path file = dir.resolve("security.json");
        Files.delete(file);
        Files.createDirectories(file.resolve("blocker"));
        try {
            assertThatThrownBy(() -> store.enroll(claim, record, null)).isInstanceOf(StoreUnavailableException.class);
            assertThat(store.device(TestBrowsers.SHIR_PHONE.nodeId())).isEmpty();
            assertThat(store.passkey(record.getCredentialId())).isEmpty();
            assertThat(store.invitationById(claim.inviteId())).get().satisfies(i -> assertThat(i.enrolledAt()).isNull());
        } finally {
            Files.delete(file.resolve("blocker"));
            Files.delete(file);
        }
        assertThat(store.enroll(claim, record, null)).isTrue(); // the same enrollment, once the disk is back
    }
}
