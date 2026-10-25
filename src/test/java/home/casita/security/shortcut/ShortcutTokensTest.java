package home.casita.security.shortcut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import home.casita.garage.Action;
import home.casita.security.store.FileSecurityStore;
import home.casita.security.store.SecurityStore;
import home.casita.security.store.TestClock;

/**
 * Making and checking tokens. Where they are kept is the security store's job, tested for every store in
 * SecurityStoreContract; here the file store stands in for it.
 */
class ShortcutTokensTest {

    @TempDir
    Path data;

    private final TestClock clock = new TestClock();
    private FileSecurityStore store;
    private ShortcutTokens tokens;

    @BeforeEach
    void setUp() {
        store = new FileSecurityStore(data, clock);
        store.seedMembers(List.of("shir", "shahar"));
        tokens = new ShortcutTokens(store, clock);
    }

    @Test
    void aNewTokenIs256RandomBitsShownOnceAndOnlyItsHashIsStored() throws Exception {
        ShortcutTokens.Created created = tokens.create("shir", "Shir's iPhone", "ford", Action.OPEN, ShortcutTokens.DEFAULT_LIFETIME);

        assertThat(created.secret()).startsWith(ShortcutTokens.PREFIX).hasSize(ShortcutTokens.PREFIX.length() + 43);
        assertThat(created.token().tokenId()).matches(ShortcutTokens.ID);
        assertThat(created.token().tokenHash()).isEqualTo(ShortcutTokens.hash(created.secret())).hasSize(64);
        assertThat(created.token().expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(180)));
        assertThat(created.token().action()).isEqualTo("OPEN");
        assertThat(created.token().door()).isEqualTo("ford");

        assertThat(store.shortcutView(created.token().tokenHash()).token()).contains(created.token());
        String stored = Files.readString(data.resolve("security.json"));
        assertThat(stored).doesNotContain(created.secret()).doesNotContain(created.secret().substring(ShortcutTokens.PREFIX.length()));
        assertThat(created.toString()).doesNotContain(created.secret());
        assertThat(created.token().toString()).doesNotContain(created.token().tokenHash());
    }

    @Test
    void twoTokensAreDifferent() {
        ShortcutTokens.Created a = tokens.create("shir", "iPhone", "ford", Action.OPEN, Duration.ofDays(1));
        ShortcutTokens.Created b = tokens.create("shir", "iPad", "ford", Action.OPEN, Duration.ofDays(1));
        assertThat(a.secret()).isNotEqualTo(b.secret());
        assertThat(a.token().tokenId()).isNotEqualTo(b.token().tokenId());
    }

    @Test
    void onlyAWellFormedSecretIsLookedUp() {
        ShortcutTokens.Created created = tokens.create("shir", "iPhone", "ford", Action.OPEN, Duration.ofDays(30));

        assertThat(tokens.lookUp(created.secret())).get().satisfies(v -> {
            assertThat(v.token()).contains(created.token());
            assertThat(v.member()).get().extracting(SecurityStore.Member::id).isEqualTo("shir");
        });
        assertThat(tokens.lookUp(created.token().tokenHash())).isEmpty();
        assertThat(tokens.lookUp(created.secret() + "x")).isEmpty();
        assertThat(tokens.lookUp(created.secret().substring(1))).isEmpty();
        assertThat(tokens.lookUp("")).isEmpty();
        assertThat(tokens.lookUp(null)).isEmpty();
        // Well formed but never created: looked up, and the store knows nothing of it.
        assertThat(tokens.lookUp(ShortcutTokens.PREFIX + "A".repeat(43))).get().satisfies(v -> assertThat(v.token()).isEmpty());
    }

    @Test
    void aTokenSurvivesARestartAndSoDoesItsRevocation() {
        ShortcutTokens.Created kept = tokens.create("shir", "iPhone", "ford", Action.OPEN, Duration.ofDays(30));
        ShortcutTokens.Created revoked = tokens.create("shir", "iPad", "ford", Action.OPEN, Duration.ofDays(30));
        assertThat(tokens.revoke(revoked.token().tokenId())).isTrue();

        ShortcutTokens again = new ShortcutTokens(new FileSecurityStore(data, clock), clock);
        assertThat(again.lookUp(kept.secret())).get().satisfies(v -> assertThat(v.token()).get().matches(t -> !t.revoked()));
        assertThat(again.lookUp(revoked.secret())).get().satisfies(v -> assertThat(v.token()).get().matches(SecurityStore.ShortcutToken::revoked));
        assertThat(again.list(List.of("shir", "shahar"))).hasSize(2);
    }

    @Test
    void revokingTakesAnIdOnly() {
        ShortcutTokens.Created created = tokens.create("shir", "iPhone", "ford", Action.OPEN, Duration.ofDays(30));
        assertThat(tokens.revoke("st-000000000000")).isFalse();
        assertThat(tokens.revoke(created.secret())).isFalse();
        assertThat(tokens.revoke(created.token().tokenHash())).isFalse();
        assertThat(tokens.lookUp(created.secret())).get().satisfies(v -> assertThat(v.token()).get().matches(t -> !t.revoked()));
    }

    @Test
    void onlyOpeningFordForAtMostAYearForAnActiveMember() {
        assertThatThrownBy(() -> tokens.create("shir", "iPhone", "toyota", Action.OPEN, Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.create("shir", "iPhone", "ford", Action.CLOSE, Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.create("shir", "iPhone", "ford", Action.OPEN, Duration.ofDays(366)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.create("shir", "iPhone", "ford", Action.OPEN, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        store.revokeMember("shahar");
        assertThatThrownBy(() -> tokens.create("shahar", "iPhone", "ford", Action.OPEN, Duration.ofDays(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tokens.list(List.of("shir", "shahar"))).isEmpty();
    }
}
