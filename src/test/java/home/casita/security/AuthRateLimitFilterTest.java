package home.casita.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The public sign-in and invite endpoints, per client and in total, window by window. */
class AuthRateLimitFilterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-24T12:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final AuthRateLimitFilter filter = new AuthRateLimitFilter(new AuthRateLimitProperties(3, 5, Duration.ofMinutes(10)), clock);

    private int post(String path, String client) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(client);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void eachClientGetsItsShareThenWaitsForTheNextWindow() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(post("/login/ott", "203.0.113.1")).isEqualTo(200);
        assertThat(post("/login/webauthn", "203.0.113.1")).isEqualTo(429);
        assertThat(post("/login/ott", "203.0.113.2")).isEqualTo(200); // someone else isn't held up

        now.set(now.get().plus(Duration.ofMinutes(10)));
        assertThat(post("/login/ott", "203.0.113.1")).isEqualTo(200);
    }

    @Test
    void everyoneTogetherHasALimitToo() throws Exception {
        for (int i = 1; i <= 5; i++) assertThat(post("/webauthn/authenticate/options", "203.0.113." + i)).isEqualTo(200);
        assertThat(post("/webauthn/authenticate/options", "203.0.113.99")).isEqualTo(429);
    }

    @Test
    void onlyThePublicAuthenticationEndpointsAreLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/doors");
            request.setRemoteAddr("203.0.113.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
        assertThat(AuthRateLimitFilter.LIMITED).contains("/login/ott", "/login/webauthn", "/webauthn/authenticate/options",
                "/webauthn/register/options", "/webauthn/register");
    }

    @Test
    void aRefusalSaysWhenToTryAgain() throws Exception {
        for (int i = 0; i < 3; i++) post("/login/ott", "203.0.113.1");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login/ott");
        request.setRemoteAddr("203.0.113.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("600");
    }
}
