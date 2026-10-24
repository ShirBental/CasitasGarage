package home.casita.security.access;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import home.casita.security.CasitaSecurityProperties;
import home.casita.security.store.FileSecurityStore;
import home.casita.security.store.SecurityStore;
import home.casita.security.store.SecurityStore.Invitation;
import home.casita.security.store.StoreUnavailableException;

/**
 * Access administration, for the server only ({@link LocalAdmin}); the {@code casita-access} script calls it.
 * Every change is a conditional write in the security store, done before this answers; each takes effect on
 * the affected sessions' next request. Devices are addressed by their device id (a browser key, {@code b-...}; older
 * records may hold a tailnet node id).
 */
@RestController
@RequestMapping("/admin")
class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final java.util.regex.Pattern INVITE_ID = java.util.regex.Pattern.compile("i-[0-9a-f]{8}");

    record CredentialView(String id, String label, boolean syncable, Instant created, Instant lastUsed) {}

    record DeviceView(String nodeId, String label, String nodeName, String status, Instant authorizedAt, Instant revokedAt, String credentialId) {}

    record MemberView(String name, String status, List<DeviceView> devices, List<CredentialView> credentials) {}

    record InviteView(String id, String member, String label, Instant expiresAt, String state) {}

    record Overview(List<MemberView> members, List<InviteView> invites) {}

    private final CasitaSecurityProperties properties;
    private final SecurityStore store;
    private final Invitations invitations;
    private final Clock clock;

    AdminController(CasitaSecurityProperties properties, SecurityStore store, Invitations invitations, Clock clock) {
        this.properties = properties;
        this.store = store;
        this.invitations = invitations;
        this.clock = clock;
    }

    @ExceptionHandler(StoreUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    Map<String, Object> unavailable() {
        return Map.of("error", "security store unavailable: the change may not have happened; check with a listing before relying on it");
    }

    @GetMapping("/access")
    Overview overview() {
        Instant now = clock.instant();
        List<MemberView> members = new ArrayList<>();
        List<InviteView> open = new ArrayList<>();
        for (String member : properties.family()) {
            String status = store.member(member).map(m -> m.status().name()).orElse("UNKNOWN");
            List<DeviceView> devices = store.devicesOf(member).stream().map(d -> new DeviceView(d.nodeId(), d.label(), d.nodeName(),
                    d.status().name(), d.authorizedAt(), d.revokedAt(), d.credentialId())).toList();
            List<CredentialView> credentials = store.passkeysOf(member).stream().map(p -> new CredentialView(p.id(), p.record().getLabel(),
                    p.record().isBackupEligible(), p.record().getCreated(), p.record().getLastUsed())).toList();
            members.add(new MemberView(member, status, devices, credentials));
            for (Invitation i : store.invitationsOf(member)) {
                if (i.expired(now)) continue;
                if (i.status() == SecurityStore.InvitationStatus.PENDING) open.add(new InviteView(i.inviteId(), member, i.label(), i.expiresAt(), "pending"));
                else if (i.enrolling(now)) open.add(new InviteView(i.inviteId(), member, i.label(), i.expiresAt(), "enrolling"));
            }
        }
        return new Overview(members, open);
    }

    /** A new invitation for one device. Its link is written to an owner-only file; this answer never contains it. */
    @PostMapping("/invites")
    Map<String, Object> invite(@RequestParam String member, @RequestParam String label) throws IOException {
        if (!properties.family().contains(member)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not in casita.security.family");
        if (store.member(member).filter(SecurityStore.Member::active).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "member revoked");
        }
        String clean = label.strip();
        if (clean.isEmpty() || clean.length() > 60 || clean.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "label: 1 to 60 printable characters");
        }
        Invitations.Created created = invitations.create(member, clean);
        Path dir = properties.dataDir().resolve("invites");
        Files.createDirectories(dir);
        FileSecurityStore.restrict(dir, "rwx------");
        Path file = dir.resolve(created.invitation().inviteId() + ".txt");
        Files.createFile(file);
        FileSecurityStore.restrict(file, "rw-------");
        String base = properties.allowedOrigins().stream().sorted().findFirst().orElseThrow();
        Files.writeString(file, base + "/login/ott?token=" + created.token() + "\n", StandardCharsets.UTF_8);
        log.info("Invite {} for {} ({}) written to {}, expires {}", created.invitation().inviteId(), member, clean, file,
                created.invitation().expiresAt());
        return Map.of("id", created.invitation().inviteId(), "member", member, "label", clean,
                "expiresAt", created.invitation().expiresAt().toString(), "file", file.toString());
    }

    /**
     * Revokes an invitation. With its link file at hand (the usual case) this is one conditional write on the
     * base table, by the token's hash: no index, so even an invitation created a moment ago is found. Without
     * the file, the id is looked up through an index, which may not list a just-created invitation yet.
     * Only an answer of {@code "revoked": true} means the link can't be used; the link file is deleted only then
     * (or when the invitation already enrolled its device). Anything else is an error status, and the file stays.
     */
    @DeleteMapping("/invites/{id}")
    ResponseEntity<Map<String, Object>> revokeInvite(@PathVariable String id) throws IOException {
        if (!INVITE_ID.matcher(id).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not an invite id");
        Path file = properties.dataDir().resolve("invites").resolve(id + ".txt");
        Optional<String> token = tokenIn(file);
        SecurityStore.RevokeOutcome outcome = token.isPresent() ? invitations.revoke(token.get(), id) : invitations.revokeById(id);
        log.info("Invite {} revocation: {}{}", id, outcome, token.isPresent() ? "" : " (by id: no link file)");
        return switch (outcome) {
            case REVOKED, ALREADY_REVOKED -> {
                Files.deleteIfExists(file);
                yield ResponseEntity.ok(Map.of("revoked", true, "state", outcome == SecurityStore.RevokeOutcome.REVOKED ? "revoked" : "already-revoked"));
            }
            case ALREADY_ENROLLED -> {
                Files.deleteIfExists(file); // the token has been used: it can't be redeemed again
                yield ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("revoked", false, "state", "already-enrolled",
                        "next", "the invitation already enrolled its device: revoke that device (casita-access list)"));
            }
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("revoked", false, "state", "not-found",
                    "next", token.isPresent() ? "no invitation matches this link file" : "no link file, and no invitation listed with this id "
                            + "(one created seconds ago may not be listed yet): run it again"));
        };
    }

    /** The token in an invite's link file ({@code <origin>/login/ott?token=...}), if the file is there. */
    private static Optional<String> tokenIn(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return Optional.empty();
        String link = Files.readString(file, StandardCharsets.UTF_8).trim();
        int at = link.indexOf("token=");
        return at < 0 ? Optional.empty() : Optional.of(link.substring(at + "token=".length())).filter(t -> !t.isBlank());
    }

    @DeleteMapping("/devices/{nodeId}")
    Map<String, Object> revokeDevice(@PathVariable String nodeId) {
        boolean revoked = store.revokeDevice(nodeId);
        log.info("Device {} revoked: {}", nodeId, revoked);
        return Map.of("revoked", revoked);
    }

    /** A revoked node may then be enrolled for any member (through a new invite). */
    @PostMapping("/devices/{nodeId}/release")
    Map<String, Object> releaseDevice(@PathVariable String nodeId) {
        boolean released = store.releaseDevice(nodeId);
        log.info("Device {} released: {}", nodeId, released);
        return Map.of("released", released);
    }

    @DeleteMapping("/credentials/{id}")
    Map<String, Object> revokeCredential(@PathVariable String id) {
        Bytes credentialId;
        try {
            credentialId = Bytes.fromBase64(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not a credential id");
        }
        boolean revoked = store.revokePasskey(credentialId);
        log.info("Passkey {} revoked: {}", id.length() > 8 ? id.substring(0, 8) + "…" : id, revoked);
        return Map.of("revoked", revoked);
    }

    /** The member is revoked first (that alone refuses them everywhere); their devices, passkeys and invitations follow. */
    @DeleteMapping("/members/{member}")
    Map<String, Object> revokeMember(@PathVariable String member) {
        if (!store.revokeMember(member)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such member");
        log.info("Member {} revoked", member);
        try {
            SecurityStore.Cleanup c = store.cleanUpMember(member);
            log.info("Cleanup for {}: {} devices, {} passkeys, {} invites", member, c.devices(), c.passkeys(), c.invitations());
            return Map.of("revoked", true, "devices", c.devices(), "credentials", c.passkeys(), "invites", c.invitations());
        } catch (StoreUnavailableException e) {
            log.warn("Member {} revoked; cleanup incomplete, run it again", member);
            return Map.of("revoked", true, "cleanup", "incomplete: run revoke-member again");
        }
    }
}
