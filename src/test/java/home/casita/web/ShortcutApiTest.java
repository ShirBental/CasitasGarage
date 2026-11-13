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
        return newToken(member, "ford");
    }

    private String newToken(String member, String door) throws Exception {
        String body = mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, adminSecret())
                        .param("member", member).param("label", member + "'s iPhone").param("door", door))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private String newToken(String member, String door, String action) throws Exception {
        String body = mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, adminSecret())
                        .param("member", member).param("label", member + "'s iPhone").param("door", door).param("action", action))
                .andExpect(status().isOk()).andExpect(jsonPath("$.action").value(action.toUpperCase(java.util.Locale.ROOT)))
                .andReturn().getResponse().getContentAsString();
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
    void aToyotaTokenOpensToyotaOnlyAndEachDoorsTokenIsRevokedOnItsOwn() throws Exception {
        String ford = newToken("shir");
        String toyota = newToken("shir", "toyota");

        mvc.perform(post("/shortcut/v1/doors/toyota/open").with(phone()).header("Authorization", "Bearer " + toyota))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.door").value("Toyota"))
                .andExpect(jsonPath("$.outcome").value("MOVING"));
        // The Toyota token can't touch Ford, nor close or resume Toyota.
        mvc.perform(open(toyota)).andExpect(status().isForbidden());
        mvc.perform(get("/shortcut/v1/doors/ford").with(phone()).header("Authorization", "Bearer " + toyota)).andExpect(status().isForbidden());
        mvc.perform(post("/shortcut/v1/doors/toyota/close").with(phone()).header("Authorization", "Bearer " + toyota))
                .andExpect(status().isForbidden());
        mvc.perform(post("/shortcut/v1/doors/toyota/resume").with(phone()).header("Authorization", "Bearer " + toyota))
                .andExpect(status().isForbidden());
        assertThat(activity.recent(10)).singleElement().satisfies(e -> {
            assertThat(e.door()).isEqualTo("Toyota");
            assertThat(e.who()).isEqualTo("shir via Shortcut");
        });

        // Revoking the phone's Toyota token leaves its Ford token working.
        String list = mvc.perform(get("/admin/shortcut-tokens").header(LocalAdmin.HEADER, adminSecret()))
                .andReturn().getResponse().getContentAsString();
        String toyotaId = JsonPath.<java.util.List<String>>read(list, "$[?(@.door == 'toyota')].id").get(0);
        mvc.perform(delete("/admin/shortcut-tokens/" + toyotaId).header(LocalAdmin.HEADER, adminSecret())).andExpect(status().isOk());
        mvc.perform(get("/shortcut/v1/doors/toyota").with(phone()).header("Authorization", "Bearer " + toyota))
                .andExpect(status().isUnauthorized());
        mvc.perform(open(ford)).andExpect(status().isAccepted()).andExpect(jsonPath("$.door").value("Ford"));
    }

    @Test
    void onePhonesFordAndToyotaShortcutsWorkIndependentlyAtTheSameTime() throws Exception {
        String ford = newToken("shir");
        String toyota = newToken("shir", "toyota");

        mvc.perform(open(ford)).andExpect(status().isAccepted()).andExpect(jsonPath("$.door").value("Ford"));
        // Ford is busy being checked; Toyota has its own movement and its own rules.
        mvc.perform(post("/shortcut/v1/doors/toyota/open").with(phone()).header("Authorization", "Bearer " + toyota))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.door").value("Toyota"));
        mvc.perform(open(ford)).andExpect(status().isConflict()).andExpect(jsonPath("$.outcome").value("BUSY"));
        assertThat(activity.recent(10)).extracting(e -> e.door()).containsExactly("Toyota", "Ford");
    }

    @Test
    void aFordTokenOnlyOpensFord() throws Exception {
        String token = newToken("shir");
        String bearer = "Bearer " + token;

        mvc.perform(post("/shortcut/v1/doors/toyota/open").with(phone()).header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post("/shortcut/v1/doors/ford/close").with(phone()).header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post("/shortcut/v1/doors/ford/resume").with(phone()).header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post("/shortcut/v1/doors/garage/open").with(phone()).header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(get("/shortcut/v1/doors/toyota").with(phone()).header("Authorization", bearer)).andExpect(status().isForbidden());
        assertThat(activity.recent(10)).isEmpty();
    }

    /* ---------- close ---------- */

    @Test
    void aTokenMadeWithoutAnActionOpensAndCanNeverClose() throws Exception {
        // Tokens from before close existed were all made this way: they stay open-only.
        String token = newToken("shir");
        String list = mvc.perform(get("/admin/shortcut-tokens").header(LocalAdmin.HEADER, adminSecret()))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.List<String>>read(list, "$[*].action")).containsExactly("OPEN");
        mvc.perform(post("/shortcut/v1/doors/ford/close").with(phone()).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void aCloseTokenClosesItsOwnDoorOnlyAndNeverOpens() throws Exception {
        String bearer = "Bearer " + newToken("shir", "toyota", "close");

        mvc.perform(post("/shortcut/v1/doors/toyota/open").with(phone()).header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post("/shortcut/v1/doors/ford/close").with(phone()).header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(post("/shortcut/v1/doors/toyota/resume").with(phone()).header("Authorization", bearer)).andExpect(status().isForbidden());
        mvc.perform(get("/shortcut/v1/doors/ford").with(phone()).header("Authorization", bearer)).andExpect(status().isForbidden());
        assertThat(activity.recent(10)).isEmpty();
        mvc.perform(get("/shortcut/v1/doors/toyota").with(phone()).header("Authorization", bearer)).andExpect(status().isOk());
    }

    @Test
    void aCloseOfADoorTheSensorSaysIsClosedSendsNothingAndSaysWhy() throws Exception {
        String bearer = "Bearer " + newToken("shir", "ford", "close");

        mvc.perform(post("/shortcut/v1/doors/ford/close").with(phone()).header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("already"))
                .andExpect(jsonPath("$.outcome").value("ALREADY"))
                .andExpect(jsonPath("$.speak").value(org.hamcrest.Matchers.containsString("sensor says it's closed, so I didn't send anything")))
                .andExpect(jsonPath("$.speak").value(org.hamcrest.Matchers.containsString("hasn't caught up")));
    }

    @Test
    void aCloseRightAfterAnOpenIsRefusedWithAReasonAndNothingIsSent() throws Exception {
        String open = newToken("shir", "ford", "open");
        String close = newToken("shir", "ford", "close");
        mvc.perform(open(open)).andExpect(status().isAccepted());

        mvc.perform(post("/shortcut/v1/doors/ford/close").with(phone()).header("Authorization", "Bearer " + close))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.result").value("refused"))
                .andExpect(jsonPath("$.outcome").value("BUSY"))
                .andExpect(jsonPath("$.speak").value(org.hamcrest.Matchers.startsWith("Practice door, not the real one. Ford: ")))
                .andExpect(jsonPath("$.speak").value(org.hamcrest.Matchers.endsWith("I didn't send anything.")));
        assertThat(activity.recent(10)).extracting(e -> e.action()).containsOnly(home.casita.garage.Action.OPEN);
    }

    @Test
    void aShortcutCanNeverSayItLookedAtTheDoor() throws Exception {
        String close = newToken("shir", "ford", "close");
        String open = newToken("shir", "ford", "open");
        for (String query : new String[] {"checked=true", "checked=false", "checked="}) {
            mvc.perform(post("/shortcut/v1/doors/ford/close?" + query).with(phone()).header("Authorization", "Bearer " + close))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("checked-not-allowed"));
            mvc.perform(post("/shortcut/v1/doors/ford/open?" + query).with(phone()).header("Authorization", "Bearer " + open))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/shortcut/v1/doors/ford/close").with(phone()).header("Authorization", "Bearer " + close).param("checked", "true"))
                .andExpect(status().isBadRequest());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void onePhoneHasFourTokensEachRevokedOnItsOwn() throws Exception {
        java.util.Map<String, String> tokens = new java.util.LinkedHashMap<>();
        for (String door : new String[] {"ford", "toyota"}) {
            for (String action : new String[] {"open", "close"}) tokens.put(door + " " + action, newToken("shir", door, action));
        }
        String list = mvc.perform(get("/admin/shortcut-tokens").header(LocalAdmin.HEADER, adminSecret()))
                .andReturn().getResponse().getContentAsString();
        String fordClose = JsonPath.<java.util.List<String>>read(list, "$[?(@.door == 'ford' && @.action == 'CLOSE')].id").get(0);
        mvc.perform(delete("/admin/shortcut-tokens/" + fordClose).header(LocalAdmin.HEADER, adminSecret())).andExpect(status().isOk());

        mvc.perform(post("/shortcut/v1/doors/ford/close").with(phone()).header("Authorization", "Bearer " + tokens.get("ford close")))
                .andExpect(status().isUnauthorized());
        mvc.perform(open(tokens.get("ford open"))).andExpect(status().isAccepted());
        mvc.perform(post("/shortcut/v1/doors/toyota/close").with(phone()).header("Authorization", "Bearer " + tokens.get("toyota close")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ALREADY"));
        mvc.perform(post("/shortcut/v1/doors/toyota/open").with(phone()).header("Authorization", "Bearer " + tokens.get("toyota open")))
                .andExpect(status().isAccepted());
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
    void revokingABrowserListsItsMembersActiveShortcutTokensButLeavesThemToTheAdmin() throws Exception {
        MockHttpSession session = TestBrowsers.signedInOn(context, "shir", "c2hpcnMtcGFzc2tleQ", TestBrowsers.SHIR_PHONE);
        String created = mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, adminSecret())
                .param("member", "shir").param("label", "Shir's iPhone")).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(created, "$.token");
        String shahars = newToken("shahar");
        assertThat(session).isNotNull();

        mvc.perform(delete("/admin/devices/" + TestBrowsers.SHIR_PHONE.nodeId()).header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.shortcutTokensStillActive.length()").value(1))
                .andExpect(jsonPath("$.shortcutTokensStillActive[0].id").value(idOf(created)))
                .andExpect(jsonPath("$.shortcutTokensStillActive[0].label").value("Shir's iPhone"))
                .andExpect(jsonPath("$.next").exists());
        // Separate credentials: the token is the admin's to revoke, by id.
        mvc.perform(open(token)).andExpect(status().isAccepted());
        mvc.perform(delete("/admin/shortcut-tokens/" + idOf(created)).header(LocalAdmin.HEADER, adminSecret()));
        mvc.perform(open(token)).andExpect(status().isUnauthorized());
        // Nobody else's: Shahar's token still authenticates. (Ford is still moving from Shir's open, so the garage's own
        // rule answers busy; a refused token would be 401.)
        mvc.perform(open(shahars)).andExpect(status().isConflict()).andExpect(jsonPath("$.outcome").value("BUSY"));
    }

    @Test
    void revokingABrowserWhoseMemberHasNoTokensSaysSo() throws Exception {
        TestBrowsers.signedInOn(context, "shir", "c2hpcnMtcGFzc2tleQ", TestBrowsers.SHIR_PHONE);
        mvc.perform(delete("/admin/devices/" + TestBrowsers.SHIR_PHONE.nodeId()).header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.shortcutTokensStillActive.length()").value(0))
                .andExpect(jsonPath("$.next").doesNotExist());
        mvc.perform(delete("/admin/devices/b-0000000000000000000000000000dead").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(jsonPath("$.revoked").value(false))
                .andExpect(jsonPath("$.shortcutTokensStillActive").doesNotExist());
    }



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
    void onlyForAnActiveFamilyMemberOneDoorAndAtMostAYear() throws Exception {
        String admin = adminSecret();
        mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, admin).param("member", "mallory").param("label", "x"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, admin).param("member", "shir").param("label", "x")
                .param("door", "garage")).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, admin).param("member", "shir").param("label", "x")
                .param("door", "ford,toyota")).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, admin).param("member", "shir").param("label", "x")
                .param("days", "366")).andExpect(status().isBadRequest());
        for (String action : new String[] {"resume", "open,close", "OPEN_AND_CLOSE", "both"}) {
            mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, admin).param("member", "shir").param("label", "x")
                    .param("action", action)).andExpect(status().isBadRequest());
        }
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
