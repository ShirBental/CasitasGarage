package home.casita.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import home.casita.garage.ActivityLog;
import home.casita.garage.DoorDevice;
import home.casita.garage.SimulatedDoor;
import home.casita.security.access.TestTailnet;

/**
 * The API over the simulated door (the default): nothing here reaches Meross. Requests come as they would
 * from a family member's authorized phone through tailscale serve, signed in with a passkey.
 */
@SpringBootTest(properties = {"casita.security.data-dir=target/test-data/controller", "casita.security.family=shir"})
@Import(TestTailnet.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GarageControllerTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ActivityLog activity;
    @Autowired
    private DoorDevice door;

    private MockMvc mvc;

    /** Shir, signed in with a passkey, on her authorized phone. */
    private MockHttpSession session;
    private org.springframework.test.web.servlet.request.RequestPostProcessor family;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        session = TestTailnet.signedInOn(context, "shir", "c2hpcnMtcGFzc2tleQ", TestTailnet.SHIR_PHONE);
        family = request -> {
            request.setSession(session);
            return TestTailnet.from(TestTailnet.SHIR_PHONE).postProcessRequest(request);
        };
    }

    @Test
    void theDefaultDoorIsTheSimulatedOne() {
        assertThat(door).isInstanceOf(SimulatedDoor.class);
    }

    @Test
    void everyEndpointNeedsSignIn() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/activity")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/open").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/close").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/resume").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/doors")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void aSignInThatIsntAPasskeyOnAnAuthorizedDeviceIsRefused() throws Exception {
        // Not a passkey session: AccessCheckFilter ends it, and the request goes on signed out.
        mvc.perform(get("/api/doors").with(user("stranger")).with(TestTailnet.from(TestTailnet.SHIR_PHONE))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/open").with(user("shir").roles("FAMILY")).with(TestTailnet.from(TestTailnet.SHIR_PHONE)).with(csrf()))
                .andExpect(status().isUnauthorized());
        // Shir's own session, but not through tailscale serve: the device can't be verified.
        mvc.perform(post("/api/open").session(session).with(csrf())).andExpect(status().isUnauthorized());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void doorsShowsFordControllableAndToyotaReadOnlyInThePracticeGarage() throws Exception {
        mvc.perform(get("/api/doors").with(family))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.control").value("simulated"))
                .andExpect(jsonPath("$.travelTimeSeconds").value(10))
                .andExpect(jsonPath("$.doors[0].name").value("Ford"))
                .andExpect(jsonPath("$.doors[0].controllable").value(true))
                .andExpect(jsonPath("$.doors[0].door").value("CLOSED"))
                .andExpect(jsonPath("$.doors[0].status.canOpen").value(true))
                .andExpect(jsonPath("$.doors[1].name").value("Toyota"))
                .andExpect(jsonPath("$.doors[1].controllable").value(false))
                .andExpect(jsonPath("$.doors[1].door").value("CLOSED"))
                .andExpect(jsonPath("$.doors[1].status").doesNotExist());
    }

    @Test
    void meSaysWhoIsSignedInAndOnWhichDevice() throws Exception {
        mvc.perform(get("/api/me").with(family))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("shir"))
                .andExpect(jsonPath("$.passkeys").value(1))
                .andExpect(jsonPath("$.device").value("shir's phone"));
    }

    @Test
    void aCommandWithoutTheCsrfTokenIsRefusedAndNothingHappens() throws Exception {
        mvc.perform(post("/api/open").with(family)).andExpect(status().isForbidden());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void statusReportsTheDoorAndWhatCanBeDone() throws Exception {
        mvc.perform(get("/api/status").with(family))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.door").value("CLOSED"))
                .andExpect(jsonPath("$.canOpen").value(true))
                .andExpect(jsonPath("$.canClose").value(false))
                .andExpect(jsonPath("$.held").value(false));
    }

    @Test
    void resumeNeedsCsrfAndWhenNothingIsHeldChangesNothing() throws Exception {
        mvc.perform(post("/api/resume").with(family)).andExpect(status().isForbidden());
        mvc.perform(post("/api/resume").with(family).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.held").value(false));
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void openGoesThroughGarageServiceAndIsRecordedUnderTheSignedInUser() throws Exception {
        mvc.perform(post("/api/open").with(family).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("MOVING"))
                .andExpect(jsonPath("$.status.door").value("OPENING"));

        mvc.perform(get("/api/activity").with(family))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].who").value("shir"))
                .andExpect(jsonPath("$[0].action").value("OPEN"))
                .andExpect(jsonPath("$[0].outcome").value("MOVING"));
    }

    @Test
    void closingAClosedDoorSendsNothing() throws Exception {
        mvc.perform(post("/api/close").with(family).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY"));
    }

    @Test
    void aSecondCommandWhileTheFirstIsUncheckedIsBusy() throws Exception {
        mvc.perform(post("/api/open").with(family).with(csrf())).andExpect(jsonPath("$.outcome").value("MOVING"));

        mvc.perform(post("/api/close").with(family).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("BUSY"));
    }

    @Test
    void twoTapsAtOnceSendOneCommand() throws Exception {
        // The app has no hold or confirmation: a double tap, or two phones at once, must still move the door once.
        java.util.concurrent.ExecutorService phones = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.List<java.util.concurrent.Future<String>> taps = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            taps.add(phones.submit(() -> com.jayway.jsonpath.JsonPath.read(mvc.perform(post("/api/open").with(FAMILY).with(csrf()))
                    .andReturn().getResponse().getContentAsString(), "$.outcome")));
        }
        java.util.List<String> outcomes = new java.util.ArrayList<>();
        for (var tap : taps) outcomes.add(tap.get());
        phones.shutdown();

        assertThat(outcomes).containsOnlyOnce("MOVING").containsOnly("MOVING", "BUSY");
        assertThat(activity.recent(10)).filteredOn(e -> e.outcome() == home.casita.garage.Outcome.MOVING).hasSize(1);
    }
}
