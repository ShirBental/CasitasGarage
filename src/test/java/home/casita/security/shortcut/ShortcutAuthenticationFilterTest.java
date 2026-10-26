package home.casita.security.shortcut;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import home.casita.garage.Action;
import home.casita.security.store.FileSecurityStore;
import home.casita.security.store.SecurityStore;
import home.casita.security.store.StoreUnavailableException;
import home.casita.security.store.TestClock;

/** The filter alone: what it refuses, and that it never lets anything through on a refusal. */
class ShortcutAuthenticationFilterTest {

    @TempDir
    Path data;

    private final TestClock clock = new TestClock();
    private FileSecurityStore store;
    private ShortcutTokens tokens;
    private String secret;

    @BeforeEach
    void setUp() {
        store = new FileSecurityStore(data, clock);
        store.seedMembers(List.of("shir", "shahar"));
        tokens = new ShortcutTokens(store, clock);
        secret = tokens.create("shir", "iPhone", "ford", Action.OPEN, Duration.ofDays(30)).secret();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private ShortcutAuthenticationFilter filter() {
        return filter(tokens);
    }

    private ShortcutAuthenticationFilter filter(ShortcutTokens tokens) {
        return new ShortcutAuthenticationFilter(tokens, List.of("shir", "shahar"), new ShortcutRateLimiter(clock), clock);
    }

    private static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/shortcut/v1/doors/ford/open");
        request.setSecure(true);
        request.setRemoteAddr("203.0.113.20");
        if (authorization != null) request.addHeader("Authorization", authorization);
        return request;
    }

    /** Runs the filter; the chain records whether it was reached. */
    private MockHttpServletResponse run(ShortcutAuthenticationFilter filter, MockHttpServletRequest request, boolean[] reached) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                reached[0] = true;
            }
        });
        return response;
    }

    @Test
    void aValidTokenIsAuthenticatedAsItself() throws Exception {
        boolean[] reached = {false};
        MockHttpServletResponse response = run(filter(), request("Bearer " + secret), reached);
        assertThat(reached[0]).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isInstanceOfSatisfying(SecurityStore.ShortcutToken.class, t -> assertThat(t.memberId()).isEqualTo("shir"));
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting(Object::toString).containsExactly(ShortcutAuthenticationFilter.AUTHORITY);
    }

    @Test
    void theSchemeIsCaseInsensitive() throws Exception {
        boolean[] reached = {false};
        run(filter(), request("bearer " + secret), reached);
        assertThat(reached[0]).isTrue();
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        clock.advance(Duration.ofDays(30));
        boolean[] reached = {false};
        MockHttpServletResponse response = run(filter(), request("Bearer " + secret), reached);
        assertThat(reached[0]).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getContentAsString()).doesNotContain(secret);
    }

    @Test
    void aTokenWhoseMemberLeftTheFamilyIsRefused() throws Exception {
        ShortcutAuthenticationFilter filter = new ShortcutAuthenticationFilter(tokens, List.of("shahar"), new ShortcutRateLimiter(clock), clock);
        boolean[] reached = {false};
        assertThat(run(filter, request("Bearer " + secret), reached).getStatus()).isEqualTo(401);
        assertThat(reached[0]).isFalse();
    }

    @Test
    void aRevokedMemberIsRefused() throws Exception {
        store.revokeMember("shir");
        boolean[] reached = {false};
        assertThat(run(filter(), request("Bearer " + secret), reached).getStatus()).isEqualTo(401);
        assertThat(reached[0]).isFalse();
    }

    @Test
    void aRevokedTokenIsRefusedOnTheNextRequest() throws Exception {
        boolean[] reached = {false};
        run(filter(), request("Bearer " + secret), reached);
        assertThat(reached[0]).isTrue();

        tokens.revoke(tokens.list(List.of("shir")).get(0).tokenId());
        boolean[] again = {false};
        assertThat(run(filter(), request("Bearer " + secret), again).getStatus()).isEqualTo(401);
        assertThat(again[0]).isFalse();
    }

    /** The store is the only authority: if it can't be read, nothing is allowed, and nothing older is trusted instead. */
    @Test
    void anUnreachableSecurityStoreRefuses() throws Exception {
        FileSecurityStore down = new FileSecurityStore(data, clock) {
            @Override
            public synchronized SecurityStore.ShortcutView shortcutView(String tokenHash) {
                throw new StoreUnavailableException("down", null);
            }
        };
        boolean[] reached = {false};
        MockHttpServletResponse response = run(filter(new ShortcutTokens(down, clock)), request("Bearer " + secret), reached);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("unavailable").doesNotContain(secret);
        assertThat(reached[0]).isFalse();
    }

    @Test
    void plainHttpIsRefusedBeforeTheTokenIsLookedAt() throws Exception {
        MockHttpServletRequest request = request("Bearer " + secret);
        request.setSecure(false);
        boolean[] reached = {false};
        MockHttpServletResponse response = run(filter(), request, reached);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("https-required");
        assertThat(reached[0]).isFalse();
    }

    @Test
    void credentialsThatArentABearerTokenAreRefused() throws Exception {
        for (String header : new String[] {null, "", "Bearer", "Bearer ", "Basic c2hpcjp4", secret,
                "Bearer " + secret.toUpperCase(), "Bearer " + ShortcutTokens.hash(secret)}) {
            boolean[] reached = {false};
            assertThat(run(filter(), request(header), reached).getStatus()).as(String.valueOf(header)).isEqualTo(401);
            assertThat(reached[0]).isFalse();
        }
    }
}
