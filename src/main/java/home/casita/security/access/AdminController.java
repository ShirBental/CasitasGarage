package home.casita.security.access;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import home.casita.security.CasitaSecurityProperties;
import home.casita.security.FilePasskeyStore;

/**
 * Access administration, for the server only ({@link LocalAdmin}); the {@code casita-access} script calls it.
 * Revoking takes effect on the affected sessions' next request (AccessCheckFilter) and is on disk before this
 * answers, so a restart can't undo it.
 */
@RestController
@RequestMapping("/admin")
class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    record CredentialView(String id, String label, boolean syncable, Instant created, Instant lastUsed) {}

    record DeviceView(String id, String label, String nodeName, String nodeId, Instant authorizedAt, String enrolledCredential,
                      Instant revokedAt) {}

    record MemberView(String name, List<DeviceView> devices, List<CredentialView> credentials) {}

    record InviteView(String id, String member, String label, Instant expiresAt, String state) {}

    record Overview(List<MemberView> members, List<InviteView> invites) {}

    private final CasitaSecurityProperties properties;
    private final FilePasskeyStore passkeys;
    private final AccessStore access;
    private final Invites invites;

    AdminController(CasitaSecurityProperties properties, FilePasskeyStore passkeys, AccessStore access, Invites invites) {
        this.properties = properties;
        this.passkeys = passkeys;
        this.access = access;
        this.invites = invites;
    }

    @GetMapping("/access")
    Overview overview() {
        List<MemberView> members = new ArrayList<>();
        for (String member : properties.family()) {
            List<DeviceView> devices = access.all().stream().filter(d -> d.member().equals(member))
                    .map(d -> new DeviceView(d.id(), d.label(), d.nodeName(), d.nodeId(), d.authorizedAt(), d.enrolledCredential(), d.revokedAt())).toList();
            List<CredentialView> credentials = passkeys.credentialsFor(member).stream().map(AdminController::view).toList();
            members.add(new MemberView(member, devices, credentials));
        }
        List<InviteView> pending = new ArrayList<>();
        invites.pending().forEach(i -> pending.add(new InviteView(i.id(), i.member(), i.label(), i.expiresAt(), "pending")));
        invites.enrolling().forEach(i -> pending.add(new InviteView(i.id(), i.member(), i.label(), i.expiresAt(), "enrolling")));
        return new Overview(members, pending);
    }

    /** A new invite for one device. Its link is written to an owner-only file; this answer never contains it. */
    @PostMapping("/invites")
    Map<String, Object> invite(@RequestParam String member, @RequestParam String label) throws IOException {
        if (!properties.family().contains(member)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not in casita.security.family");
        String clean = label.strip();
        if (clean.isEmpty() || clean.length() > 60 || clean.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "label: 1 to 60 printable characters");
        }
        Invites.Created created = invites.create(member, clean);
        Path dir = properties.dataDir().resolve("invites");
        Files.createDirectories(dir);
        AccessStore.restrict(dir, "rwx------");
        Path file = dir.resolve(created.invite().id() + ".txt");
        Files.createFile(file);
        AccessStore.restrict(file, "rw-------");
        String base = properties.allowedOrigins().stream().sorted().findFirst().orElseThrow();
        Files.writeString(file, base + "/login/ott?token=" + created.token() + "\n", StandardCharsets.UTF_8);
        log.info("Invite {} for {} ({}) written to {}, expires {}", created.invite().id(), member, clean, file, created.invite().expiresAt());
        return Map.of("id", created.invite().id(), "member", member, "label", clean, "expiresAt", created.invite().expiresAt().toString(),
                "file", file.toString());
    }

    @DeleteMapping("/invites/{id}")
    Map<String, Object> revokeInvite(@PathVariable String id) throws IOException {
        boolean revoked = invites.revoke(id);
        Files.deleteIfExists(properties.dataDir().resolve("invites").resolve(id.replaceAll("[^a-z0-9-]", "") + ".txt"));
        log.info("Invite {} revoked: {}", id, revoked);
        return Map.of("revoked", revoked);
    }

    @DeleteMapping("/devices/{id}")
    Map<String, Object> revokeDevice(@PathVariable String id) {
        boolean revoked = access.revokeDevice(id);
        log.info("Device {} revoked: {}", id, revoked);
        return Map.of("revoked", revoked);
    }

    @DeleteMapping("/credentials/{id}")
    Map<String, Object> revokeCredential(@PathVariable String id) {
        Bytes credentialId;
        try {
            credentialId = Bytes.fromBase64(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not a credential id");
        }
        boolean exists = passkeys.findByCredentialId(credentialId) != null;
        if (exists) passkeys.delete(credentialId);
        log.info("Passkey {} revoked: {}", id.length() > 8 ? id.substring(0, 8) + "…" : id, exists);
        return Map.of("revoked", exists);
    }

    @DeleteMapping("/members/{member}")
    Map<String, Object> revokeMember(@PathVariable String member) {
        int devices = access.revokeMember(member);
        int credentials = passkeys.deleteAllFor(member);
        int pendingInvites = invites.revokeMember(member);
        log.info("All access for {} revoked: {} devices, {} passkeys, {} invites", member, devices, credentials, pendingInvites);
        return Map.of("devices", devices, "credentials", credentials, "invites", pendingInvites);
    }

    private static CredentialView view(CredentialRecord r) {
        return new CredentialView(r.getCredentialId().toBase64UrlString(), r.getLabel(), r.isBackupEligible(), r.getCreated(), r.getLastUsed());
    }
}
