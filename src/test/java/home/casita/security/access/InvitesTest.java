package home.casita.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;

class InvitesTest {

    /** A clock the test moves. */
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-16T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private final TestClock clock = new TestClock();
    private final Invites invites = new Invites(clock);

    @Test
    void anInviteIsForOneMemberAndDeviceAndLastsFiveMinutes() {
        Invites.Created created = invites.create("shir", "Shir's iPhone");

        assertThat(created.invite().member()).isEqualTo("shir");
        assertThat(created.invite().label()).isEqualTo("Shir's iPhone");
        assertThat(Duration.between(created.invite().createdAt(), created.invite().expiresAt())).isEqualTo(Duration.ofMinutes(5));
        assertThat(created.token()).hasSizeGreaterThanOrEqualTo(43); // 256 random bits
        assertThat(created.toString()).doesNotContain(created.token());
        assertThat(invites.pending().toString()).doesNotContain(created.token());
    }

    @Test
    void itWorksOnce() {
        String token = invites.create("shir", "Shir's iPhone").token();

        assertThat(invites.redeem(token)).isPresent();
        assertThat(invites.redeem(token)).isEmpty();
    }

    @Test
    void itExpires() {
        String token = invites.create("shir", "Shir's iPhone").token();
        clock.advance(Duration.ofMinutes(5));

        assertThat(invites.redeem(token)).isEmpty();
        assertThat(invites.pending()).isEmpty();
    }

    @Test
    void anEnrollmentEndsWhenItsInviteWouldHaveExpired() {
        Invites.Created created = invites.create("shir", "Shir's iPhone");
        invites.redeem(created.token());
        clock.advance(Duration.ofMinutes(4));
        assertThat(invites.activeEnrollment(created.invite().id())).isPresent();

        clock.advance(Duration.ofMinutes(1));
        assertThat(invites.activeEnrollment(created.invite().id())).isEmpty();
    }

    @Test
    void itCanBeRevokedBeforeOrDuringEnrollment() {
        Invites.Created pending = invites.create("shir", "Shir's iPhone");
        assertThat(invites.revoke(pending.invite().id())).isTrue();
        assertThat(invites.redeem(pending.token())).isEmpty();

        Invites.Created enrolling = invites.create("shir", "Shir's iPad");
        invites.redeem(enrolling.token());
        assertThat(invites.revoke(enrolling.invite().id())).isTrue();
        assertThat(invites.activeEnrollment(enrolling.invite().id())).isEmpty();
        assertThat(invites.revoke("i-00000000")).isFalse();
    }

    @Test
    void finishingAnEnrollmentUsesTheInviteUp() {
        Invites.Created created = invites.create("shir", "Shir's iPhone");
        invites.redeem(created.token());
        invites.finish(created.invite().id());

        assertThat(invites.activeEnrollment(created.invite().id())).isEmpty();
        assertThat(invites.enrolling()).isEmpty();
    }

    @Test
    void revokingAMemberRevokesOnlyTheirInvites() {
        invites.create("shir", "a");
        String hubby = invites.create("hubby", "b").token();

        assertThat(invites.revokeMember("shir")).isEqualTo(1);
        assertThat(invites.redeem(hubby)).isPresent();
    }

    @Test
    void springsTokenGenerationIsOff() {
        assertThatThrownBy(() -> invites.generate(new GenerateOneTimeTokenRequest("shir"))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRestartVoidsEveryInvite() {
        String token = invites.create("shir", "Shir's iPhone").token();

        Invites afterRestart = new Invites(clock);

        assertThat(afterRestart.redeem(token)).isEmpty();
    }
}
