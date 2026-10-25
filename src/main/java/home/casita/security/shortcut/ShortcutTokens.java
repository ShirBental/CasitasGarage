package home.casita.security.shortcut;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import home.casita.garage.Action;
import home.casita.security.store.FileSecurityStore;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shortcut tokens: per-phone credentials for Apple Shortcuts and Siri, which can't use passkeys or the browser key
 * (docs/SHORTCUTS.md). Each token belongs to one member and one device label, and allows one action on one door.
 *
 * <ul>
 *   <li>A token is {@value #PREFIX} followed by 256 random bits (base64url). It is shown once, when created; only its
 *       SHA-256 is kept. (A fast hash is right here: the secret is 256 random bits, so there is nothing to brute-force.)</li>
 *   <li>Kept in {@code <data-dir>/shortcut-tokens.json}, owner-only, written atomically: next to the admin secret and the
 *       invite links, on the server only. The security store is unchanged.</li>
 *   <li>Revoking never deletes: a revoked token stays listed with when it was revoked.</li>
 *   <li>For now a token can only {@link Action#OPEN} the Ford door ({@link #DOORS}, {@link #ACTIONS}).</li>
 * </ul>
 *
 * <p>A token is a bearer secret. Holding it is all it proves: not who is holding the phone, not that the phone is
 * unlocked, not that anyone said anything to Siri.
 */
public class ShortcutTokens {

    public static final String PREFIX = "casita_st_";
    /** The doors a token may be created for. Ford only, for now. */
    public static final Set<String> DOORS = Set.of("ford");
    /** The actions a token may be created for. Open only, for now: closing stays in the app. */
    public static final Set<Action> ACTIONS = Set.of(Action.OPEN);
    public static final Duration DEFAULT_LIFETIME = Duration.ofDays(180);
    public static final Duration MAX_LIFETIME = Duration.ofDays(365);
    public static final Pattern ID = Pattern.compile("st-[0-9a-f]{8}");
    /** {@value #PREFIX} + 32 bytes in unpadded base64url (43 characters). */
    private static final Pattern FORMAT = Pattern.compile(Pattern.quote(PREFIX) + "[A-Za-z0-9_-]{43}");

    /**
     * One token, as kept. {@code hash} is the SHA-256 (hex) of the whole token; the token itself is never stored.
     */
    public record Token(String id, String hash, String member, String label, String door, Action action,
                        Instant createdAt, Instant expiresAt, Instant revokedAt) {

        public boolean revoked() {
            return revokedAt != null;
        }

        public boolean expired(Instant now) {
            return !now.isBefore(expiresAt);
        }

        @Override
        public String toString() {
            return "ShortcutToken[" + id + ", " + member + ", " + label + ", " + action + " " + door + "]";
        }
    }

    /** @param secret the token itself: give it to the person once, never store or log it */
    public record Created(Token token, String secret) {
        @Override
        public String toString() {
            return "Created[" + token + "]";
        }
    }

    record Contents(List<Token> tokens) {}

    private final Path file;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Token> byHash = new LinkedHashMap<>();

    public ShortcutTokens(Path dataDir, Clock clock) {
        this.file = dataDir.resolve("shortcut-tokens.json");
        this.clock = clock;
        try {
            if (Files.exists(file)) {
                FileSecurityStore.restrict(file, "rw-------");
                Contents contents = json.readValue(file.toFile(), Contents.class);
                if (contents.tokens() != null) contents.tokens().forEach(t -> byHash.put(t.hash(), t));
            }
        } catch (JacksonException e) {
            throw new IllegalStateException("Can't read " + file + ": " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("Can't read " + file, e);
        }
    }

    /** A new token for a member's device. The caller checks that the member exists and is active. */
    public synchronized Created create(String member, String label, String door, Action action, Duration lifetime) {
        if (!DOORS.contains(door)) throw new IllegalArgumentException("a Shortcut token can be for " + DOORS + " only");
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("a Shortcut token can " + ACTIONS + " only");
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(MAX_LIFETIME) > 0) {
            throw new IllegalArgumentException("lifetime: more than zero and at most " + MAX_LIFETIME.toDays() + " days");
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String id;
        do {
            byte[] idBytes = new byte[4];
            random.nextBytes(idBytes);
            id = "st-" + HexFormat.of().formatHex(idBytes);
        } while (find(id).isPresent());
        Instant now = clock.instant();
        Token token = new Token(id, hash(secret), member, label, door, action, now, now.plus(lifetime), null);
        Map<String, Token> next = new LinkedHashMap<>(byHash);
        next.put(token.hash(), token);
        save(next);
        return new Created(token, secret);
    }

    /**
     * The token this secret is, if it is a well-formed token Casita created; revoked and expired ones included (the
     * caller decides and logs why it refuses). Anything malformed is empty without a lookup.
     */
    public synchronized Optional<Token> lookUp(String secret) {
        if (secret == null || !FORMAT.matcher(secret).matches()) return Optional.empty();
        return Optional.ofNullable(byHash.get(hash(secret)));
    }

    public synchronized List<Token> list() {
        return List.copyOf(byHash.values());
    }

    public synchronized Optional<Token> find(String id) {
        return byHash.values().stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /** @return true if the token is revoked now (also when it already was); false if there is no such token */
    public synchronized boolean revoke(String id) {
        Optional<Token> token = find(id);
        if (token.isEmpty()) return false;
        if (token.get().revoked()) return true;
        Map<String, Token> next = new LinkedHashMap<>(byHash);
        next.put(token.get().hash(), withRevoked(token.get(), clock.instant()));
        save(next);
        return true;
    }

    /** Revokes every token of a member that isn't revoked yet. @return how many */
    public synchronized int revokeAllOf(String member) {
        Instant now = clock.instant();
        Map<String, Token> next = new LinkedHashMap<>(byHash);
        int count = 0;
        for (Token t : byHash.values()) {
            if (t.member().equals(member) && !t.revoked()) {
                next.put(t.hash(), withRevoked(t, now));
                count++;
            }
        }
        if (count > 0) save(next);
        return count;
    }

    private static Token withRevoked(Token t, Instant at) {
        return new Token(t.id(), t.hash(), t.member(), t.label(), t.door(), t.action(), t.createdAt(), t.expiresAt(), at);
    }

    /** Writes the whole set to a new owner-only file and moves it into place; memory changes only if that worked. */
    private void save(Map<String, Token> next) {
        try {
            Path dir = file.getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "shortcut-tokens", ".tmp");
            try {
                FileSecurityStore.restrict(tmp, "rw-------");
                json.writeValue(tmp.toFile(), new Contents(new ArrayList<>(next.values())));
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Can't write " + file, e);
        } catch (JacksonException e) {
            throw new IllegalStateException("Can't write " + file + ": " + e.getOriginalMessage(), e);
        }
        byHash.clear();
        byHash.putAll(next);
    }

    static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
