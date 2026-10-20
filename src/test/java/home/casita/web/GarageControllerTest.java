package home.casita.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import home.casita.garage.DoorRoster;
import home.casita.garage.SimulatedDoor;
import home.casita.security.access.LocalAdmin;
import home.casita.security.access.TestTailnet;

/**
 * The API over the practice garage (the default): two simulated doors, nothing reaches Meross. Requests come as
 * they would from a family member's authorized phone through tailscale serve, signed in with a passkey, so
 * every request also passes AccessCheckFilter.
 */
@SpringBootTest(properties = "casita.security.family=shir")
@Import(TestTailnet.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GarageControllerTest {

    /** A fresh data directory for every run (and, below, every context): enrolled phones from before would refuse re-enrollment. */
    private static final java.nio.file.Path DATA;

    static {
        try {
            DATA = java.nio.file.Files.createTempDirectory("casita-controller");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @org.springframework.test.context.DynamicPropertySource
    static void dataDir(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("casita.security.data-dir", DATA::toString);
    }

    @org.junit.jupiter.api.AfterEach
    void forgetEnrollments() throws java.io.IOException {
        java.nio.file.Files.deleteIfExists(DATA.resolve("security.json"));
        whois.reset();
    }

    private static final String[] DOORS = {"ford", "toyota"};

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ActivityLog activity;
    @Autowired
    private DoorRoster roster;
    @Autowired
    private TestTailnet.FakeWhois whois;

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

    private String adminSecret() throws java.io.IOException {
        return java.nio.file.Files.readString(DATA.resolve("admin.secret")).trim();
    }

    /** Every door endpoint, read or command, answers 401 to this request and nothing is recorded. */
    private void doorApiRefuses(org.springframework.test.web.servlet.request.RequestPostProcessor as) throws Exception {
        mvc.perform(get("/api/doors").with(as)).andExpect(status().isUnauthorized());
        for (String door : DOORS) {
            mvc.perform(get("/api/doors/" + door).with(as)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/doors/" + door + "/open").with(as).with(csrf())).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/doors/" + door + "/close").with(as).with(csrf())).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/doors/" + door + "/resume").with(as).with(csrf())).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/open").with(as).with(csrf())).andExpect(status().isUnauthorized());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void theDefaultDoorsAreTwoSeparateSimulatedOnes() {
        assertThat(roster.doors()).extracting(DoorRoster.Door::name).containsExactly("Ford", "Toyota");
        assertThat(roster.doors()).allMatch(d -> d.device() instanceof SimulatedDoor);
        assertThat(roster.doors().get(0).device()).isNotSameAs(roster.doors().get(1).device());
    }

    /* ---------- who may use the door API ---------- */

    @Test
    void everyEndpointNeedsSignIn() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/activity")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/open").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/close").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/resume").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/doors")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        for (String door : DOORS) {
            mvc.perform(get("/api/doors/" + door)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/doors/" + door + "/open").with(csrf())).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/doors/" + door + "/close").with(csrf())).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/doors/" + door + "/resume").with(csrf())).andExpect(status().isUnauthorized());
        }
        // Even from Shir's authorized phone: being on the tailnet is not a sign-in.
        doorApiRefuses(TestTailnet.from(TestTailnet.SHIR_PHONE));
    }

    @Test
    void aSignInThatIsntAPasskeyOnAnAuthorizedDeviceIsRefused() throws Exception {
        // Not a passkey session, whoever it claims to be and whatever role: AccessCheckFilter ends it, and the
        // request goes on signed out. (Before PR #8 a non-family user got 403 here; now no such session survives.)
        doorApiRefuses(request -> TestTailnet.from(TestTailnet.SHIR_PHONE).postProcessRequest(user("stranger").postProcessRequest(request)));
        doorApiRefuses(request -> TestTailnet.from(TestTailnet.SHIR_PHONE).postProcessRequest(user("shir").roles("FAMILY").postProcessRequest(request)));
        // Shir's own session, but not through tailscale serve: the device can't be verified.
        mvc.perform(post("/api/doors/toyota/open").session(session).with(csrf())).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void aSessionFromADeviceTheTailnetDoesntKnowIsEndedAndMovesNoDoor() throws Exception {
        mvc.perform(get("/api/doors").with(family)).andExpect(status().isOk());
        whois.forget(TestTailnet.SHIR_PHONE.address()); // tailscaled can't say which node this is

        mvc.perform(post("/api/doors/toyota/open").with(family).with(csrf())).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        whois.reset();
        doorApiRefuses(family); // ended for good, not just for that one request
    }

    @Test
    void aSessionCarriedToAnotherOfShirsDevicesIsEndedAndMovesNoDoor() throws Exception {
        // Shir's iPad is on the tailnet but was never enrolled: her phone's session doesn't work from it.
        mvc.perform(post("/api/doors/ford/open").session(session).with(TestTailnet.from(TestTailnet.SHIR_IPAD)).with(csrf()))
                .andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        // And signing in there with her passkey is refused: the iPad needs its own invite.
        assertThat(TestTailnet.signIn(context, new MockHttpSession(), "shir", "c2hpcnMtcGFzc2tleQ", TestTailnet.SHIR_IPAD))
                .isEqualTo("device not authorized");
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void revokingThePhoneEndsItsSessionOnTheNextDoorCommand() throws Exception {
        mvc.perform(get("/api/doors").with(family)).andExpect(status().isOk());
        mvc.perform(delete("/admin/devices/" + TestTailnet.SHIR_PHONE.nodeId()).header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(jsonPath("$.revoked").value(true));

        mvc.perform(post("/api/doors/toyota/open").with(family).with(csrf())).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        doorApiRefuses(family);
        // Signing in again with the passkey doesn't help: the phone needs a new invite.
        assertThat(TestTailnet.signIn(context, new MockHttpSession(), "shir", "c2hpcnMtcGFzc2tleQ", TestTailnet.SHIR_PHONE))
                .isEqualTo("device not authorized");
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void revokingThePasskeyEndsItsSessionOnTheNextDoorCommand() throws Exception {
        mvc.perform(get("/api/doors").with(family)).andExpect(status().isOk());
        mvc.perform(delete("/admin/credentials/c2hpcnMtcGFzc2tleQ").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(jsonPath("$.revoked").value(true));

        mvc.perform(post("/api/doors/ford/open").with(family).with(csrf())).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        assertThat(activity.recent(10)).isEmpty();
    }

    /* ---------- the doors ---------- */

    @Test
    void doorsShowsBothPracticeDoorsControllableAndNeitherLive() throws Exception {
        mvc.perform(get("/api/doors").with(family))
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
        mvc.perform(get("/api/doors/Toyota").with(family))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Toyota"))
                .andExpect(jsonPath("$.live").value(false))
                .andExpect(jsonPath("$.status.door").value("CLOSED"));
    }

    @Test
    void anUnknownDoorIsNotFoundAndNothingIsRecorded() throws Exception {
        mvc.perform(get("/api/doors/channel3").with(family)).andExpect(status().isNotFound());
        mvc.perform(post("/api/doors/channel3/open").with(family).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/doors/3/close").with(family).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/doors/channel3/resume").with(family).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/activity").param("door", "channel3").with(family)).andExpect(status().isNotFound());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void perDoorCommandsNeedTheCsrfTokenAndNothingHappensWithout() throws Exception {
        for (String door : DOORS) {
            mvc.perform(post("/api/doors/" + door + "/open").with(family)).andExpect(status().isForbidden());
            mvc.perform(post("/api/doors/" + door + "/close").with(family)).andExpect(status().isForbidden());
            mvc.perform(post("/api/doors/" + door + "/resume").with(family)).andExpect(status().isForbidden());
        }
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void thereIsNoToggleAndCommandsAreNeverAGet() throws Exception {
        mvc.perform(post("/api/doors/toyota/toggle").with(family).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/doors/toyota").with(family).with(csrf())).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/doors/toyota/open").with(family)).andExpect(status().isMethodNotAllowed());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void openingToyotaMovesOnlyToyotaAndIsRecordedUnderItsName() throws Exception {
        mvc.perform(post("/api/doors/toyota/open").with(family).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("MOVING"))
                .andExpect(jsonPath("$.status.door").value("OPENING"));

        mvc.perform(get("/api/doors").with(family))
                .andExpect(jsonPath("$.doors[0].door").value("CLOSED"))
                .andExpect(jsonPath("$.doors[1].door").value("OPENING"));
        mvc.perform(get("/api/activity").with(family))
                .andExpect(jsonPath("$[0].door").value("Toyota"))
                .andExpect(jsonPath("$[0].who").value("shir"))
                .andExpect(jsonPath("$[0].action").value("OPEN"))
                .andExpect(jsonPath("$[0].outcome").value("MOVING"));
        mvc.perform(get("/api/activity").param("door", "ford").with(family))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void eachDoorHasItsOwnMovementSoBothCanMoveAtOnce() throws Exception {
        mvc.perform(post("/api/doors/ford/open").with(family).with(csrf())).andExpect(jsonPath("$.outcome").value("MOVING"));
        mvc.perform(post("/api/doors/toyota/open").with(family).with(csrf())).andExpect(jsonPath("$.outcome").value("MOVING"));
        mvc.perform(post("/api/doors/ford/close").with(family).with(csrf())).andExpect(jsonPath("$.outcome").value("BUSY"));
        mvc.perform(post("/api/doors/toyota/close").with(family).with(csrf())).andExpect(jsonPath("$.outcome").value("BUSY"));

        mvc.perform(get("/api/activity").param("door", "toyota").with(family))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].door").value("Toyota"));
    }

    @Test
    void perDoorResumeWithNothingHeldChangesNothing() throws Exception {
        mvc.perform(post("/api/doors/toyota/resume").with(family).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.held").value(false));
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void theOlderEndpointsDriveFordOnly() throws Exception {
        mvc.perform(post("/api/open").with(family).with(csrf())).andExpect(jsonPath("$.outcome").value("MOVING"));

        mvc.perform(get("/api/doors").with(family))
                .andExpect(jsonPath("$.doors[0].door").value("OPENING"))
                .andExpect(jsonPath("$.doors[1].door").value("CLOSED"));
        mvc.perform(get("/api/activity").with(family)).andExpect(jsonPath("$[0].door").value("Ford"));
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
        mvc.perform(get("/api/doors").with(family)).andExpect(status().isOk()); // ties the session to the phone first
        java.util.concurrent.ExecutorService phones = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.List<java.util.concurrent.Future<String>> taps = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            taps.add(phones.submit(() -> com.jayway.jsonpath.JsonPath.read(mvc.perform(post("/api/open").with(family).with(csrf()))
                    .andReturn().getResponse().getContentAsString(), "$.outcome")));
        }
        java.util.List<String> outcomes = new java.util.ArrayList<>();
        for (var tap : taps) outcomes.add(tap.get());
        phones.shutdown();

        assertThat(outcomes).containsOnlyOnce("MOVING").containsOnly("MOVING", "BUSY");
        assertThat(activity.recent(10)).filteredOn(e -> e.outcome() == home.casita.garage.Outcome.MOVING).hasSize(1);
    }
}
