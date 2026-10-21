package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import home.casita.garage.ActivityLog;
import home.casita.garage.Outcome;
import home.casita.security.access.TestTailnet;

/**
 * The API with the real doors, Ford switched on live and Toyota not: the app is told, per door, that only Ford
 * is live, and Toyota's commands are refused before anything is read or sent. Meross points at a closed local
 * port, so reads fail fast (UNKNOWN) and nothing reaches Meross; no Ford command is sent. Shir is signed in with a
 * passkey on her authorized phone, as in GarageControllerTest.
 */
@SpringBootTest(properties = {
        "casita.door=meross",
        "casita.meross.api-base-url=http://127.0.0.1:1",
        "casita.meross.request-timeout=1s",
        "casita.meross.email=placeholder@example.com",
        "casita.meross.password=placeholder",
        "casita.meross.command-mode=live",
        "casita.meross.command-channels=1",
        // Live needs Ford's own timings. Test values, not measurements: this test never moves a door.
        "casita.garage.travel-times.Ford=30s",
        "casita.garage.sensor-lags.Ford=5s",
        "casita.security.family=shir"})
@Import(TestTailnet.Config.class)
class MerossDoorsApiTest {

    /** A fresh data directory for every run: an enrolled phone from before would refuse re-enrollment. */
    private static final java.nio.file.Path DATA;

    static {
        try {
            DATA = java.nio.file.Files.createTempDirectory("casita-meross-api");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("casita.security.data-dir", DATA::toString);
    }

    @AfterEach
    void forgetEnrollments() throws java.io.IOException {
        java.nio.file.Files.deleteIfExists(DATA.resolve("security.json"));
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ActivityLog activity;

    @Test
    void onlyTheSwitchedOnDoorIsReportedLiveAndToyotaIsRefused() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        MockHttpSession session = TestTailnet.signedInOn(context, "shir", "c2hpcnMtcGFzc2tleQ", TestTailnet.SHIR_PHONE);
        org.springframework.test.web.servlet.request.RequestPostProcessor family = request -> {
            request.setSession(session);
            return TestTailnet.from(TestTailnet.SHIR_PHONE).postProcessRequest(request);
        };

        mvc.perform(get("/api/doors").with(family))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doors[0].name").value("Ford"))
                .andExpect(jsonPath("$.doors[0].control").value("live"))
                .andExpect(jsonPath("$.doors[0].live").value(true))
                .andExpect(jsonPath("$.doors[1].name").value("Toyota"))
                .andExpect(jsonPath("$.doors[1].control").value("off"))
                .andExpect(jsonPath("$.doors[1].live").value(false))
                .andExpect(jsonPath("$.doors[1].controllable").value(false));

        mvc.perform(post("/api/doors/toyota/open").with(family).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DISABLED"));
        mvc.perform(post("/api/doors/toyota/close").with(family).with(csrf()))
                .andExpect(jsonPath("$.outcome").value("DISABLED"));

        assertThat(activity.recent(10)).allMatch(e -> e.door().equals("Toyota") && e.outcome() == Outcome.DISABLED);
    }
}
