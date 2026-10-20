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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.resource.ResourceUrlProvider;

import home.casita.security.access.Invitations;
import home.casita.security.access.TestTailnet;

/**
 * The passkey page links its files by content-hashed URLs (PagesConfig). A phone setting up its passkey has no
 * family session yet: signed out, or enrolling. It must still load every one of those files, and nothing more:
 * the home screen's files and the API stay refused, by either name. (The full enrollment with a real passkey,
 * end to end, is in PR #9's PublicFilesTest, where the security store exists.)
 */
@SpringBootTest(properties = "casita.security.family=shir,hubby")
@Import(TestTailnet.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class EnrollmentPageFilesTest {

    private static final Path DATA;

    static {
        try {
            DATA = Files.createTempDirectory("casita-enrollment-files");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("casita.security.data-dir", DATA::toString);
    }

    private static final Pattern LINK = Pattern.compile("(?:src|href)=\"(/[^\"]*)\"|\"src\": \"(/[^\"]*)\"|url\\(\"(/[^\"]*)\"\\)");

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private Invitations invites;
    @Autowired
    private ResourceUrlProvider urls;
    @Autowired
    private TestTailnet.FakeWhois whois;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void forget() {
        whois.reset();
    }

    private MockHttpServletResponse fetch(String url, RequestPostProcessor who) throws Exception {
        return mvc.perform(get(url).with(who)).andReturn().getResponse();
    }

    private static RequestPostProcessor in(MockHttpSession session, TestTailnet.Phone phone) {
        return request -> {
            request.setSession(session);
            return TestTailnet.from(phone).postProcessRequest(request);
        };
    }

    private List<String> links(String page, RequestPostProcessor who) throws Exception {
        MockHttpServletResponse response = fetch(page, who);
        assertThat(response.getStatus()).as(page).isEqualTo(200);
        List<String> links = new ArrayList<>();
        Matcher m = LINK.matcher(response.getContentAsString());
        while (m.find()) links.add(m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3));
        return links;
    }

    private static void assertLoads(MockHttpServletResponse response, String what) {
        assertThat(response.getStatus()).as(what).isEqualTo(200);
        assertThat(response.getRedirectedUrl()).as(what).isNull();
    }

    @Test
    void aSignedOutBrowserLoadsEverythingThePasskeyPageLinksIncludingItsHashedScript() throws Exception {
        List<String> links = links("/passkey.html", request -> request);
        assertThat(links).anyMatch(l -> l.matches("/passkey-[0-9a-f]{32}\\.js"));
        for (String link : links) assertLoads(fetch(link, request -> request), link + " signed out");
    }

    @Test
    void anEnrollingPhoneLoadsThePasskeyPagesFilesAndNothingElse() throws Exception {
        String token = invites.create("hubby", "Hubby's iPhone").token();
        var redeemed = mvc.perform(post("/login/ott").param("token", token).with(csrf()).with(TestTailnet.from(TestTailnet.HUBBY_PHONE)))
                .andReturn();
        assertThat(redeemed.getResponse().getRedirectedUrl()).isEqualTo("/passkey");
        RequestPostProcessor phone = in((MockHttpSession) redeemed.getRequest().getSession(), TestTailnet.HUBBY_PHONE);

        List<String> links = links("/passkey", phone);
        assertThat(links).anyMatch(l -> l.matches("/passkey-[0-9a-f]{32}\\.js"));
        for (String link : links) {
            if (!link.equals("/login")) assertLoads(fetch(link, phone), link + " enrolling");
        }
        assertThat(fetch("/api/doors", phone).getStatus()).isEqualTo(403);
        assertThat(fetch(urls.getForLookupPath("/app.js"), phone).getStatus()).isEqualTo(403);
    }

    @Test
    void hashedNamesOfNonPublicFilesAndHashedLookingApiPathsStayRefused() throws Exception {
        for (String url : List.of(urls.getForLookupPath("/app.js"), urls.getForLookupPath("/poller.js"),
                "/api/doors-0123456789abcdef0123456789abcdef.js", "/passkey-0123456789abcdef0123456789abcdef.json")) {
            MockHttpServletResponse response = fetch(url, request -> request);
            assertThat(response.getStatus()).as(url).isNotEqualTo(200);
        }
        assertThat(fetch("/api/doors", request -> request).getStatus()).isEqualTo(401);
    }
}
