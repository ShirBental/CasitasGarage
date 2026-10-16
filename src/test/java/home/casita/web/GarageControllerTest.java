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
import home.casita.garage.DoorDevice;
import home.casita.garage.SimulatedDoor;

/** The API over the simulated door (the default): nothing here reaches Meross. */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GarageControllerTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ActivityLog activity;
    @Autowired
    private DoorDevice door;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
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
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void aCommandWithoutTheCsrfTokenIsRefusedAndNothingHappens() throws Exception {
        mvc.perform(post("/api/open").with(user("shir"))).andExpect(status().isForbidden());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void statusReportsTheDoorAndWhatCanBeDone() throws Exception {
        mvc.perform(get("/api/status").with(user("shir")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.door").value("CLOSED"))
                .andExpect(jsonPath("$.canOpen").value(true))
                .andExpect(jsonPath("$.canClose").value(false));
    }

    @Test
    void openGoesThroughGarageServiceAndIsRecordedUnderTheSignedInUser() throws Exception {
        mvc.perform(post("/api/open").with(user("shir")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("MOVING"))
                .andExpect(jsonPath("$.status.door").value("OPENING"));

        mvc.perform(get("/api/activity").with(user("shir")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].who").value("shir"))
                .andExpect(jsonPath("$[0].action").value("OPEN"))
                .andExpect(jsonPath("$[0].outcome").value("MOVING"));
    }

    @Test
    void closingAClosedDoorSendsNothing() throws Exception {
        mvc.perform(post("/api/close").with(user("shir")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY"));
    }

    @Test
    void aSecondCommandWhileTheFirstIsUncheckedIsBusy() throws Exception {
        mvc.perform(post("/api/open").with(user("shir")).with(csrf())).andExpect(jsonPath("$.outcome").value("MOVING"));

        mvc.perform(post("/api/close").with(user("shir")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("BUSY"));
    }
}
