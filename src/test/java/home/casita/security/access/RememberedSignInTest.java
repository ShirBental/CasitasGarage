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
        return new Cookie(RememberedSignIns.COOKIE, signIns.issue("shir", PASSKEY, TestBrowsers.SHIR_PHONE.nodeId()).orElseThrow().getValue());
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
        assertThat(setCookies(first)).anySatisfy(c -> assertThat(c).startsWith(RememberedSignIns.COOKIE + "=v1.").contains("Secure")
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
        Cookie hubbys = new Cookie(RememberedSignIns.COOKIE,
                signIns.issue("hubby", "aHViYnlzLWtleQ", TestBrowsers.HUBBY_PHONE.nodeId()).orElseThrow().getValue());
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

        String issued = response.getHeaders(HttpHeaders.SET_COOKIE).stream().filter(c -> c.startsWith(RememberedSignIns.COOKIE + "=v1.")).findFirst()
                .orElseThrow();
        String value = issued.substring(issued.indexOf('=') + 1, issued.indexOf(';'));
        assertThat(signIns.verify(value)).get().satisfies(c -> {
            assertThat(c.member()).isEqualTo("shir");
            assertThat(c.credentialId()).isEqualTo(PASSKEY);
            assertThat(c.nodeId()).isEqualTo(TestBrowsers.SHIR_PHONE.nodeId());
        });
    }
}
