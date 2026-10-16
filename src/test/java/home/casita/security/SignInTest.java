package home.casita.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Sign-in end to end through the real filter chain: public pages, passkey options, invites, CSRF, CSP. */
@SpringBootTest
@DirtiesContext
class SignInTest {

    private static final Path DATA;

    static {
        try {
            DATA = Files.createTempDirectory("casita-signin");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("casita.security.data-dir", DATA::toString);
        registry.add("casita.security.family", () -> "shir,hubby");
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /* ---------- what's public, and what isn't ---------- */

    @Test
    void theAppSendsYouToSignIn() throws Exception {
        mvc.perform(get("/")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/app.js")).andExpect(status().isFound());
        mvc.perform(get("/passkey")).andExpect(status().isFound());
    }

    @Test
    void theRedirectToSignInIsRelativeSoItSurvivesTheTailscaleProxy() throws Exception {
        // tailscale serve forwards to http://127.0.0.1:8080; an absolute redirect would send the phone there.
        mvc.perform(get("/").header("Host", "127.0.0.1:8080")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
    }

    @Test
    void theSignInPageAndOnlyWhatItNeedsArePublic() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(forwardedUrl("/login.html"));
        mvc.perform(get("/login.html")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in with Face ID")));
        for (String asset : new String[] {"/login.js", "/common.js", "/casita.css", "/watson.svg"}) {
            mvc.perform(get(asset)).andExpect(status().isOk());
        }
        mvc.perform(get("/login/webauthn.js")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("setupLogin")));
    }

    @Test
    void pagesCarryAStrictContentSecurityPolicy() throws Exception {
        mvc.perform(get("/login.html"))
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.CSP))
                .andExpect(header().string("X-Frame-Options", "DENY"));
        assertThat(SecurityConfig.CSP).contains("script-src 'self'").doesNotContain("unsafe-inline");
        // The XSRF-TOKEN cookie is checked in CsrfCookieTest: csrf() below changes the shared context.
    }

    @Test
    void theStaticPagesHaveNoInlineScripts() throws Exception {
        for (String page : new String[] {"login.html", "passkey.html", "index.html"}) {
            String html = new String(new org.springframework.core.io.ClassPathResource("static/" + page).getContentAsByteArray());
            assertThat(html).as(page).doesNotContainPattern("<script>(?!</script>)").doesNotContain("onclick=").doesNotContain("style=\"");
        }
    }

    /* ---------- passkeys ---------- */

    @Test
    void passkeySignInOptionsAreForTheConfiguredRelyingPartyAndNeedTheCsrfToken() throws Exception {
        mvc.perform(post("/webauthn/authenticate/options")).andExpect(status().isForbidden());
        mvc.perform(post("/webauthn/authenticate/options").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rpId").value("localhost"))
                .andExpect(jsonPath("$.challenge").isNotEmpty());
    }

    @Test
    void settingUpAPasskeyNeedsSomeoneSignedIn() throws Exception {
        mvc.perform(post("/webauthn/register/options").with(csrf())).andExpect(status().is4xxClientError());
        mvc.perform(post("/webauthn/register/options").with(csrf()).with(user("shir").roles("FAMILY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rp.id").value("localhost"))
                .andExpect(jsonPath("$.user.name").value("shir"));
    }

    /* ---------- invites ---------- */

    @Test
    void anInviteIsWrittenToAPrivateFileUsedOnceAndLeadsToFaceIdSetup() throws Exception {
        MvcResult asked = mvc.perform(post("/ott/generate").param("username", "hubby"))
                .andExpect(status().isOk()).andReturn();
        assertThat(asked.getResponse().getContentAsString()).doesNotContain("token=");

        Path invite = DATA.resolve("invites/hubby.txt");
        assertThat(invite).exists();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(invite))).isEqualTo("rw-------");
        Matcher link = Pattern.compile("^http://localhost:8080/login/ott\\?token=(\\S+)$").matcher(Files.readString(invite).trim());
        assertThat(link.matches()).isTrue();
        String token = link.group(1);

        // The link opens Spring's token page, which posts the token back.
        mvc.perform(get("/login/ott").param("token", token)).andExpect(status().isOk());
        MockHttpSession session = (MockHttpSession) mvc.perform(post("/login/ott").param("token", token).with(csrf()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/passkey"))
                .andReturn().getRequest().getSession();
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("hubby"));

        // Single use.
        mvc.perform(post("/login/ott").param("token", token).with(csrf())).andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void anInviteForSomeoneOutsideTheFamilyWritesNothingAndAnswersTheSame() throws Exception {
        String family = mvc.perform(post("/ott/generate").param("username", "shir")).andReturn().getResponse().getContentAsString();
        String stranger = mvc.perform(post("/ott/generate").param("username", "stranger")).andReturn().getResponse().getContentAsString();

        assertThat(stranger).isEqualTo(family);
        assertThat(DATA.resolve("invites/stranger.txt")).doesNotExist();
    }

    @Test
    void aWrongInviteTokenSignsNobodyIn() throws Exception {
        mvc.perform(post("/login/ott").param("token", "not-a-token").with(csrf()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?error"));
    }

    /* ---------- signing out ---------- */

    @Test
    void signingOutNeedsTheCsrfTokenAndEndsAtTheSignInPage() throws Exception {
        mvc.perform(post("/logout").with(user("shir").roles("FAMILY"))).andExpect(status().isForbidden());
        mvc.perform(post("/logout").with(user("shir").roles("FAMILY")).with(csrf()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?signed-out"));
    }
}
