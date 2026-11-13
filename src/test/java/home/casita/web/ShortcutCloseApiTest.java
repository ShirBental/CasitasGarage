package home.casita.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

import home.casita.garage.ActivityLog;
import home.casita.garage.Outcome;
import home.casita.security.access.LocalAdmin;
import home.casita.security.access.TestBrowsers;

/**
 * "Close Ford Garage" end to end, on the practice garage with a 1 s travel time so a door can really settle: open with
 * the phone's open token, wait for the door to settle, close with its close token. The close is an ordinary close: no
 * "I've looked" entry, through the same rules as the app.
 */
@SpringBootTest(properties = {"casita.security.family=shir,shahar", "casita.garage.travel-time=1s", "casita.garage.verify-grace=0s"})
@Import(TestBrowsers.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ShortcutCloseApiTest {

    private static final Path DATA;

    static {
        try {
            DATA = Files.createTempDirectory("casita-shortcut-close");
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

    private String token(String door, String action) throws Exception {
        String body = mvc.perform(post("/admin/shortcut-tokens").header(LocalAdmin.HEADER, Files.readString(DATA.resolve("admin.secret")).trim())
                        .param("member", "shir").param("label", "Shir's iPhone").param("door", door).param("action", action))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + JsonPath.read(body, "$.token");
    }

    private static RequestPostProcessor phone() {
        return request -> {
            request.setSecure(true);
            request.setScheme("https");
            request.setRemoteAddr("203.0.113.20");
            return request;
        };
    }

    /**
     * Asks for the door's status until it says {@code state}, for at most 12 s, once a second: the Shortcut endpoints
     * allow a client {@link home.casita.security.shortcut.ShortcutRateLimiter#PER_CLIENT} requests per window.
     */
    private void awaitState(String door, String bearer, String state) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(12));
        while (true) {
            String body = mvc.perform(get("/shortcut/v1/doors/" + door).with(phone()).header("Authorization", bearer))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            if (state.equals(JsonPath.read(body, "$.doorState"))) return;
            assertThat(Instant.now()).as(door + " never reached " + state + ": " + body).isBefore(deadline);
            Thread.sleep(1000);
        }
    }

    @Test
    void closeFordGarageClosesASettledOpenDoorThroughTheSameRules() throws Exception {
        String open = token("ford", "open");
        String close = token("ford", "close");

        mvc.perform(post("/shortcut/v1/doors/ford/open").with(phone()).header("Authorization", open)).andExpect(status().isAccepted());
        awaitState("ford", close, "NOT_CLOSED");

        mvc.perform(post("/shortcut/v1/doors/ford/close").with(phone()).header("Authorization", close))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("accepted"))
                .andExpect(jsonPath("$.outcome").value("MOVING"))
                .andExpect(jsonPath("$.door").value("Ford"))
                .andExpect(jsonPath("$.speak").value("Practice door, not the real one. Closing Ford. It isn't confirmed yet: the sensor will say once it's closed."));
        awaitState("ford", close, "CLOSED");

        assertThat(activity.recent(10)).extracting(e -> e.outcome().name()).doesNotContain("CHECKED"); // no "I looked" (PR #15)
        assertThat(activity.recent(10)).filteredOn(e -> e.outcome() == Outcome.MOVING)
                .extracting(e -> e.who() + " " + e.action()).containsExactly("shir via Shortcut CLOSE", "shir via Shortcut OPEN");
        // Toyota never moved.
        assertThat(activity.recent(10)).extracting(e -> e.door()).containsOnly("Ford");
    }

    @Test
    void closeToyotaGarageWorksOnItsOwn() throws Exception {
        String open = token("toyota", "open");
        String close = token("toyota", "close");

        mvc.perform(post("/shortcut/v1/doors/toyota/open").with(phone()).header("Authorization", open)).andExpect(status().isAccepted());
        awaitState("toyota", close, "NOT_CLOSED");
        mvc.perform(post("/shortcut/v1/doors/toyota/close").with(phone()).header("Authorization", close))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.door").value("Toyota"));
        awaitState("toyota", close, "CLOSED");
    }
}
