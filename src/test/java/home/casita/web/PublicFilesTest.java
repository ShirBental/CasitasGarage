package home.casita.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.resource.ResourceUrlProvider;

import home.casita.security.access.Invitations;
import home.casita.security.access.LocalAdmin;
import home.casita.security.access.SoftAuthenticator;
import home.casita.security.access.TestTailnet;
import home.casita.security.store.SecurityStore;

/**
 * What a phone without a Casita session can fetch, now that pages link files by content-hashed URLs: everything
 * the passkey page links, for a signed-out browser and for one in enrollment (a new phone setting up Face ID),
 * and the Add to Home Screen icons, which iOS fetches without the cookie. And nothing more: the home screen's
 * own files, Watson's artwork and the API stay refused, by either name.
 */
@SpringBootTest(properties = "casita.security.family=shir,hubby")
@Import(TestTailnet.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PublicFilesTest {

    private static final Path DATA;

    static {
        try {
            DATA = Files.createTempDirectory("casita-public-files");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("casita.security.data-dir", DATA::toString);
    }

    private static final Pattern HASHED = Pattern.compile("-[0-9a-f]{32}\\.[a-z0-9]+$");
    private static final Pattern LINK = Pattern.compile("(?:src|href)=\"(/[^\"]*)\"|\"src\": \"(/[^\"]*)\"|url\\(\"(/[^\"]*)\"\\)");

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private Invitations invitations;
    @Autowired
    private ResourceUrlProvider urls;
    @Autowired
    private TestTailnet.FakeWhois whois;
    @Autowired
    private SecurityStore store;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void forgetEnrollments() throws IOException {
        Files.deleteIfExists(DATA.resolve("security.json"));
        whois.reset();
    }

    private MockHttpServletResponse fetch(String url, RequestPostProcessor who) throws Exception {
        return mvc.perform(get(url).with(who)).andReturn().getResponse();
    }

    private static RequestPostProcessor nobody() {
        return request -> request;
    }

    private static RequestPostProcessor in(MockHttpSession session, TestTailnet.Phone phone) {
        return request -> {
            request.setSession(session);
            return TestTailnet.from(phone).postProcessRequest(request);
        };
    }

    /** Hubby's new phone, after tapping the invite link's Sign in: an enrollment-only session. */
    private MockHttpSession enrolling() throws Exception {
        String token = invitations.create("hubby", "Hubby's iPhone").token();
        var redeemed = mvc.perform(post("/login/ott").param("token", token).with(csrf())
                .with(TestTailnet.from(TestTailnet.HUBBY_PHONE))).andReturn();
        assertThat(redeemed.getResponse().getRedirectedUrl()).isEqualTo("/passkey");
        return (MockHttpSession) redeemed.getRequest().getSession();
    }

    /** Every link in the page (as served to {@code who}), and in the stylesheets it links. */
    private List<String> links(String page, RequestPostProcessor who) throws Exception {
        MockHttpServletResponse response = fetch(page, who);
        assertThat(response.getStatus()).as(page).isEqualTo(200);
        List<String> links = new ArrayList<>();
        collect(response.getContentAsString(), links);
        for (String link : List.copyOf(links)) {
            if (link.endsWith(".css")) collect(fetch(link, who).getContentAsString(), links);
        }
        return links;
    }

    private static void collect(String text, List<String> into) {
        Matcher m = LINK.matcher(text);
        while (m.find()) into.add(m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3));
    }

    private String hashed(String file) {
        String url = urls.getForLookupPath(file);
        assertThat(url).as(file).containsPattern(HASHED);
        return url;
    }

    private static void assertLoads(MockHttpServletResponse response, String what) {
        assertThat(response.getStatus()).as(what).isEqualTo(200);
        assertThat(response.getRedirectedUrl()).as(what).isNull();
    }

    /* ---------- the passkey page, for a phone that isn't signed in ---------- */

    @Test
    void aSignedOutBrowserLoadsEverythingThePasskeyPageLinks() throws Exception {
        List<String> links = links("/passkey.html", nobody());
        assertThat(links).anyMatch(l -> l.matches("/passkey-[0-9a-f]{32}\\.js")).anyMatch(l -> l.matches("/common-[0-9a-f]{32}\\.js"))
                .anyMatch(l -> l.matches("/casita-[0-9a-f]{32}\\.css")).anyMatch(l -> l.matches("/icons/icon-192-[0-9a-f]{32}\\.png"))
                .contains("/login/webauthn.js", "/login");
        for (String link : links) assertLoads(fetch(link, nobody()), link + " signed out");
    }

    @Test
    void anEnrollingBrowserLoadsEverythingThePasskeyPageLinksAndStaysEnrolling() throws Exception {
        MockHttpSession session = enrolling();
        RequestPostProcessor phone = in(session, TestTailnet.HUBBY_PHONE);
        List<String> links = links("/passkey", phone);
        assertThat(links).anyMatch(l -> l.matches("/passkey-[0-9a-f]{32}\\.js")).anyMatch(l -> l.matches("/common-[0-9a-f]{32}\\.js"))
                .anyMatch(l -> l.matches("/casita-[0-9a-f]{32}\\.css")).anyMatch(l -> l.matches("/icons/icon-192-[0-9a-f]{32}\\.png"))
                .contains("/login/webauthn.js");
        for (String link : links) {
            if (link.equals("/login")) continue;   // the sign-in page: public, but leaving enrollment is another test's business
            assertLoads(fetch(link, phone), link + " enrolling");
        }
        // Still the same enrollment, and still only that: the passkey page yes, the garage no.
        assertThat(session.isInvalid()).isFalse();
        assertLoads(fetch("/passkey", phone), "/passkey after loading its files");
        assertThat(fetch("/api/doors", phone).getStatus()).isEqualTo(403);
        assertThat(fetch(hashed("/app.js"), phone).getStatus()).isEqualTo(403);
    }

    /* ---------- a new phone, end to end, with real WebAuthn ---------- */

    private int send(String url, RequestPostProcessor who, String json) throws Exception {
        var request = post(url).with(who).with(csrf());
        if (json != null) request.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private String options(String url, RequestPostProcessor who) throws Exception {
        var response = mvc.perform(post(url).with(who).with(csrf()))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as(url).isEqualTo(200);
        return response.getContentAsString();
    }

    @Test
    void aNewPhoneEnrollsWithARealPasskeySignsInAndIsShutOutOnceRevoked() throws Exception {
        SoftAuthenticator iphone = new SoftAuthenticator();
        String token = invitations.create("hubby", "Hubby's iPhone").token();

        // Signed out: the sign-in page and its files (the invite link's page).
        for (String link : links("/login", nobody())) assertLoads(fetch(link, nobody()), link + " before redeeming");

        // Redeemed: an enrollment session, the passkey page and every file it links, hashed script included.
        var redeemed = mvc.perform(post("/login/ott")
                .param("token", token).with(csrf()).with(TestTailnet.from(TestTailnet.HUBBY_PHONE))).andReturn();
        assertThat(redeemed.getResponse().getRedirectedUrl()).isEqualTo("/passkey");
        MockHttpSession enrollment = (MockHttpSession) redeemed.getRequest().getSession();
        RequestPostProcessor enrolling = in(enrollment, TestTailnet.HUBBY_PHONE);
        List<String> passkeyPage = links("/passkey", enrolling);
        assertThat(passkeyPage).anyMatch(l -> l.matches("/passkey-[0-9a-f]{32}\\.js"));
        for (String link : passkeyPage) if (!link.equals("/login")) assertLoads(fetch(link, enrolling), link + " enrolling");

        // Face ID set up: Spring's real WebAuthn verification of a real attestation.
        String attestation = iphone.register(options("/webauthn/register/options", enrolling), "iCloud Keychain");
        assertThat(send("/webauthn/register", enrolling, attestation)).isEqualTo(200);
        assertThat(store.device(TestTailnet.HUBBY_PHONE.nodeId())).get().satisfies(d -> assertThat(d.activeFor("hubby")).isTrue());
        assertLoads(fetch("/webauthn/register?success", nobody()), "where the passkey script lands");

        // Signed in with that passkey, on that phone: the home screen and everything it links.
        MockHttpSession session = new MockHttpSession();
        RequestPostProcessor phone = in(session, TestTailnet.HUBBY_PHONE);
        assertThat(send("/login/webauthn", phone, iphone.assertion(options("/webauthn/authenticate/options", phone)))).isEqualTo(200);
        assertThat(fetch("/api/doors", phone).getStatus()).isEqualTo(200);
        for (String link : links("/", phone)) assertLoads(fetch(link, phone), link + " signed in");
        String app = hashed("/app.js");

        // The phone revoked on the server: its session ends on the next request; the public files stay public.
        var revoked = mvc.perform(delete("/admin/devices/" + TestTailnet.HUBBY_PHONE.nodeId())
                .header(LocalAdmin.HEADER, Files.readString(DATA.resolve("admin.secret")).trim())).andReturn().getResponse();
        assertThat(revoked.getContentAsString()).contains("\"revoked\":true");
        assertThat(fetch("/api/doors", phone).getStatus()).isEqualTo(401);
        assertThat(session.isInvalid()).isTrue();
        MockHttpSession again = new MockHttpSession();
        RequestPostProcessor phoneAgain = in(again, TestTailnet.HUBBY_PHONE);
        assertThat(send("/login/webauthn", phoneAgain, iphone.assertion(options("/webauthn/authenticate/options", phoneAgain))))
                .as("a passkey sign-in on a revoked phone").isNotEqualTo(200);
        assertThat(fetch("/api/doors", phoneAgain).getStatus()).isEqualTo(401);
        assertThat(send("/api/doors/ford/open", phoneAgain, null)).isIn(401, 403);
        assertThat(fetch(app, phoneAgain).getStatus()).isEqualTo(302);
        assertThat(fetch(hashed("/passkey.js"), phoneAgain).getStatus()).isEqualTo(200);
        assertThat(fetch("/api/passkey-0123456789abcdef0123456789abcdef.js", phoneAgain).getStatus()).isEqualTo(401);
    }

    /* ---------- Add to Home Screen ---------- */

    @Test
    void addToHomeScreenIconsLoadWithoutASession() throws Exception {
        MockHttpSession shir = TestTailnet.signedInOn(context, "shir", "c2hpcnMtcGFzc2tleQ", TestTailnet.SHIR_PHONE);
        List<String> icons = new ArrayList<>(links("/manifest.webmanifest", nobody()));
        links("/", in(shir, TestTailnet.SHIR_PHONE)).stream().filter(l -> l.startsWith("/icons/")).forEach(icons::add);
        assertThat(icons).anyMatch(l -> l.matches("/icons/apple-touch-icon-[0-9a-f]{32}\\.png"))
                .anyMatch(l -> l.matches("/icons/icon-512-[0-9a-f]{32}\\.png"))
                .anyMatch(l -> l.matches("/icons/icon-maskable-512-[0-9a-f]{32}\\.png"))
                .allMatch(l -> l.startsWith("/icons/") && HASHED.matcher(l).find());
        for (String icon : icons) {
            MockHttpServletResponse response = fetch(icon, nobody());
            assertLoads(response, icon + " without a session");
            assertThat(response.getContentType()).as(icon).isEqualTo("image/png");
        }
    }

    /* ---------- and nothing else ---------- */

    @Test
    void nothingElseBecomesPublicByItsHashedName() throws Exception {
        List<String> refused = new ArrayList<>(List.of(hashed("/app.js"), hashed("/poller.js"), hashed("/voice.js"),
                hashed("/brand/watson-idle.webp"), hashed("/brand/watson-excited.webp"), hashed("/brand/watson-home.png"),
                hashed("/index.html"),
                // Look-alikes of the passkey script's hashed name, anywhere but the root.
                "/brand/passkey-0123456789abcdef0123456789abcdef.js", "/api/passkey-0123456789abcdef0123456789abcdef.js"));
        refused.add(hashed("/passkey.js").replace(".js", ".js/app.js"));
        for (String url : refused) {
            MockHttpServletResponse signedOut = fetch(url, nobody());
            assertThat(signedOut.getStatus()).as(url + " signed out").isIn(302, 401);
            assertThat(signedOut.getContentAsString()).as(url).isEmpty();
        }
        MockHttpSession session = enrolling();
        for (String url : refused) {
            assertThat(fetch(url, in(session, TestTailnet.HUBBY_PHONE)).getStatus()).as(url + " enrolling").isEqualTo(403);
        }
    }

    @Test
    void theDoorAndTheApiStayClosed() throws Exception {
        String[] api = {"/api/doors", "/api/doors/ford", "/api/doors/toyota", "/api/status", "/api/activity", "/api/brand", "/api/me"};
        for (String url : api) assertThat(fetch(url, nobody()).getStatus()).as(url + " signed out").isEqualTo(401);
        for (String door : new String[] {"ford", "toyota"}) {
            for (String command : new String[] {"open", "close", "resume"}) {
                String url = "/api/doors/" + door + "/" + command;
                assertThat(mvc.perform(post(url).with(csrf())).andReturn().getResponse().getStatus()).as(url).isEqualTo(401);
            }
        }
        assertThat(fetch("/", nobody()).getRedirectedUrl()).isEqualTo("/login");
        assertThat(fetch("/passkey", nobody()).getRedirectedUrl()).isEqualTo("/login");
        assertThat(fetch("/admin/devices", nobody()).getStatus()).isEqualTo(403);

        MockHttpSession session = enrolling();
        RequestPostProcessor phone = in(session, TestTailnet.HUBBY_PHONE);
        for (String url : api) assertThat(fetch(url, phone).getStatus()).as(url + " enrolling").isEqualTo(403);
        assertThat(mvc.perform(post("/api/doors/ford/open").with(phone).with(csrf())).andReturn().getResponse().getStatus()).isEqualTo(403);
    }
}
