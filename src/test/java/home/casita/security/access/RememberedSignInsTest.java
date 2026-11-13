package home.casita.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseCookie;

import home.casita.security.RememberProperties;
import home.casita.security.store.TestClock;

/** The remembered sign-in cookie on its own: what it claims, and when it stops being accepted. */
class RememberedSignInsTest {

    private static final String NODE = "b-5a17000000000000000000000000000a";
    private static final String CREDENTIAL = "c2hpcnMtcGFzc2tleQ";
    private static final String AUTH = "i-1a2b3c4d";

    @TempDir
    Path dir;
    private TestClock clock;
    private RememberedSignIns signIns;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        signIns = with(Duration.ofDays(30), Duration.ofDays(14));
    }

    private RememberedSignIns with(Duration maxAge, Duration idle) {
        return new RememberedSignIns(dir.resolve("remember.key"), new RememberProperties(maxAge, idle), clock);
    }

    private String issue() {
        return signIns.issue("shir", CREDENTIAL, NODE, AUTH).orElseThrow().getValue();
    }

    @Test
    void aSignInIsIssuedAsAHostOnlySecureHttpOnlyCookieAndVerifiesToWhatItClaims() throws Exception {
        ResponseCookie cookie = signIns.issue("shir", CREDENTIAL, NODE, AUTH).orElseThrow();

        assertThat(cookie.getName()).isEqualTo("__Host-casita-signin");
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(14));
        assertThat(signIns.verify(cookie.getValue())).get().satisfies(c -> {
            assertThat(c.member()).isEqualTo("shir");
            assertThat(c.credentialId()).isEqualTo(CREDENTIAL);
            assertThat(c.nodeId()).isEqualTo(NODE);
            assertThat(c.authorization()).isEqualTo(AUTH);
            assertThat(c.verifiedAt()).isEqualTo(clock.instant());
            assertThat(c.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
        });
        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("remember.key")))).isEqualTo("rw-------");
    }

    @Test
    void anythingAlteredOrSignedWithAnotherKeyIsRefused() throws Exception {
        String value = issue();
        String[] parts = value.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String hubby = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.replace("shir|", "hubby|").getBytes(StandardCharsets.UTF_8));

        assertThat(signIns.verify(parts[0] + "." + hubby + "." + parts[2])).isEmpty();
        assertThat(signIns.verify(value.substring(0, value.length() - 2) + "AA")).isEmpty();
        assertThat(signIns.verify("v1." + parts[1] + "." + parts[2])).isEmpty(); // the earlier format, unsigned for v2
        String other = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.replace("|" + AUTH + "|", "|i-99999999|").getBytes(StandardCharsets.UTF_8));
        assertThat(signIns.verify(parts[0] + "." + other + "." + parts[2])).isEmpty(); // another authorization: the MAC fails
        for (String junk : new String[] {null, "", "v1", "v1..", "v1.!!.!!", "x".repeat(5000)}) assertThat(signIns.verify(junk)).isEmpty();

        Files.delete(dir.resolve("remember.key")); // the kill switch: a new key, so every cookie issued before is refused
        assertThat(with(Duration.ofDays(30), Duration.ofDays(14)).verify(value)).isEmpty();
    }

    @Test
    void usedEveryDayItStillEndsMaxAgeAfterTheLastFaceId() {
        String value = issue();
        for (int day = 1; day < 30; day++) {
            clock.advance(Duration.ofDays(1));
            RememberedSignIns.Claim claim = signIns.verify(value).orElseThrow();
            value = signIns.renew(claim).getValue();
        }
        clock.advance(Duration.ofDays(1)); // day 30
        assertThat(signIns.verify(value)).isEmpty();
    }

    @Test
    void unusedForTheIdleTimeItLapses() {
        String value = issue();
        clock.advance(Duration.ofDays(14).minusSeconds(1));
        assertThat(signIns.verify(value)).isPresent();
        clock.advance(Duration.ofSeconds(1));
        assertThat(signIns.verify(value)).isEmpty();
    }

    @Test
    void renewingMovesOnlyTheIdleDeadlineAndNeverPastExpiry() {
        String value = issue();
        clock.advance(Duration.ofDays(10));
        value = signIns.renew(signIns.verify(value).orElseThrow()).getValue();
        clock.advance(Duration.ofDays(10));
        RememberedSignIns.Claim before = signIns.verify(signIns.renew(signIns.verify(value).orElseThrow()).getValue()).orElseThrow();
        clock.advance(Duration.ofDays(1));
        RememberedSignIns.Claim claim = signIns.verify(signIns.renew(before).getValue()).orElseThrow();

        assertThat(claim.verifiedAt()).isEqualTo(before.verifiedAt());
        assertThat(claim.expiresAt()).isEqualTo(before.expiresAt());
        assertThat(claim.idleUntil()).isEqualTo(claim.expiresAt()); // 9 days left, less than the 14 idle days
    }

    @Test
    void loweringTheSettingsShortensCookiesAlreadyIssued() {
        String value = issue();
        clock.advance(Duration.ofDays(8));
        assertThat(signIns.verify(value)).isPresent();
        assertThat(with(Duration.ofDays(7), Duration.ofDays(7)).verify(value)).isEmpty();
        assertThat(with(Duration.ofDays(30), Duration.ofDays(1)).verify(value)).isEmpty(); // idle until is beyond the new idle
    }

    @Test
    void offMeansNothingIsIssuedOrAccepted() {
        String value = issue();
        RememberedSignIns off = with(Duration.ZERO, Duration.ZERO);
        assertThat(off.enabled()).isFalse();
        assertThat(off.issue("shir", CREDENTIAL, NODE, AUTH)).isEmpty();
        assertThat(off.verify(value)).isEmpty();
    }

    @Test
    void aSignInFromTheFutureIsRefused() {
        clock.advance(Duration.ofHours(1));
        String value = issue();
        TestClock earlier = new TestClock();
        assertThat(new RememberedSignIns(dir.resolve("remember.key"), new RememberProperties(Duration.ofDays(30), Duration.ofDays(14)), earlier)
                .verify(value)).isEmpty();
    }

    @Test
    void settingsAreCheckedWhenBound() {
        assertThatThrownBy(() -> new RememberProperties(Duration.ofDays(91), Duration.ofDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RememberProperties(Duration.ofDays(7), Duration.ofDays(8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RememberProperties(Duration.ofDays(7), Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RememberProperties(Duration.ofDays(-1), Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new RememberProperties(Duration.ZERO, Duration.ZERO).enabled()).isFalse();
    }

    @Test
    void noAuthorizationToBindToMeansNoCookie() {
        assertThat(signIns.issue("shir", CREDENTIAL, NODE, null)).isEmpty();
        assertThat(signIns.issue("shir", CREDENTIAL, NODE, "not|safe")).isEmpty();
    }

    @Test
    void faceIdIsNeededEveryMaxAgeAndEvery30DaysWhenRememberingIsOff() {
        assertThat(signIns.faceIdUntil(clock.instant())).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
        assertThat(with(Duration.ofDays(7), Duration.ofDays(7)).faceIdUntil(clock.instant())).isEqualTo(clock.instant().plus(Duration.ofDays(7)));
        assertThat(with(Duration.ZERO, Duration.ZERO).faceIdUntil(clock.instant())).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
    }

    @Test
    void theClearedCookieRemovesIt() {
        ResponseCookie cleared = RememberedSignIns.cleared();
        assertThat(cleared.getName()).isEqualTo(RememberedSignIns.COOKIE);
        assertThat(cleared.getMaxAge()).isZero();
        assertThat(cleared.isSecure()).isTrue();
        assertThat(cleared.getPath()).isEqualTo("/");
    }
}
