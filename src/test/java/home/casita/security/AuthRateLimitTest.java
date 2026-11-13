package home.casita.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Through the real filter chain: invite redemption from one address is cut off at the limit, before anything else runs. */
@SpringBootTest(properties = {
        "casita.security.data-dir=target/test-data/rate-limit",
        "casita.security.family=shir",
        "casita.security.rate-limit.per-client=3",
        "casita.security.rate-limit.total=100"})
class AuthRateLimitTest {

    @Autowired
    WebApplicationContext context;

    @Test
    void guessingInviteTokensFromOneAddressIsCutOff() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/login/ott").param("token", "guess-" + i).with(csrf()).secure(true)
                    .with(r -> { r.setRemoteAddr("203.0.113.7"); return r; })).andExpect(status().is3xxRedirection());
        }
        mvc.perform(post("/login/ott").param("token", "guess-4").with(csrf()).secure(true)
                .with(r -> { r.setRemoteAddr("203.0.113.7"); return r; })).andExpect(status().isTooManyRequests());
        // Even without a CSRF token: counted and refused before CSRF is checked.
        mvc.perform(post("/login/ott").param("token", "guess-5").with(r -> { r.setRemoteAddr("203.0.113.7"); return r; }))
                .andExpect(status().isTooManyRequests());
        mvc.perform(post("/login/ott").param("token", "guess-6").with(csrf()).secure(true)
                .with(r -> { r.setRemoteAddr("203.0.113.8"); return r; })).andExpect(status().is3xxRedirection());
    }
}
