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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import home.casita.garage.ActivityLog;
import home.casita.garage.DoorRoster;
import home.casita.garage.SimulatedDoor;

/** The API over the practice garage (the default): two simulated doors, nothing reaches Meross. */
@SpringBootTest(properties = "casita.security.data-dir=target/test-data/controller")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GarageControllerTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ActivityLog activity;
    @Autowired
    private DoorRoster roster;

    private MockMvc mvc;

    /** A signed-in family member (the role SecurityConfig requires). */
    private static final org.springframework.test.web.servlet.request.RequestPostProcessor FAMILY = user("shir").roles("FAMILY");

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void theDefaultDoorsAreTwoSeparateSimulatedOnes() {
        assertThat(roster.doors()).extracting(DoorRoster.Door::name).containsExactly("Ford", "Toyota");
        assertThat(roster.doors()).allMatch(d -> d.device() instanceof SimulatedDoor);
        assertThat(roster.doors().get(0).device()).isNotSameAs(roster.doors().get(1).device());
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
        for (String door : new String[] {"ford", "toyota"}) {
            mvc.perform(get("/api/doors/" + door)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/doors/" + door + "/open").with(csrf())).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/doors/" + door + "/close").with(csrf())).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/doors/" + door + "/resume").with(csrf())).andExpect(status().isUnauthorized());
        }
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void someoneSignedInButOutsideTheFamilyIsRefused() throws Exception {
        mvc.perform(get("/api/doors").with(user("stranger"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/open").with(user("stranger")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/doors/toyota/open").with(user("stranger")).with(csrf())).andExpect(status().isForbidden());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void doorsShowsBothPracticeDoorsControllableAndNeitherLive() throws Exception {
        mvc.perform(get("/api/doors").with(FAMILY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.control").value("simulated"))
                .andExpect(jsonPath("$.travelTimeSeconds").value(10))
                .andExpect(jsonPath("$.doors.length()").value(2))
                .andExpect(jsonPath("$.doors[0].key").value("ford"))
                .andExpect(jsonPath("$.doors[0].name").value("Ford"))
                .andExpect(jsonPath("$.doors[0].control").value("simulated"))
                .andExpect(jsonPath("$.doors[0].live").value(false))
                .andExpect(jsonPath("$.doors[0].controllable").value(true))
                .andExpect(jsonPath("$.doors[0].door").value("CLOSED"))
                .andExpect(jsonPath("$.doors[0].travelTimeSeconds").value(10))
                .andExpect(jsonPath("$.doors[0].status.canOpen").value(true))
                .andExpect(jsonPath("$.doors[1].key").value("toyota"))
                .andExpect(jsonPath("$.doors[1].name").value("Toyota"))
                .andExpect(jsonPath("$.doors[1].control").value("simulated"))
                .andExpect(jsonPath("$.doors[1].live").value(false))
                .andExpect(jsonPath("$.doors[1].controllable").value(true))
                .andExpect(jsonPath("$.doors[1].door").value("CLOSED"))
                .andExpect(jsonPath("$.doors[1].status.canOpen").value(true))
                .andExpect(jsonPath("$.doors[1].status.held").value(false));
    }

    @Test
    void oneDoorCanBeReadOnItsOwn() throws Exception {
        mvc.perform(get("/api/doors/Toyota").with(FAMILY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Toyota"))
                .andExpect(jsonPath("$.live").value(false))
                .andExpect(jsonPath("$.status.door").value("CLOSED"));
    }

    @Test
    void anUnknownDoorIsNotFoundAndNothingIsRecorded() throws Exception {
        mvc.perform(get("/api/doors/channel3").with(FAMILY)).andExpect(status().isNotFound());
        mvc.perform(post("/api/doors/channel3/open").with(FAMILY).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/doors/3/close").with(FAMILY).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/doors/channel3/resume").with(FAMILY).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/activity").param("door", "channel3").with(FAMILY)).andExpect(status().isNotFound());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void perDoorCommandsNeedTheCsrfTokenAndNothingHappensWithout() throws Exception {
        for (String door : new String[] {"ford", "toyota"}) {
            mvc.perform(post("/api/doors/" + door + "/open").with(FAMILY)).andExpect(status().isForbidden());
            mvc.perform(post("/api/doors/" + door + "/close").with(FAMILY)).andExpect(status().isForbidden());
            mvc.perform(post("/api/doors/" + door + "/resume").with(FAMILY)).andExpect(status().isForbidden());
        }
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void thereIsNoToggleAndCommandsAreNeverAGet() throws Exception {
        mvc.perform(post("/api/doors/toyota/toggle").with(FAMILY).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/doors/toyota").with(FAMILY).with(csrf())).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/doors/toyota/open").with(FAMILY)).andExpect(status().isMethodNotAllowed());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void openingToyotaMovesOnlyToyotaAndIsRecordedUnderItsName() throws Exception {
        mvc.perform(post("/api/doors/toyota/open").with(FAMILY).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("MOVING"))
                .andExpect(jsonPath("$.status.door").value("OPENING"));

        mvc.perform(get("/api/doors").with(FAMILY))
                .andExpect(jsonPath("$.doors[0].door").value("CLOSED"))
                .andExpect(jsonPath("$.doors[1].door").value("OPENING"));
        mvc.perform(get("/api/activity").with(FAMILY))
                .andExpect(jsonPath("$[0].door").value("Toyota"))
                .andExpect(jsonPath("$[0].who").value("shir"))
                .andExpect(jsonPath("$[0].action").value("OPEN"))
                .andExpect(jsonPath("$[0].outcome").value("MOVING"));
        mvc.perform(get("/api/activity").param("door", "ford").with(FAMILY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void eachDoorHasItsOwnMovementSoBothCanMoveAtOnce() throws Exception {
        mvc.perform(post("/api/doors/ford/open").with(FAMILY).with(csrf())).andExpect(jsonPath("$.outcome").value("MOVING"));
        mvc.perform(post("/api/doors/toyota/open").with(FAMILY).with(csrf())).andExpect(jsonPath("$.outcome").value("MOVING"));
        mvc.perform(post("/api/doors/ford/close").with(FAMILY).with(csrf())).andExpect(jsonPath("$.outcome").value("BUSY"));
        mvc.perform(post("/api/doors/toyota/close").with(FAMILY).with(csrf())).andExpect(jsonPath("$.outcome").value("BUSY"));

        mvc.perform(get("/api/activity").param("door", "toyota").with(FAMILY))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].door").value("Toyota"));
    }

    @Test
    void perDoorResumeWithNothingHeldChangesNothing() throws Exception {
        mvc.perform(post("/api/doors/toyota/resume").with(FAMILY).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.held").value(false));
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void theOlderEndpointsDriveFordOnly() throws Exception {
        mvc.perform(post("/api/open").with(FAMILY).with(csrf())).andExpect(jsonPath("$.outcome").value("MOVING"));

        mvc.perform(get("/api/doors").with(FAMILY))
                .andExpect(jsonPath("$.doors[0].door").value("OPENING"))
                .andExpect(jsonPath("$.doors[1].door").value("CLOSED"));
        mvc.perform(get("/api/activity").with(FAMILY)).andExpect(jsonPath("$[0].door").value("Ford"));
    }

    @Test
    void meSaysWhoIsSignedInAndWhetherTheyHaveAPasskey() throws Exception {
        mvc.perform(get("/api/me").with(FAMILY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("shir"))
                .andExpect(jsonPath("$.passkeys").value(0));
    }

    @Test
    void aCommandWithoutTheCsrfTokenIsRefusedAndNothingHappens() throws Exception {
        mvc.perform(post("/api/open").with(FAMILY)).andExpect(status().isForbidden());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void statusReportsTheDoorAndWhatCanBeDone() throws Exception {
        mvc.perform(get("/api/status").with(FAMILY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.door").value("CLOSED"))
                .andExpect(jsonPath("$.canOpen").value(true))
                .andExpect(jsonPath("$.canClose").value(false))
                .andExpect(jsonPath("$.held").value(false));
    }

    @Test
    void resumeNeedsCsrfAndWhenNothingIsHeldChangesNothing() throws Exception {
        mvc.perform(post("/api/resume").with(FAMILY)).andExpect(status().isForbidden());
        mvc.perform(post("/api/resume").with(FAMILY).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.held").value(false));
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void openGoesThroughGarageServiceAndIsRecordedUnderTheSignedInUser() throws Exception {
        mvc.perform(post("/api/open").with(FAMILY).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("MOVING"))
                .andExpect(jsonPath("$.status.door").value("OPENING"));

        mvc.perform(get("/api/activity").with(FAMILY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].who").value("shir"))
                .andExpect(jsonPath("$[0].action").value("OPEN"))
                .andExpect(jsonPath("$[0].outcome").value("MOVING"));
    }

    @Test
    void closingAClosedDoorSendsNothing() throws Exception {
        mvc.perform(post("/api/close").with(FAMILY).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY"));
    }

    @Test
    void aSecondCommandWhileTheFirstIsUncheckedIsBusy() throws Exception {
        mvc.perform(post("/api/open").with(FAMILY).with(csrf())).andExpect(jsonPath("$.outcome").value("MOVING"));

        mvc.perform(post("/api/close").with(FAMILY).with(csrf()))
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
