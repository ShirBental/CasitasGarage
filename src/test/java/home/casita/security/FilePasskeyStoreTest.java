package home.casita.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;

/** Passkeys on disk: invented keys only. */
class FilePasskeyStoreTest {

    @TempDir
    Path dir;

    private final Bytes userId = Bytes.random();
    private final Bytes credentialId = Bytes.random();

    private PublicKeyCredentialUserEntity shir() {
        return ImmutablePublicKeyCredentialUserEntity.builder().name("shir").id(userId).displayName("shir").build();
    }

    private CredentialRecord iphone() {
        return ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(credentialId)
                .userEntityUserId(userId)
                .publicKey(new ImmutablePublicKeyCose(new byte[] {1, 2, 3, 4, 5}))
                .signatureCount(0)
                .uvInitialized(true)
                .transports(Set.of(AuthenticatorTransport.INTERNAL, AuthenticatorTransport.HYBRID))
                .backupEligible(true)
                .backupState(true)
                .label("iPhone")
                .created(Instant.parse("2026-10-16T10:00:00Z"))
                .lastUsed(Instant.parse("2026-10-16T10:00:00Z"))
                .build();
    }

    @Test
    void keepsUsersAndPasskeysAcrossRestarts() {
        FilePasskeyStore store = new FilePasskeyStore(dir);
        store.save(shir());
        store.save(iphone());

        FilePasskeyStore reopened = new FilePasskeyStore(dir);
        assertThat(reopened.findByUsername("shir").getId()).isEqualTo(userId);
        assertThat(reopened.findById(userId).getName()).isEqualTo("shir");
        CredentialRecord back = reopened.findByCredentialId(credentialId);
        assertThat(back.getPublicKey().getBytes()).containsExactly(1, 2, 3, 4, 5);
        assertThat(back.getUserEntityUserId()).isEqualTo(userId);
        assertThat(back.getTransports()).extracting(AuthenticatorTransport::getValue).containsExactlyInAnyOrder("internal", "hybrid");
        assertThat(back.isBackupEligible()).isTrue();
        assertThat(back.getLabel()).isEqualTo("iPhone");
        assertThat(reopened.findByUserId(userId)).hasSize(1);
        assertThat(reopened.countFor("shir")).isEqualTo(1);
        assertThat(reopened.countFor("hubby")).isZero();
    }

    @Test
    void theFileIsReadableByCasitaOnlyAndHoldsNoPrivateKey() throws Exception {
        FilePasskeyStore store = new FilePasskeyStore(dir);
        store.save(shir());
        store.save(iphone());

        Path file = dir.resolve("passkeys.json");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
        assertThat(Files.readString(file)).contains("publicKey").doesNotContainIgnoringCase("privateKey");
        try (var leftovers = Files.list(dir)) {
            assertThat(leftovers.map(p -> p.getFileName().toString())).containsExactly("passkeys.json"); // no temp files left
        }
    }

    @Test
    void deletingAPasskeyOrAUserIsPersisted() {
        FilePasskeyStore store = new FilePasskeyStore(dir);
        store.save(shir());
        store.save(iphone());

        store.delete(credentialId);
        assertThat(new FilePasskeyStore(dir).findByCredentialId(credentialId)).isNull();
        store.delete(userId);
        assertThat(new FilePasskeyStore(dir).findByUsername("shir")).isNull();
    }

    @Test
    void unknownLookupsFindNothingAndAMissingFileIsAnEmptyStore() {
        FilePasskeyStore store = new FilePasskeyStore(dir);

        assertThat(store.findByUsername("shir")).isNull();
        assertThat(store.findById(Bytes.random())).isNull();
        assertThat(store.findByCredentialId(Bytes.random())).isNull();
        assertThat(store.findByUserId(Bytes.random())).isEmpty();
        assertThat(dir.resolve("passkeys.json")).doesNotExist();
    }
}
