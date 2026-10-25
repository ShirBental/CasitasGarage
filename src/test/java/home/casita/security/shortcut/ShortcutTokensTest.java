package home.casita.security.shortcut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import home.casita.garage.Action;
import home.casita.security.store.TestClock;

/** Shortcut tokens as kept: only hashes, owner-only, Ford and open only, revocable, expiring. */
class ShortcutTokensTest {

    @TempDir
    Path data;

    private final TestClock clock = new TestClock();

    private ShortcutTokens tokens() {
        return new ShortcutTokens(data, clock);
    }

    @Test
    void aNewTokenIs256RandomBitsShownOnceAndOnlyItsHashIsKept() throws Exception {
        ShortcutTokens tokens = tokens();
        ShortcutTokens.Created created = tokens.create("shir", "Shir's iPhone", "ford", Action.OPEN, ShortcutTokens.DEFAULT_LIFETIME);

        assertThat(created.secret()).startsWith(ShortcutTokens.PREFIX).hasSize(ShortcutTokens.PREFIX.length() + 43);
        assertThat(created.token().id()).matches(ShortcutTokens.ID);
        assertThat(created.token().hash()).isEqualTo(ShortcutTokens.hash(created.secret())).hasSize(64);
        assertThat(created.token().expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(180)));

        Path file = data.resolve("shortcut-tokens.json");
        String stored = Files.readString(file);
        assertThat(stored).doesNotContain(created.secret()).doesNotContain(created.secret().substring(ShortcutTokens.PREFIX.length()))
                .contains(created.token().hash());
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(created.toString()).doesNotContain(created.secret());
        assertThat(created.token().toString()).doesNotContain(created.token().hash());
    }

    @Test
    void twoTokensAreDifferent() {
        ShortcutTokens tokens = tokens();
        ShortcutTokens.Created a = tokens.create("shir", "iPhone", "ford", Action.OPEN, Duration.ofDays(1));
        ShortcutTokens.Created b = tokens.create("shir", "iPad", "ford", Action.OPEN, Duration.ofDays(1));
        assertThat(a.secret()).isNotEqualTo(b.secret());
        assertThat(a.token().id()).isNotEqualTo(b.token().id());
    }

    @Test
    void aTokenIsFoundByItsSecretOnlyAndSurvivesARestart() {
        ShortcutTokens.Created created = tokens().create("shir", "iPhone", "ford", Action.OPEN, Duration.ofDays(30));

        ShortcutTokens again = tokens();
        assertThat(again.lookUp(created.secret())).map(ShortcutTokens.Token::id).contains(created.token().id());
        assertThat(again.lookUp(created.token().hash())).isEmpty();
        assertThat(again.lookUp(created.secret() + "x")).isEmpty();
        assertThat(again.lookUp(created.secret().substring(1))).isEmpty();
        assertThat(again.lookUp("")).isEmpty();
        assertThat(again.lookUp(null)).isEmpty();
        assertThat(again.lookUp(ShortcutTokens.PREFIX + "A".repeat(43))).isEmpty();
    }

    @Test
    void revokingKeepsTheRecordAndTakesEffectAtOnce() {
        ShortcutTokens tokens = tokens();
        ShortcutTokens.Created created = tokens.create("shir", "iPhone", "ford", Action.OPEN, Duration.ofDays(30));

        assertThat(tokens.revoke(created.token().id())).isTrue();
        assertThat(tokens.lookUp(created.secret())).get().matches(ShortcutTokens.Token::revoked);
        assertThat(tokens.revoke(created.token().id())).isTrue(); // already revoked: still revoked
        assertThat(tokens.revoke("st-00000000")).isFalse();
        assertThat(tokens().lookUp(created.secret())).get().matches(ShortcutTokens.Token::revoked);
    }

    @Test
    void revokingAMembersTokensLeavesOthersAlone() {
        ShortcutTokens tokens = tokens();
        ShortcutTokens.Created shir1 = tokens.create("shir", "iPhone", "ford", Action.OPEN, Duration.ofDays(30));
        ShortcutTokens.Created shir2 = tokens.create("shir", "iPad", "ford", Action.OPEN, Duration.ofDays(30));
        ShortcutTokens.Created shahar = tokens.create("shahar", "iPhone", "ford", Action.OPEN, Duration.ofDays(30));

        assertThat(tokens.revokeAllOf("shir")).isEqualTo(2);
        assertThat(tokens.lookUp(shir1.secret())).get().matches(ShortcutTokens.Token::revoked);
        assertThat(tokens.lookUp(shir2.secret())).get().matches(ShortcutTokens.Token::revoked);
        assertThat(tokens.lookUp(shahar.secret())).get().matches(t -> !t.revoked());
        assertThat(tokens.revokeAllOf("shir")).isZero();
    }

    @Test
    void aTokenExpires() {
        ShortcutTokens tokens = tokens();
        ShortcutTokens.Token token = tokens.create("shir", "iPhone", "ford", Action.OPEN, Duration.ofDays(1)).token();
        assertThat(token.expired(clock.instant())).isFalse();
        clock.advance(Duration.ofDays(1));
        assertThat(token.expired(clock.instant())).isTrue();
    }

    @Test
    void onlyOpeningFordForAtMostAYear() {
        ShortcutTokens tokens = tokens();
        assertThatThrownBy(() -> tokens.create("shir", "iPhone", "toyota", Action.OPEN, Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.create("shir", "iPhone", "ford", Action.CLOSE, Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.create("shir", "iPhone", "ford", Action.OPEN, Duration.ofDays(366)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.create("shir", "iPhone", "ford", Action.OPEN, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tokens.list()).isEmpty();
        assertThat(data.resolve("shortcut-tokens.json")).doesNotExist();
    }
}
