package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import home.casita.garage.ActivityLog;
import home.casita.garage.Outcome;

/**
 * The API with the real doors, Ford switched on live and Toyota not: the app is told, per door, that only Ford
 * is live, and Toyota's commands are refused before anything is read or sent. Meross points at a closed local
 * port, so reads fail fast (UNKNOWN) and nothing reaches Meross; no Ford command is sent.
 */
@SpringBootTest(properties = {
        "casita.door=meross",
        "casita.meross.api-base-url=http://127.0.0.1:1",
        "casita.meross.request-timeout=1s",
        "casita.meross.email=placeholder@example.com",
        "casita.meross.password=placeholder",
        "casita.meross.command-mode=live",
        "casita.meross.command-channels=1",
        "casita.security.data-dir=target/test-data/meross-api"})
class MerossDoorsApiTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ActivityLog activity;

    @Test
    void onlyTheSwitchedOnDoorIsReportedLiveAndToyotaIsRefused() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var family = user("shir").roles("FAMILY");

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
