package home.casita.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import home.casita.security.RememberProperties;
import home.casita.security.store.SecurityStore;
import home.casita.security.store.StoreUnavailableException;
import home.casita.security.store.TestClock;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/** The filter alone, with a security store that can't be reached. */
class RememberedSignInFilterTest {

    private static final String NODE = "b-5a17000000000000000000000000000a";

    @TempDir
    Path dir;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anUnreachableStoreSignsNobodyInButKeepsTheCookieForWhenItIsBack() throws Exception {
        SecurityStore down = (SecurityStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {SecurityStore.class},
                (proxy, method, args) -> {
                    throw new StoreUnavailableException("Firestore unreachable", null);
                });
        RememberedSignIns signIns = new RememberedSignIns(dir.resolve("remember.key"),
                new RememberProperties(Duration.ofDays(30), Duration.ofDays(14)), new TestClock());
        DeviceIdentityResolver phone = new DeviceIdentityResolver() {
            @Override
            public Optional<DeviceIdentity> resolve(HttpServletRequest request) {
                return Optional.of(new DeviceIdentity(NODE, "browser b-5a170000"));
            }
        };
        RememberedSignInFilter filter = new RememberedSignInFilter(signIns, phone, new AccessDecisions(down, List.of("shir"), new TestClock()),
                down, "ROLE_FAMILY");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/doors");
        request.setSecure(true);
        request.setCookies(new Cookie(RememberedSignIns.COOKIE, signIns.issue("shir", "c2hpcnM", NODE).orElseThrow().getValue()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getSession(false)).isNull();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }
}
