package home.casita.security.shortcut;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import home.casita.garage.Action;
import home.casita.security.store.SecurityStore;
import home.casita.security.store.SecurityStore.ShortcutToken;
import home.casita.security.store.SecurityStore.ShortcutView;

/**
 * Shortcut tokens: per-phone credentials for Apple Shortcuts and Siri, which can't use passkeys or the browser key
 * (docs/SHORTCUTS.md). This makes and checks the tokens themselves; the {@link SecurityStore} keeps them, with members,
 * passkeys, devices and invitations (Firestore in production), and is the only place that decides whether one is valid.
 *
 * <ul>
 *   <li>A token is {@value #PREFIX} followed by 256 random bits (base64url). It is shown once, when created; the store
 *       keeps only its SHA-256. (A fast hash is right here: the secret is 256 random bits, so there is nothing to
 *       brute-force.)</li>
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
    /** A token's id: 48 random bits, for listing and revoking. Says nothing about the token. */
    public static final Pattern ID = Pattern.compile("st-[0-9a-f]{12}");
    /** {@value #PREFIX} + 32 bytes in unpadded base64url (43 characters). */
    private static final Pattern FORMAT = Pattern.compile(Pattern.quote(PREFIX) + "[A-Za-z0-9_-]{43}");

    /** @param secret the token itself: give it to the person once, never store or log it */
    public record Created(ShortcutToken token, String secret) {
        @Override
        public String toString() {
            return "Created[" + token + "]";
        }
    }

    private final SecurityStore store;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ShortcutTokens(SecurityStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * A new token for a member's device, stored atomically before this returns.
     *
     * @throws IllegalArgumentException for a door, action or lifetime a token can't have
     * @throws IllegalStateException    if the member isn't active (or, vanishingly unlikely, the token or id exists)
     */
    public Created create(String member, String label, String door, Action action, Duration lifetime) {
        if (!DOORS.contains(door)) throw new IllegalArgumentException("a Shortcut token can be for " + DOORS + " only");
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("a Shortcut token can " + ACTIONS + " only");
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(MAX_LIFETIME) > 0) {
            throw new IllegalArgumentException("lifetime: more than zero and at most " + MAX_LIFETIME.toDays() + " days");
        }
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        byte[] idBytes = new byte[6];
        random.nextBytes(idBytes);
        Instant now = clock.instant();
        ShortcutToken token = new ShortcutToken(hash(secret), "st-" + HexFormat.of().formatHex(idBytes), member, label, door, action.name(),
                SecurityStore.Status.ACTIVE, now, now.plus(lifetime), null);
        store.createShortcutToken(token);
        return new Created(token, secret);
    }

    /**
     * What the store holds for this secret: the token (revoked and expired ones included; the caller decides and logs
     * why it refuses) and its member. Empty, without reading the store, for anything that isn't a well-formed token.
     *
     * @throws home.casita.security.store.StoreUnavailableException if the store can't be read: refuse
     */
    public Optional<ShortcutView> lookUp(String secret) {
        if (secret == null || !FORMAT.matcher(secret).matches()) return Optional.empty();
        return Optional.of(store.shortcutView(hash(secret)));
    }

    /** Every token of these members, oldest first per member. */
    public List<ShortcutToken> list(Collection<String> members) {
        List<ShortcutToken> all = new ArrayList<>();
        for (String member : members) all.addAll(store.shortcutTokensOf(member));
        return all;
    }

    /** @return true if the token is revoked now (also when it already was); false if there is no such token */
    public boolean revoke(String id) {
        return ID.matcher(id).matches() && store.revokeShortcutToken(id);
    }

    static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
