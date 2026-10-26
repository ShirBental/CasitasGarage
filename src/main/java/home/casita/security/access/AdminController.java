package home.casita.security.access;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

import home.casita.garage.Action;
import home.casita.security.CasitaSecurityProperties;
import home.casita.security.shortcut.ShortcutTokens;
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

    /** A Shortcut token as listed: never its secret or its hash. */
    record ShortcutTokenView(String id, String member, String label, String door, String action, Instant createdAt,
                             Instant expiresAt, Instant revokedAt, String state) {}

    private final CasitaSecurityProperties properties;
    private final SecurityStore store;
    private final Invitations invitations;
    private final ShortcutTokens shortcutTokens;
    private final Clock clock;

    AdminController(CasitaSecurityProperties properties, SecurityStore store, Invitations invitations, ShortcutTokens shortcutTokens,
                    Clock clock) {
        this.properties = properties;
        this.store = store;
        this.invitations = invitations;
        this.shortcutTokens = shortcutTokens;
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
        activeMember(member);
        String clean = cleanLabel(label);
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

    private void activeMember(String member) {
        if (!properties.family().contains(member)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not in casita.security.family");
        if (store.member(member).filter(SecurityStore.Member::active).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "member revoked");
        }
    }

    private static String cleanLabel(String label) {
        String clean = label.strip();
        if (clean.isEmpty() || clean.length() > 60 || clean.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "label: 1 to 60 printable characters");
        }
        return clean;
    }

    /* ---------- Shortcut tokens (docs/SHORTCUTS.md) ---------- */

    /**
     * A new Shortcut token for one member's device: open the Ford door, nothing else. The answer is the only place the
     * token ever appears: it isn't logged or kept (only its hash is). Copy it into the Shortcut, then clear the terminal.
     */
    @PostMapping("/shortcut-tokens")
    Map<String, Object> createShortcutToken(@RequestParam String member, @RequestParam String label,
                                            @RequestParam(defaultValue = "ford") String door,
                                            @RequestParam(defaultValue = "180") int days) {
        activeMember(member);
        String clean = cleanLabel(label);
        String key = door.strip().toLowerCase(java.util.Locale.ROOT);
        if (!ShortcutTokens.DOORS.contains(key)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "door: a Shortcut token can be for " + ShortcutTokens.DOORS + " only");
        }
        if (days < 1 || days > ShortcutTokens.MAX_LIFETIME.toDays()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "days: 1 to " + ShortcutTokens.MAX_LIFETIME.toDays());
        }
        ShortcutTokens.Created created;
        try {
            created = shortcutTokens.create(member, clean, key, Action.OPEN, Duration.ofDays(days));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage() + ": nothing was created");
        }
        SecurityStore.ShortcutToken t = created.token();
        log.info("Shortcut token {} for {} ({}) created: {} {}, expires {}", t.tokenId(), member, clean, t.action(), t.door(), t.expiresAt());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", t.tokenId());
        answer.put("member", member);
        answer.put("label", clean);
        answer.put("door", t.door());
        answer.put("action", t.action());
        answer.put("expiresAt", t.expiresAt().toString());
        answer.put("token", created.secret());
        answer.put("note", "shown once and not kept: put it in the Shortcut's Authorization header now");
        return answer;
    }

    @GetMapping("/shortcut-tokens")
    List<ShortcutTokenView> shortcutTokens() {
        Instant now = clock.instant();
        return shortcutTokens.list(properties.family()).stream().map(t -> new ShortcutTokenView(t.tokenId(), t.memberId(), t.label(), t.door(), t.action(),
                t.createdAt(), t.expiresAt(), t.revokedAt(), t.revoked() ? "revoked" : t.expired(now) ? "expired" : "active")).toList();
    }

    /** Takes effect on the token's next request. */
    @DeleteMapping("/shortcut-tokens/{id}")
    Map<String, Object> revokeShortcutToken(@PathVariable String id) {
        if (!ShortcutTokens.ID.matcher(id).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "not a Shortcut token id");
        if (!shortcutTokens.revoke(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such Shortcut token (one created seconds ago may not be listed yet: run it again)");
        }
        log.info("Shortcut token {} revoked", id);
        return Map.of("revoked", true);
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

    /**
     * Revokes one browser. Its member's Shortcut tokens are separate credentials and stay active: nothing ties a token
     * to a browser (a Shortcut sends only its token, never the browser key), so they are listed for the admin to revoke
     * by id, or all at once with revoke-member. Never guessed from labels.
     */
    @DeleteMapping("/devices/{nodeId}")
    Map<String, Object> revokeDevice(@PathVariable String nodeId) {
        Optional<String> member = store.device(nodeId).map(SecurityStore.Device::memberId);
        boolean revoked = store.revokeDevice(nodeId);
        log.info("Device {} revoked: {}", nodeId, revoked);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("revoked", revoked);
        if (member.isPresent()) {
            Instant now = clock.instant();
            List<Map<String, String>> active = store.shortcutTokensOf(member.get()).stream().filter(t -> !t.revoked() && !t.expired(now))
                    .map(t -> Map.of("id", t.tokenId(), "label", t.label())).toList();
            answer.put("shortcutTokensStillActive", active);
            if (!active.isEmpty()) {
                answer.put("next", "Shortcut tokens aren't tied to a browser and still work: if this phone has one, "
                        + "casita-access revoke-shortcut-token <id> (or revoke-member for everything)");
            }
        }
        return answer;
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
            log.info("Cleanup for {}: {} devices, {} passkeys, {} invites, {} Shortcut tokens", member, c.devices(), c.passkeys(),
                    c.invitations(), c.shortcutTokens());
            return Map.of("revoked", true, "devices", c.devices(), "credentials", c.passkeys(), "invites", c.invitations(),
                    "shortcutTokens", c.shortcutTokens());
        } catch (StoreUnavailableException e) {
            log.warn("Member {} revoked; cleanup incomplete, run it again", member);
            return Map.of("revoked", true, "cleanup", "incomplete: run revoke-member again");
        }
    }
}
