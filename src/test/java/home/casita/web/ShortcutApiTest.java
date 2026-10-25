package home.casita.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import home.casita.garage.ActivityLog;
import home.casita.security.access.LocalAdmin;
import home.casita.security.access.TestBrowsers;
import home.casita.security.shortcut.ShortcutRateLimiter;

/**
 * Shortcut tokens end to end, through both security chains, over the practice garage (two simulated doors: nothing here
 * can reach Meross). Requests come as they would through Caddy: HTTPS, from the phone's address.
 */
@SpringBootTest(properties = "casita.security.family=shir,shahar")
@Import(TestBrowsers.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ExtendWith(OutputCaptureExtension.class)
class ShortcutApiTest {

    private static final Path DATA;

    static {
        try {
            DATA = Files.createTempDirectory("casita-shortcut");
        } catch (java.io.IOException e) {
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
    private ActivityLog activity;
    @Autowired
    private TestBrowsers.Browsers browsers;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void forget() throws java.io.IOException {
        Files.deleteIfExists(DATA.resolve("security.json"));
        browsers.reset();
    }

    private String adminSecret() throws java.io.IOException {
        return Files.readString(DATA.resolve("admin.secret")).trim();
    }

    /** Through casita-access's endpoint, on the server. @return the token, shown this once */
    private String newToken(String member) throws Exception {
        String body = mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, adminSecret())
                        .param("member", member).param("label", member + "'s iPhone"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private String idOf(String body) {
        return JsonPath.read(body, "$.id");
    }

    /** From the phone, through Caddy. */
    private static RequestPostProcessor phone() {
        return request -> {
            request.setSecure(true);
            request.setScheme("https");
            request.setRemoteAddr("203.0.113.20");
            return request;
        };
    }

    private MockHttpServletRequestBuilder open(String token) {
        MockHttpServletRequestBuilder request = post("/shortcut/v1/doors/ford/open").with(phone());
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    /* ---------- what a token does ---------- */

    @Test
    void aTokenOpensFordThroughTheSameRulesAndSaysWhatItKnows() throws Exception {
        String token = newToken("shir");

        mvc.perform(open(token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("accepted"))
                .andExpect(jsonPath("$.outcome").value("MOVING"))
                .andExpect(jsonPath("$.door").value("Ford"))
                .andExpect(jsonPath("$.control").value("simulated"))
                .andExpect(jsonPath("$.live").value(false))
                .andExpect(jsonPath("$.confirmed").value(false))
                .andExpect(jsonPath("$.checkAfterSeconds").isNumber())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().string("Cache-Control", "no-store"));

        assertThat(activity.recent(1)).singleElement().satisfies(e -> {
            assertThat(e.door()).isEqualTo("Ford");
            assertThat(e.who()).isEqualTo("shir via Shortcut");
        });

        mvc.perform(get("/shortcut/v1/doors/ford").with(phone()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.door").value("Ford"))
                .andExpect(jsonPath("$.doorState").value("OPENING"))
                .andExpect(jsonPath("$.confirmed").value(false));
    }

    @Test
    void onlyFordAndOnlyOpen() throws Exception {
        String token = newToken("shir");
        String bearer = "Bearer " + token;

        mvc.perform(post("/shortcut/v1/doors/toyota/open").with(phone()).header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post("/shortcut/v1/doors/ford/close").with(phone()).header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post("/shortcut/v1/doors/ford/resume").with(phone()).header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post("/shortcut/v1/doors/garage/open").with(phone()).header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(get("/shortcut/v1/doors/toyota").with(phone()).header("Authorization", bearer)).andExpect(status().isForbidden());
        assertThat(activity.recent(10)).isEmpty();
    }

    /* ---------- who may ---------- */

    @Test
    void missingOrWrongCredentialsAreRefusedAndNothingIsRecorded() throws Exception {
        String token = newToken("shir");
        mvc.perform(open(null)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        for (String header : new String[] {"Bearer", "Bearer nope", "Basic c2hpcjpzZWNyZXQ=", token, "Bearer " + token + "x",
                "Bearer casita_st_" + "A".repeat(43)}) {
            mvc.perform(post("/shortcut/v1/doors/ford/open").with(phone()).header("Authorization", header))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.result").value("refused"));
        }
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void aRevokedTokenStopsAtOnce() throws Exception {
        String created = mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, adminSecret())
                .param("member", "shir").param("label", "Shir's iPhone")).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(created, "$.token");
        mvc.perform(open(token)).andExpect(status().isAccepted());

        mvc.perform(delete("/admin/shortcut-tokens/" + idOf(created)).header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(jsonPath("$.revoked").value(true));
        mvc.perform(open(token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/shortcut-tokens").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(jsonPath("$[0].state").value("revoked"));
    }

    @Test
    void revokingTheMemberRevokesTheirTokensOnly() throws Exception {
        String shirs = newToken("shir");
        String shahars = newToken("shahar");

        mvc.perform(delete("/admin/members/shir").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(jsonPath("$.revoked").value(true)).andExpect(jsonPath("$.shortcutTokens").value(1));
        mvc.perform(open(shirs)).andExpect(status().isUnauthorized());
        mvc.perform(open(shahars)).andExpect(status().isAccepted());
    }

    @Test
    void plainHttpIsRefused() throws Exception {
        String token = newToken("shir");
        mvc.perform(post("/shortcut/v1/doors/ford/open").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("https-required"));
        assertThat(activity.recent(10)).isEmpty();
    }

    /* ---------- how often ---------- */

    @Test
    void aTokenGetsAFewCommandsPerWindow() throws Exception {
        String token = newToken("shir");
        for (int i = 0; i < ShortcutRateLimiter.COMMANDS_PER_TOKEN; i++) {
            int code = mvc.perform(open(token)).andReturn().getResponse().getStatus();
            assertThat(code).isIn(202, 409); // the first opens; the rest find the door busy
        }
        mvc.perform(open(token)).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }

    @Test
    void aClientGetsAFewRequestsPerWindowWhateverTheyCarry() throws Exception {
        for (int i = 0; i < ShortcutRateLimiter.PER_CLIENT; i++) {
            mvc.perform(open(null)).andExpect(status().isUnauthorized());
        }
        mvc.perform(open(null)).andExpect(status().isTooManyRequests());
    }

    /* ---------- the browser's way in is unchanged, and separate ---------- */

    @Test
    void aShortcutTokenMeansNothingToTheAppsApi() throws Exception {
        String token = newToken("shir");
        mvc.perform(post("/api/doors/ford/open").with(phone()).with(csrf()).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/doors").with(phone()).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void aSignedInBrowserMeansNothingToTheShortcutEndpointsAndStillWorksInTheApp() throws Exception {
        MockHttpSession session = TestBrowsers.signedInOn(context, "shir", "c2hpcnMtcGFzc2tleQ", TestBrowsers.SHIR_PHONE);
        RequestPostProcessor browser = request -> {
            request.setSession(session);
            return TestBrowsers.from(TestBrowsers.SHIR_PHONE).postProcessRequest(request);
        };

        mvc.perform(post("/shortcut/v1/doors/ford/open").with(browser).with(csrf())).andExpect(status().isUnauthorized());
        assertThat(activity.recent(10)).isEmpty();

        // The app, exactly as before: the browser key, the session and the CSRF token.
        mvc.perform(post("/api/doors/ford/open").with(browser)).andExpect(status().isForbidden());
        mvc.perform(post("/api/doors/ford/open").with(browser).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("MOVING"));
    }

    /* ---------- administration ---------- */

    @Test
    void theTokenIsShownOnceAndNeverListed() throws Exception {
        String created = mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, adminSecret())
                        .param("member", "shir").param("label", "Shir's iPhone").param("days", "30"))
                .andExpect(jsonPath("$.door").value("ford")).andExpect(jsonPath("$.action").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(created, "$.token");

        String listed = mvc.perform(get("/admin/shortcut-tokens").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(jsonPath("$[0].id").value(idOf(created))).andExpect(jsonPath("$[0].state").value("active"))
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain(token).doesNotContain("hash");
        assertThat(Files.readString(DATA.resolve("security.json"))).doesNotContain(token);
        assertThat(DATA.resolve("shortcut-tokens.json")).doesNotExist(); // tokens live in the security store, nowhere else
    }

    @Test
    void onlyForAnActiveFamilyMemberFordAndAtMostAYear() throws Exception {
        String admin = adminSecret();
        mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, admin).param("member", "mallory").param("label", "x"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, admin).param("member", "shir").param("label", "x")
                .param("door", "toyota")).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, admin).param("member", "shir").param("label", "x")
                .param("days", "366")).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, admin).param("member", "shir").param("label", "x")
                .param("days", "0")).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, admin).param("member", "shir").param("label", " "))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/admin/shortcut-tokens/st-000000000000").header(LocalAdmin.HEADER, admin)).andExpect(status().isNotFound());
        mvc.perform(delete("/admin/shortcut-tokens/not-an-id").header(LocalAdmin.HEADER, admin)).andExpect(status().isBadRequest());
    }

    @Test
    void tokenAdministrationIsForTheServerOnly() throws Exception {
        mvc.perform(post("/admin/shortcut-tokens").param("member", "shir").param("label", "x")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/shortcut-tokens").with(phone()).header(LocalAdmin.HEADER, adminSecret())
                .param("member", "shir").param("label", "x")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/shortcut-tokens").with(phone()).header(LocalAdmin.HEADER, adminSecret())).andExpect(status().isForbidden());
    }

    /* ---------- secrets stay secret ---------- */

    @Test
    void noTokenEverReachesTheLog(CapturedOutput output) throws Exception {
        String created = mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, adminSecret())
                .param("member", "shir").param("label", "Shir's iPhone")).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(created, "$.token");
        mvc.perform(open(token));
        mvc.perform(post("/shortcut/v1/doors/ford/close").with(phone()).header("Authorization", "Bearer " + token));
        mvc.perform(open(token + "x"));
        mvc.perform(delete("/admin/shortcut-tokens/" + idOf(created)).header(LocalAdmin.HEADER, adminSecret()));
        mvc.perform(open(token));

        String random = token.substring("casita_st_".length());
        assertThat(output.getAll()).doesNotContain(random).contains(idOf(created));
    }
}
