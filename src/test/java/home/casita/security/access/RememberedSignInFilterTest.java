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
        request.setCookies(new Cookie(RememberedSignIns.COOKIE, signIns.issue("shir", "c2hpcnM", NODE, "i-1a2b3c4d").orElseThrow().getValue()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getSession(false)).isNull();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    /** A store where shir, her passkey and her browser are all active, the browser authorized by {@code invite}. */
    private static SecurityStore storeWith(String invite, java.time.Instant authorizedAt) {
        SecurityStore.Member shir = new SecurityStore.Member("shir", SecurityStore.Status.ACTIVE,
                new org.springframework.security.web.webauthn.api.Bytes(new byte[] {1, 2, 3}));
        SecurityStore.Device phone = new SecurityStore.Device(NODE, "shir", "Shir's iPhone", "browser b-5a170000", "c2hpcnM", invite,
                SecurityStore.Status.ACTIVE, authorizedAt, null);
        org.springframework.security.web.webauthn.api.CredentialRecord record =
                TestBrowsers.credential(org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity.builder()
                        .name("shir").id(shir.userHandle()).displayName("shir").build(), "c2hpcnM");
        SecurityStore.Passkey passkey = new SecurityStore.Passkey(record, "shir", SecurityStore.Status.ACTIVE, NODE, invite);
        return (SecurityStore) Proxy.newProxyInstance(RememberedSignInFilterTest.class.getClassLoader(), new Class<?>[] {SecurityStore.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "member" -> Optional.of(shir);
                    case "device" -> Optional.of(phone);
                    case "accessView" -> new SecurityStore.AccessView(Optional.of(shir), Optional.of(passkey), Optional.of(phone));
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void aCookieFromTheSameInstantAsTheBrowsersNewAuthorizationIsRefusedIfIssuedUnderTheOldOne() throws Exception {
        // Deterministic version of the same-second case: one fixed clock for the cookie, the revocation and the new
        // authorization. Only the authorization id tells the copy from the new cookie, and that is what is compared.
        TestClock clock = new TestClock();
        RememberedSignIns signIns = new RememberedSignIns(dir.resolve("remember.key"),
                new RememberProperties(Duration.ofDays(30), Duration.ofDays(14)), clock);
        SecurityStore reauthorized = storeWith("i-00000002", clock.instant()); // re-authorized at the very instant the copy was made
        DeviceIdentityResolver phone = request -> Optional.of(new DeviceIdentity(NODE, "browser b-5a170000"));
        RememberedSignInFilter filter = new RememberedSignInFilter(signIns, phone,
                new AccessDecisions(reauthorized, List.of("shir"), clock), reauthorized, "ROLE_FAMILY");

        for (String[] c : new String[][] {{"i-00000001", "refused"}, {"i-00000002", "restored"}}) {
            SecurityContextHolder.clearContext();
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/doors");
            request.setSecure(true);
            request.setCookies(new Cookie(RememberedSignIns.COOKIE, signIns.issue("shir", "c2hpcnM", NODE, c[0]).orElseThrow().getValue()));
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());

            if (c[1].equals("refused")) {
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).anySatisfy(h -> assertThat(h).contains("Max-Age=0"));
            } else {
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
                assertThat(request.getSession(false).getAttribute(AccessSession.FACE_ID_UNTIL)).isEqualTo(clock.instant().plus(Duration.ofDays(30)));
            }
        }
    }
}
