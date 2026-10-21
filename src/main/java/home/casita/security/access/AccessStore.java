package home.casita.security.access;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Which devices (tailnet nodes) each family member may use Casita from: {@code <data-dir>/devices.json},
 * written whole and atomically, readable by Casita's user only. A device is authorized only through an
 * invite. Revoking marks the entry revoked (kept for the record, as DeviceAuthorizations will in DynamoDB);
 * a revoked entry never authorizes anything again, and a restart reads it back as revoked.
 */
public class AccessStore {

    /**
     * One device authorization.
     *
     * @param id                  short id for administration
     * @param label               what the invite called it ("Shir's iPhone"); a note, not proof of anything
     * @param nodeId              the tailnet node's stable id: what is actually checked
     * @param enrolledCredential  the passkey (base64url id) used to enroll; informational
     * @param revokedAt           when it was revoked; null while authorized
     */
    public record Device(String id, String member, String label, String nodeId, String nodeName, String enrolledCredential,
                         String inviteId, Instant authorizedAt, Instant revokedAt) {
        public boolean active() {
            return revokedAt == null;
        }

        Device revoked(Instant at) {
            return new Device(id, member, label, nodeId, nodeName, enrolledCredential, inviteId, authorizedAt, at);
        }
    }

    record Contents(List<Device> devices) {}

    private final Path file;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Device> devices = new LinkedHashMap<>();

    public AccessStore(Path dataDir, Clock clock) {
        this.file = dataDir.resolve("devices.json");
        this.clock = clock;
        if (Files.exists(file)) {
            json.readValue(file.toFile(), Contents.class).devices().forEach(d -> devices.put(d.id(), d));
        }
    }

    /** Authorizes a node for a member (an earlier authorization of the same node for them is revoked). */
    public synchronized Device authorize(String member, String label, DeviceIdentity node, String credentialId, String inviteId) {
        Instant now = clock.instant();
        devices.replaceAll((k, d) -> d.active() && d.member().equals(member) && d.nodeId().equals(node.nodeId()) ? d.revoked(now) : d);
        byte[] id = new byte[4];
        random.nextBytes(id);
        Device device = new Device("d-" + HexFormat.of().formatHex(id), member, label, node.nodeId(), node.nodeName(), credentialId,
                inviteId, now, null);
        devices.put(device.id(), device);
        write();
        return device;
    }

    public synchronized Optional<Device> find(String member, String nodeId) {
        return devices.values().stream().filter(d -> d.active() && d.member().equals(member) && d.nodeId().equals(nodeId)).findFirst();
    }

    public synchronized List<Device> all() {
        return List.copyOf(devices.values());
    }

    /** Revokes an authorized device; false if there's no such device or it's already revoked. */
    public synchronized boolean revokeDevice(String id) {
        Device device = devices.get(id);
        if (device == null || !device.active()) return false;
        devices.put(id, device.revoked(clock.instant()));
        write();
        return true;
    }

    public synchronized int revokeMember(String member) {
        Instant now = clock.instant();
        int revoked = 0;
        for (Map.Entry<String, Device> e : devices.entrySet()) {
            if (e.getValue().active() && e.getValue().member().equals(member)) {
                e.setValue(e.getValue().revoked(now));
                revoked++;
            }
        }
        if (revoked > 0) write();
        return revoked;
    }

    private void write() {
        try {
            Path dir = file.getParent();
            Files.createDirectories(dir);
            restrict(dir, "rwx------");
            Path tmp = Files.createTempFile(dir, "devices", ".tmp");
            restrict(tmp, "rw-------");
            json.writeValue(tmp.toFile(), new Contents(new ArrayList<>(devices.values())));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't save device authorizations", e);
        }
    }

    static void restrict(Path path, String permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException e) {
            // Not a POSIX file system.
        }
    }
}
