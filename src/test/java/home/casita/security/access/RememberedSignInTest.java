package home.casita.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import home.casita.garage.ActivityLog;
import home.casita.security.store.SecurityStore;
import jakarta.servlet.http.Cookie;

/**
 * Staying signed in on an enrolled browser (docs/ACCESS.md): with no session (a restart, a session that ended), the
 * browser's remembered sign-in brings it back without Face ID, and only while member, passkey and browser all check
 * out in the store. Requests come from the phone through the reverse proxy, as in GarageControllerTest.
 */
@SpringBootTest(properties = "casita.security.family=shir,hubby")
@Import(TestBrowsers.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RememberedSignInTest {

    private static final Path DATA;
    private static final String PASSKEY = "c2hpcnMtcGFzc2tleQ";

    static {
        try {
            DATA = Files.createTempDirectory("casita-remember");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("casita.security.data-dir", DATA::toString);
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private RememberedSignIns signIns;
    @Autowired
    private SecurityStore store;
    @Autowired
    private ActivityLog activity;
    @Autowired
    private TestBrowsers.Browsers browsers;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestBrowsers.enrollNewPasskey(context, "shir", PASSKEY, TestBrowsers.SHIR_PHONE);
    }

    @AfterEach
    void forget() throws IOException {
        Files.deleteIfExists(DATA.resolve("security.json"));
        browsers.reset();
    }

    private String adminSecret() throws IOException {
        return Files.readString(DATA.resolve("admin.secret")).trim();
    }

    /** What the phone's browser got after its last Face ID sign-in. */
    private Cookie remembered() {
        return rememberedFor("shir", PASSKEY, TestBrowsers.SHIR_PHONE);
    }

    /** Issued as a Face ID sign-in would issue it: under the browser's current authorization. */
    private Cookie rememberedFor(String member, String passkey, TestBrowsers.Phone phone) {
        String authorization = store.device(phone.nodeId()).map(SecurityStore.Device::inviteId).orElseThrow();
        return new Cookie(RememberedSignIns.COOKIE, signIns.issue(member, passkey, phone.nodeId(), authorization).orElseThrow().getValue());
    }

    /** No session (Casita restarted, or the session ended): the browser key and the remembered sign-in only. */
    private MockHttpServletRequestBuilder fromPhone(MockHttpServletRequestBuilder request, Cookie remembered) {
        return request.cookie(remembered).with(TestBrowsers.from(TestBrowsers.SHIR_PHONE));
    }

    private static List<String> setCookies(MvcResult result) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
    }

    private static void removesTheRememberedSignIn(ResultActions actions) {
        assertThat(setCookies(actions.andReturn())).anySatisfy(c -> assertThat(c).startsWith(RememberedSignIns.COOKIE + "=;").contains("Max-Age=0"));
    }

    @Test
    void afterARestartTheEnrolledBrowserIsSignedBackInWithoutFaceIdAndCanMoveADoor() throws Exception {
        MvcResult first = mvc.perform(fromPhone(get("/api/doors"), remembered())).andExpect(status().isOk()).andReturn();

        // A new session, and the remembered sign-in renewed (never removed).
        assertThat(setCookies(first)).anySatisfy(c -> assertThat(c).startsWith(RememberedSignIns.COOKIE + "=v2.").contains("Secure")
                .contains("HttpOnly"));
        MockHttpSession session = (MockHttpSession) first.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(session.getAttribute(AccessSession.BINDING))
                .isEqualTo(new AccessSession.Binding("shir", PASSKEY, TestBrowsers.SHIR_PHONE.nodeId()));

        // Then an ordinary session: the door command goes through the same rules and is recorded as Shir's.
        mvc.perform(post("/api/doors/ford/open").session(session).with(TestBrowsers.from(TestBrowsers.SHIR_PHONE)).with(csrf()))
                .andExpect(jsonPath("$.outcome").value("MOVING"));
        assertThat(activity.recent(1)).singleElement().satisfies(e -> assertThat(e.who()).isEqualTo("shir"));
        mvc.perform(get("/api/me").session(session).with(TestBrowsers.from(TestBrowsers.SHIR_PHONE)))
                .andExpect(jsonPath("$.name").value("shir"));
    }

    @Test
    void aCommandThatArrivesFirstAfterARestartIsSignedInToo() throws Exception {
        mvc.perform(fromPhone(post("/api/doors/toyota/open"), remembered()).with(csrf())).andExpect(jsonPath("$.outcome").value("MOVING"));
        // CSRF still applies to it.
        mvc.perform(fromPhone(post("/api/doors/ford/open"), remembered())).andExpect(status().isForbidden());
        assertThat(activity.recent(10)).hasSize(1);
    }

    @Test
    void aBrowserWithoutItsBrowserKeyIsRefused() throws Exception {
        browsers.forget(TestBrowsers.SHIR_PHONE);
        removesTheRememberedSignIn(mvc.perform(fromPhone(get("/api/doors"), remembered())).andExpect(status().isUnauthorized()));
    }

    @Test
    void theRememberedSignInIsUselessInAnotherBrowserEvenOneOfTheSameMembers() throws Exception {
        TestBrowsers.enrollExistingPasskey(context, "shir", PASSKEY, TestBrowsers.SHIR_IPAD);
        removesTheRememberedSignIn(mvc.perform(get("/api/doors").cookie(remembered()).with(TestBrowsers.from(TestBrowsers.SHIR_IPAD)))
                .andExpect(status().isUnauthorized()));
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void revokingTheBrowserStopsItOnTheNextRequest() throws Exception {
        Cookie cookie = remembered();
        mvc.perform(fromPhone(get("/api/doors"), cookie)).andExpect(status().isOk());
        mvc.perform(delete("/admin/devices/" + TestBrowsers.SHIR_PHONE.nodeId()).header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(jsonPath("$.revoked").value(true));

        removesTheRememberedSignIn(mvc.perform(fromPhone(get("/api/doors"), cookie)).andExpect(status().isUnauthorized()));
        mvc.perform(fromPhone(post("/api/doors/ford/open"), cookie).with(csrf())).andExpect(status().isUnauthorized());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void revokingThePasskeyOrTheMemberStopsItToo() throws Exception {
        Cookie cookie = remembered();
        mvc.perform(delete("/admin/credentials/" + PASSKEY).header(LocalAdmin.HEADER, adminSecret())).andExpect(jsonPath("$.revoked").value(true));
        removesTheRememberedSignIn(mvc.perform(fromPhone(get("/api/doors"), cookie)).andExpect(status().isUnauthorized()));

        TestBrowsers.enrollNewPasskey(context, "hubby", "aHViYnlzLWtleQ", TestBrowsers.HUBBY_PHONE);
        Cookie hubbys = rememberedFor("hubby", "aHViYnlzLWtleQ", TestBrowsers.HUBBY_PHONE);
        mvc.perform(get("/api/doors").cookie(hubbys).with(TestBrowsers.from(TestBrowsers.HUBBY_PHONE))).andExpect(status().isOk());
        mvc.perform(delete("/admin/members/hubby").header(LocalAdmin.HEADER, adminSecret())).andExpect(status().isOk());
        mvc.perform(get("/api/doors").cookie(hubbys).with(TestBrowsers.from(TestBrowsers.HUBBY_PHONE))).andExpect(status().isUnauthorized());
    }

    @Test
    void aSessionEndedByARevocationAlsoRemovesTheRememberedSignIn() throws Exception {
        MvcResult first = mvc.perform(fromPhone(get("/api/doors"), remembered())).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) first.getRequest().getSession(false);
        mvc.perform(delete("/admin/devices/" + TestBrowsers.SHIR_PHONE.nodeId()).header(LocalAdmin.HEADER, adminSecret()));

        removesTheRememberedSignIn(mvc.perform(get("/api/doors").session(session).with(TestBrowsers.from(TestBrowsers.SHIR_PHONE)))
                .andExpect(status().isUnauthorized()));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aBrowserRevokedAndAuthorizedAgainInTheSameSecondNeverAcceptsACookieFromBefore() throws Exception {
        // Regression: revocation, a new invite and a new authorization all within one second of the cookie. The cookie is
        // bound to the authorization (its invite id), not compared by time, so it doesn't matter how close together they are.
        Cookie old = remembered(); // e.g. the copy that made someone revoke the phone
        mvc.perform(delete("/admin/devices/" + TestBrowsers.SHIR_PHONE.nodeId()).header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(jsonPath("$.revoked").value(true));
        TestBrowsers.enrollExistingPasskey(context, "shir", PASSKEY, TestBrowsers.SHIR_PHONE); // a new invite, same browser key
        Cookie fresh = remembered(); // what the next Face ID sign-in on that browser issues

        removesTheRememberedSignIn(mvc.perform(fromPhone(get("/api/doors"), old)).andExpect(status().isUnauthorized()));
        mvc.perform(fromPhone(post("/api/doors/ford/open"), old).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(fromPhone(get("/api/doors"), fresh)).andExpect(status().isOk());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void aFaceIdSessionKeptActiveStillEndsThirtyDaysAfterFaceId() throws Exception {
        MockHttpSession session = new MockHttpSession();
        assertThat(TestBrowsers.signIn(context, session, "shir", PASSKEY, TestBrowsers.SHIR_PHONE)).isNull();
        assertThat(session.getAttribute(AccessSession.FACE_ID_UNTIL)).isInstanceOfSatisfying(java.time.Instant.class, until ->
                assertThat(until).isBetween(java.time.Instant.now().plus(java.time.Duration.ofDays(30)).minusSeconds(60),
                        java.time.Instant.now().plus(java.time.Duration.ofDays(30)).plusSeconds(60)));
        mvc.perform(get("/api/doors").session(session).with(TestBrowsers.from(TestBrowsers.SHIR_PHONE))).andExpect(status().isOk());

        session.setAttribute(AccessSession.FACE_ID_UNTIL, java.time.Instant.now().minusSeconds(1)); // day 30, used every day
        mvc.perform(get("/api/doors").session(session).with(TestBrowsers.from(TestBrowsers.SHIR_PHONE))).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aRememberedSignInMeansNothingToTheShortcutEndpoints() throws Exception {
        // /shortcut/** has its own chain that reads no cookie: neither the browser key, the session nor this one.
        mvc.perform(fromPhone(post("/shortcut/v1/doors/ford/open"), remembered())).andExpect(status().isUnauthorized());
        mvc.perform(fromPhone(get("/shortcut/v1/doors/ford"), remembered())).andExpect(status().isUnauthorized());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void aRestoredSessionEndsWhenItsRememberedSignInDoesHoweverOftenItIsUsed() throws Exception {
        MvcResult first = mvc.perform(fromPhone(get("/api/doors"), remembered())).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) first.getRequest().getSession(false);
        session.setAttribute(AccessSession.FACE_ID_UNTIL, java.time.Instant.now().minusSeconds(1)); // 30 days after Face ID

        mvc.perform(get("/api/doors").session(session).with(TestBrowsers.from(TestBrowsers.SHIR_PHONE))).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aBrowserInDailyUseRenewsItsRememberedSignInAtMostOnceADay() throws Exception {
        Cookie cookie = remembered();
        MvcResult first = mvc.perform(fromPhone(get("/api/doors"), cookie)).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) first.getRequest().getSession(false);

        MvcResult sameDay = mvc.perform(get("/api/doors").session(session).cookie(cookie).with(TestBrowsers.from(TestBrowsers.SHIR_PHONE)))
                .andExpect(status().isOk()).andReturn();
        assertThat(setCookies(sameDay)).noneMatch(c -> c.startsWith(RememberedSignIns.COOKIE));

        session.setAttribute(AccessSession.REMEMBER_RENEWED, java.time.Instant.now().minus(java.time.Duration.ofDays(2)));
        MvcResult later = mvc.perform(get("/api/doors").session(session).cookie(cookie).with(TestBrowsers.from(TestBrowsers.SHIR_PHONE)))
                .andExpect(status().isOk()).andReturn();
        String renewed = setCookies(later).stream().filter(c -> c.startsWith(RememberedSignIns.COOKIE + "=v2.")).findFirst().orElseThrow();
        RememberedSignIns.Claim after = signIns.verify(renewed.substring(renewed.indexOf('=') + 1, renewed.indexOf(';'))).orElseThrow();
        RememberedSignIns.Claim before = signIns.verify(cookie.getValue()).orElseThrow();
        assertThat(after.verifiedAt()).isEqualTo(before.verifiedAt());
        assertThat(after.expiresAt()).isEqualTo(before.expiresAt()); // renewing never moves the 30-day limit
        assertThat(session.getAttribute(AccessSession.FACE_ID_UNTIL)).isEqualTo(before.expiresAt());
    }

    @Test
    void anAlteredOrForeignCookieIsRefusedAndRemoved() throws Exception {
        Cookie cookie = remembered();
        Cookie altered = new Cookie(RememberedSignIns.COOKIE, cookie.getValue().substring(0, cookie.getValue().length() - 3) + "AAA");
        removesTheRememberedSignIn(mvc.perform(fromPhone(get("/api/doors"), altered)).andExpect(status().isUnauthorized()));
        removesTheRememberedSignIn(mvc.perform(fromPhone(get("/api/doors"), new Cookie(RememberedSignIns.COOKIE, "v1.x.y")))
                .andExpect(status().isUnauthorized()));
    }

    @Test
    void plainHttpIgnoresIt() throws Exception {
        mvc.perform(get("/api/doors").cookie(remembered()).with(request -> {
            TestBrowsers.from(TestBrowsers.SHIR_PHONE).postProcessRequest(request);
            request.setSecure(false);
            request.setScheme("http");
            return request;
        })).andExpect(status().isUnauthorized());
    }

    @Test
    void signingOutForgetsIt() throws Exception {
        MvcResult first = mvc.perform(fromPhone(get("/api/doors"), remembered())).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) first.getRequest().getSession(false);

        removesTheRememberedSignIn(mvc.perform(post("/logout").session(session).with(TestBrowsers.from(TestBrowsers.SHIR_PHONE)).with(csrf())));
    }

    @Test
    void aFaceIdSignInGivesTheBrowserItsRememberedSignIn() throws Exception {
        WebAuthnAuthentication auth = new WebAuthnAuthentication(TestBrowsers.user(store, "shir"), List.of(new SimpleGrantedAuthority("ROLE_FAMILY")));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(auth));
        MockHttpServletRequest request = TestBrowsers.request(context, "POST", "/login/webauthn");
        request.setSession(session);
        request.setAttribute(AccessSession.SIGNED_IN_CREDENTIAL, PASSKEY);
        TestBrowsers.from(TestBrowsers.SHIR_PHONE).postProcessRequest(request);
        MockHttpServletResponse response = new MockHttpServletResponse();

        context.getBean(PasskeySignInSuccessHandler.class).onAuthenticationSuccess(request, response, auth);

        String issued = response.getHeaders(HttpHeaders.SET_COOKIE).stream().filter(c -> c.startsWith(RememberedSignIns.COOKIE + "=v2.")).findFirst()
                .orElseThrow();
        String value = issued.substring(issued.indexOf('=') + 1, issued.indexOf(';'));
        assertThat(signIns.verify(value)).get().satisfies(c -> {
            assertThat(c.member()).isEqualTo("shir");
            assertThat(c.credentialId()).isEqualTo(PASSKEY);
            assertThat(c.nodeId()).isEqualTo(TestBrowsers.SHIR_PHONE.nodeId());
        });
    }
}
