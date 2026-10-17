package home.casita.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * Passkeys, kept in one JSON file ({@code <data-dir>/passkeys.json}) for Spring Security's WebAuthn
 * support. Only public keys and their metadata are stored: nothing here can sign in on its own. The file
 * is written whole, atomically, readable by Casita's user only.
 *
 * <p>A stand-in for the Parameter Store repositories in the design doc (build step 5), behind the same
 * two Spring interfaces, so replacing it changes nothing else.
 */
public class FilePasskeyStore implements PublicKeyCredentialUserEntityRepository, UserCredentialRepository {

    /** One person's WebAuthn user handle. */
    record User(String name, String id, String displayName) {}

    /** One passkey. Byte fields are base64url. */
    record Passkey(String credentialId, String userId, String publicKey, long signatureCount, boolean uvInitialized,
                   List<String> transports, boolean backupEligible, boolean backupState, String attestationObject,
                   String attestationClientDataJson, String label, Instant created, Instant lastUsed) {}

    record Contents(List<User> users, List<Passkey> passkeys) {}

    private final Path file;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, User> usersById = new LinkedHashMap<>();
    private final Map<String, Passkey> passkeysById = new LinkedHashMap<>();

    public FilePasskeyStore(Path dataDir) {
        this.file = dataDir.resolve("passkeys.json");
        if (Files.exists(file)) {
            Contents contents = json.readValue(file.toFile(), Contents.class);
            contents.users().forEach(u -> usersById.put(u.id(), u));
            contents.passkeys().forEach(p -> passkeysById.put(p.credentialId(), p));
        }
    }

    /* ---------- users ---------- */

    @Override
    public synchronized PublicKeyCredentialUserEntity findById(Bytes id) {
        User u = usersById.get(id.toBase64UrlString());
        return u == null ? null : toEntity(u);
    }

    @Override
    public synchronized PublicKeyCredentialUserEntity findByUsername(String username) {
        return usersById.values().stream().filter(u -> u.name().equals(username)).findFirst().map(FilePasskeyStore::toEntity).orElse(null);
    }

    @Override
    public synchronized void save(PublicKeyCredentialUserEntity entity) {
        usersById.put(entity.getId().toBase64UrlString(),
                new User(entity.getName(), entity.getId().toBase64UrlString(), entity.getDisplayName()));
        write();
    }

    @Override
    public synchronized void delete(Bytes id) {
        String key = id.toBase64UrlString();
        boolean changed = usersById.remove(key) != null;
        changed |= passkeysById.remove(key) != null; // UserCredentialRepository.delete takes a credential id
        if (changed) write();
    }

    /* ---------- passkeys ---------- */

    @Override
    public synchronized void save(CredentialRecord r) {
        passkeysById.put(r.getCredentialId().toBase64UrlString(), new Passkey(
                r.getCredentialId().toBase64UrlString(), r.getUserEntityUserId().toBase64UrlString(),
                new Bytes(r.getPublicKey().getBytes()).toBase64UrlString(), r.getSignatureCount(), r.isUvInitialized(),
                r.getTransports() == null ? List.of() : r.getTransports().stream().map(AuthenticatorTransport::getValue).sorted().toList(),
                r.isBackupEligible(), r.isBackupState(), base64(r.getAttestationObject()), base64(r.getAttestationClientDataJSON()),
                r.getLabel(), r.getCreated(), r.getLastUsed()));
        write();
    }

    @Override
    public synchronized CredentialRecord findByCredentialId(Bytes credentialId) {
        Passkey p = passkeysById.get(credentialId.toBase64UrlString());
        return p == null ? null : toRecord(p);
    }

    @Override
    public synchronized List<CredentialRecord> findByUserId(Bytes userId) {
        String id = userId.toBase64UrlString();
        return passkeysById.values().stream().filter(p -> p.userId().equals(id)).map(FilePasskeyStore::toRecord).toList();
    }

    /** How many passkeys a person has (for the app's "set up Face ID" prompt). */
    public synchronized int countFor(String username) {
        PublicKeyCredentialUserEntity user = findByUsername(username);
        return user == null ? 0 : findByUserId(user.getId()).size();
    }

    /* ---------- mapping and storage ---------- */

    private static PublicKeyCredentialUserEntity toEntity(User u) {
        return ImmutablePublicKeyCredentialUserEntity.builder().name(u.name()).id(Bytes.fromBase64(u.id())).displayName(u.displayName()).build();
    }

    private static CredentialRecord toRecord(Passkey p) {
        Set<AuthenticatorTransport> transports = p.transports().stream().map(AuthenticatorTransport::valueOf)
                .collect(Collectors.toCollection(() -> new TreeSet<>((a, b) -> a.getValue().compareTo(b.getValue()))));
        return ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(Bytes.fromBase64(p.credentialId()))
                .userEntityUserId(Bytes.fromBase64(p.userId()))
                .publicKey(new ImmutablePublicKeyCose(Bytes.fromBase64(p.publicKey()).getBytes()))
                .signatureCount(p.signatureCount())
                .uvInitialized(p.uvInitialized())
                .transports(transports)
                .backupEligible(p.backupEligible())
                .backupState(p.backupState())
                .attestationObject(p.attestationObject() == null ? null : Bytes.fromBase64(p.attestationObject()))
                .attestationClientDataJSON(p.attestationClientDataJson() == null ? null : Bytes.fromBase64(p.attestationClientDataJson()))
                .label(p.label())
                .created(p.created())
                .lastUsed(p.lastUsed())
                .build();
    }

    private static String base64(Bytes b) {
        return b == null ? null : b.toBase64UrlString();
    }

    private void write() {
        try {
            Path dir = file.getParent();
            Files.createDirectories(dir);
            restrict(dir, "rwx------");
            Path tmp = Files.createTempFile(dir, "passkeys", ".tmp");
            restrict(tmp, "rw-------");
            json.writeValue(tmp.toFile(), new Contents(new ArrayList<>(usersById.values()), new ArrayList<>(passkeysById.values())));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't save passkeys", e);
        }
    }

    static void restrict(Path path, String permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException e) {
            // Not a POSIX file system (Windows): rely on the directory's own protection.
        }
    }
}
