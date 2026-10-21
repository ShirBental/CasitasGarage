package home.casita.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import home.casita.security.access.Invitations;
import home.casita.security.access.LocalAdmin;
import home.casita.security.access.TestTailnet;

/**
 * Cache-Control by kind of response, through the real filter chain: hashed static files for a year, pages and
 * the manifest revalidated, the API and everything about signing in never stored. And none of it changes who
 * may fetch what, or the security headers. Signed-in requests come from Shir's authorized phone through
 * tailscale serve, signed in with a passkey (so they pass AccessCheckFilter); the passkey page from a phone
 * in enrollment, the only session that page is for.
 */
@SpringBootTest(properties = "casita.security.family=shir,hubby")
@Import(TestTailnet.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CacheHeadersTest {

    /** A fresh data directory for every run, emptied after every test: an enrolled phone can't enroll again. */
    private static final Path DATA;

    static {
        try {
            DATA = Files.createTempDirectory("casita-cache-headers");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("casita.security.data-dir", DATA::toString);
    }

    private static final Pattern HASHED = Pattern.compile("-[0-9a-f]{32}\\.[a-z0-9]+$");
    private static final Pattern LINK = Pattern.compile("(?:src|href)=\"(/[^\"]*)\"|\"src\": \"(/[^\"]*)\"");

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private Invitations invitations;
    @Autowired
    private TestTailnet.FakeWhois whois;

    private MockMvc mvc;

    /** Shir, signed in with a passkey, on her authorized phone. */
    private RequestPostProcessor FAMILY;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        FAMILY = on(TestTailnet.signedInOn(context, "shir", "c2hpcnMtcGFzc2tleQ", TestTailnet.SHIR_PHONE), TestTailnet.SHIR_PHONE);
    }

    @AfterEach
    void forgetEnrollments() throws IOException {
        Files.deleteIfExists(DATA.resolve("security.json"));
        whois.reset();
    }

    private static RequestPostProcessor on(MockHttpSession session, TestTailnet.Phone phone) {
        return request -> {
            request.setSession(session);
            return TestTailnet.from(phone).postProcessRequest(request);
        };
    }

    /** Hubby's new phone, after tapping the invite link's Sign in: an enrollment-only session. */
    private RequestPostProcessor enrolling() throws Exception {
        String token = invitations.create("hubby", "Hubby's iPhone").token();
        var redeemed = mvc.perform(post("/login/ott").param("token", token).with(csrf())
                .with(TestTailnet.from(TestTailnet.HUBBY_PHONE))).andReturn();
        assertThat(redeemed.getResponse().getRedirectedUrl()).isEqualTo("/passkey");
        return on((MockHttpSession) redeemed.getRequest().getSession(), TestTailnet.HUBBY_PHONE);
    }

    private MockHttpServletResponse fetch(RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private List<String> links(String page, RequestPostProcessor who) throws Exception {
        String body = fetch(get(page).with(who)).getContentAsString();
        List<String> links = new ArrayList<>();
        Matcher m = LINK.matcher(body);
        while (m.find()) links.add(m.group(1) != null ? m.group(1) : m.group(2));
        return links;
    }

    private static RequestPostProcessor nobody() {
        return request -> request;
    }

    private static void assertImmutable(MockHttpServletResponse response, String what) {
        assertThat(response.getStatus()).as(what).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).as(what)
                .contains("max-age=31536000").contains("public").contains("immutable").doesNotContain("no-");
        assertThat(response.getHeader("Pragma")).as(what).isNull();
    }

    private static void assertNoStore(MockHttpServletResponse response, String what) {
        assertThat(response.getHeader("Cache-Control")).as(what).contains("no-store").doesNotContain("immutable");
    }

    private static void assertSecurityHeaders(MockHttpServletResponse response, String what) {
        assertThat(response.getHeader("Content-Security-Policy")).as(what).contains("default-src 'self'");
        assertThat(response.getHeader("X-Content-Type-Options")).as(what).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).as(what).isEqualTo("DENY");
    }

    /* ---------- static files: hashed URLs, a year, immutable ---------- */

    @Test
    void theHomeScreenLinksEveryFileByItsContentHash() throws Exception {
        List<String> links = links("/", FAMILY);
        assertThat(links).anyMatch(l -> l.matches("/app-[0-9a-f]{32}\\.js")).anyMatch(l -> l.matches("/poller-[0-9a-f]{32}\\.js"))
                .anyMatch(l -> l.matches("/casita-[0-9a-f]{32}\\.css")).anyMatch(l -> l.matches("/icons/apple-touch-icon-[0-9a-f]{32}\\.png"));
        for (String link : links) {
            if (link.equals("/manifest.webmanifest")) continue;
            assertThat(link).as("link on the home screen").containsPattern(HASHED);
            MockHttpServletResponse response = fetch(get(link).with(FAMILY));
            assertImmutable(response, link);
            assertSecurityHeaders(response, link);
        }
    }

    @Test
    void watsonsArtworkIsGivenByItsContentHashAndCachedForAYear() throws Exception {
        String brand = fetch(get("/api/brand").with(FAMILY)).getContentAsString();
        Matcher art = Pattern.compile("\"(/brand/watson-[a-z]+-[0-9a-f]{32}\\.webp)\"").matcher(brand);
        int pictures = 0;
        while (art.find()) {
            assertImmutable(fetch(get(art.group(1)).with(FAMILY)), art.group(1));
            pictures++;
        }
        assertThat(pictures).as("idle and excited Watson in " + brand).isEqualTo(2);
    }

    @Test
    void theManifestsIconsAndTheStylesheetsIconAreHashedToo() throws Exception {
        List<String> icons = links("/manifest.webmanifest", nobody());
        assertThat(icons).hasSize(3).allMatch(l -> l.startsWith("/icons/") && HASHED.matcher(l).find());
        for (String icon : icons) assertImmutable(fetch(get(icon).with(FAMILY)), icon);

        String css = links("/login", nobody()).stream().filter(l -> l.endsWith(".css")).findFirst().orElseThrow();
        assertThat(fetch(get(css)).getContentAsString()).containsPattern("url\\(\"/icons/icon-192-[0-9a-f]{32}\\.png\"\\)");
    }

    @Test
    void aHashThatIsntTheFilesCurrentContentIsNotFoundNeverAStaleFile() throws Exception {
        String app = links("/", FAMILY).stream().filter(l -> l.startsWith("/app-")).findFirst().orElseThrow();
        String stale = app.replaceAll("[0-9a-f]{32}", "0123456789abcdef0123456789abcdef");
        MockHttpServletResponse response = fetch(get(stale).with(FAMILY));
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getHeader("Cache-Control")).doesNotContain("immutable");
    }

    @Test
    void filesByTheirPlainNamesAreRevalidated() throws Exception {
        MockHttpServletResponse response = fetch(get("/app.js").with(FAMILY));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
    }

    /* ---------- who may fetch them: unchanged ---------- */

    @Test
    void theSignInPagesFilesArePublicByTheirHashedNamesAndNothingElseIs() throws Exception {
        List<String> links = links("/login", nobody());
        assertThat(links).anyMatch(l -> l.matches("/login-[0-9a-f]{32}\\.js")).anyMatch(l -> l.matches("/common-[0-9a-f]{32}\\.js"))
                .contains("/login/webauthn.js");
        for (String link : links) {
            if (link.equals("/login/webauthn.js")) continue;    // Spring Security's own script, as before
            assertImmutable(fetch(get(link)), link);
        }
        // The home screen's files still need someone signed in, by either name. (Its app icons are public since
        // PR #8, for Add to Home Screen: PublicFilesTest.)
        List<String> refused = new ArrayList<>();
        for (String link : links("/", FAMILY)) {
            if (link.contains("/common-") || link.contains("/casita-") || link.equals("/manifest.webmanifest")
                    || link.startsWith("/icons/")) continue;
            refused.add(link);
            refused.add(link.replaceAll("-[0-9a-f]{32}(\\.[a-z0-9]+)$", "$1"));
        }
        assertThat(refused).contains("/app.js", "/poller.js", "/voice.js")
                .anyMatch(l -> l.matches("/app-[0-9a-f]{32}\\.js")).anyMatch(l -> l.matches("/poller-[0-9a-f]{32}\\.js"));
        for (String link : refused) {
            MockHttpServletResponse response = fetch(get(link));
            assertThat(response.getStatus()).as(link).isEqualTo(302);
            assertNoStore(response, link);
        }
    }

    /* ---------- pages and the manifest: revalidated ---------- */

    @Test
    void pagesAndTheManifestAreRevalidatedWithAnEtag() throws Exception {
        // y: signed in; e: enrolling (the passkey page is only for setting up a passkey); n: nobody.
        String[][] pages = {{"/", "y"}, {"/index.html", "y"}, {"/passkey", "e"}, {"/webauthn/register", "y"},
                {"/login", "n"}, {"/login.html", "n"}, {"/manifest.webmanifest", "n"}};
        RequestPostProcessor enrolling = enrolling();
        for (String[] page : pages) {
            RequestPostProcessor who = switch (page[1]) {
                case "y" -> FAMILY;
                case "e" -> enrolling;
                default -> nobody();
            };
            MockHttpServletResponse response = fetch(get(page[0]).with(who));
            assertThat(response.getStatus()).as(page[0]).isEqualTo(200);
            assertThat(response.getHeader("Cache-Control")).as(page[0]).isEqualTo("no-cache");
            assertSecurityHeaders(response, page[0]);
            String etag = response.getHeader("ETag");
            assertThat(etag).as(page[0]).isNotBlank();

            MockHttpServletResponse again = fetch(get(page[0]).with(who).header("If-None-Match", etag));
            assertThat(again.getStatus()).as(page[0] + " revalidated").isEqualTo(304);
            assertThat(again.getContentAsString()).isEmpty();
        }
        assertThat(fetch(get("/manifest.webmanifest")).getContentType()).startsWith("application/manifest+json");
    }

    @Test
    void theHomeScreenStillNeedsSomeoneSignedIn() throws Exception {
        MockHttpServletResponse response = fetch(get("/"));
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login");
        assertNoStore(response, "/ signed out");
    }

    /* ---------- the API and signing in: never stored ---------- */

    @Test
    void theApiIsNeverStored() throws Exception {
        for (String api : new String[] {"/api/doors", "/api/activity", "/api/brand", "/api/me"}) {
            MockHttpServletResponse response = fetch(get(api).with(FAMILY));
            assertThat(response.getStatus()).as(api).isEqualTo(200);
            assertNoStore(response, api);
            assertNoStore(fetch(get(api)), api + " signed out");
        }
        assertNoStore(fetch(post("/api/open").with(FAMILY)), "a command without the CSRF token");
    }

    @Test
    void signingInPasskeysAndInvitesAreNeverStored() throws Exception {
        assertNoStore(fetch(post("/webauthn/authenticate/options").with(csrf())), "passkey sign-in options");
        assertNoStore(fetch(post("/webauthn/register/options").with(FAMILY).with(csrf())), "passkey set-up options");
        assertNoStore(fetch(post("/ott/generate").param("username", "nobody")), "asking for an invite");
        assertNoStore(fetch(get("/login/ott").param("token", "not-a-token")), "an invite link");
        assertNoStore(fetch(post("/login/ott").param("token", "not-a-token").with(csrf())), "signing in with an invite");
        assertNoStore(fetch(post("/logout").with(FAMILY).with(csrf())), "signing out");
        assertNoStore(fetch(get("/login/webauthn.js")), "Spring Security's passkey script");
        // Access administration (PR #8): never stored, refused or not.
        String admin = Files.readString(DATA.resolve("admin.secret")).trim();
        MockHttpServletResponse access = fetch(get("/admin/access").header(LocalAdmin.HEADER, admin));
        assertThat(access.getStatus()).isEqualTo(200);
        assertNoStore(access, "the access listing");
        MockHttpServletResponse invite = fetch(post("/admin/invites").param("member", "hubby").param("label", "Hubby's iPad")
                .header(LocalAdmin.HEADER, admin));
        assertThat(invite.getStatus()).isEqualTo(200);
        assertNoStore(invite, "a new invite");
        assertNoStore(fetch(get("/admin/access")), "the access listing without the admin secret");
    }
}
