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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import home.casita.security.access.TestTailnet;

/** Sign-in through the real filter chain: public pages, passkey options, CSRF, CSP. Invites and devices: AccessFlowTest. */
@SpringBootTest
@Import(TestTailnet.Config.class)
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
    void settingUpAPasskeyNeedsAnEnrollmentNotJustASignIn() throws Exception {
        mvc.perform(post("/webauthn/register/options").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/webauthn/register/options").with(csrf()).with(user("shir").roles("FAMILY")).with(TestTailnet.from(TestTailnet.SHIR_PHONE)))
                .andExpect(status().isForbidden());
        // The enrollment itself is covered in AccessFlowTest.
    }

    /* ---------- invites ---------- */

    @Test
    void aWrongInviteTokenSignsNobodyIn() throws Exception {
        mvc.perform(post("/login/ott").param("token", "not-a-token").with(csrf()).with(TestTailnet.from(TestTailnet.SHIR_PHONE)))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?error"));
        // From a device Casita can't identify, it isn't even tried.
        mvc.perform(post("/login/ott").param("token", "not-a-token").with(csrf()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?device-unverified"));
    }

    @Test
    void theInviteLinkOpensSpringsTokenPage() throws Exception {
        mvc.perform(get("/login/ott").param("token", "anything")).andExpect(status().isOk());
    }

    /* ---------- signing out ---------- */

    @Test
    void signingOutNeedsTheCsrfTokenAndEndsAtTheSignInPage() throws Exception {
        mvc.perform(post("/logout").with(user("shir").roles("FAMILY"))).andExpect(status().isForbidden());
        mvc.perform(post("/logout").with(user("shir").roles("FAMILY")).with(csrf()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?signed-out"));
    }
}
