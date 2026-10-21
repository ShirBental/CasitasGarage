package home.casita.security;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The passkey script reads the CSRF token from the XSRF-TOKEN cookie, so the very first page must set it.
 * A context of its own: spring-security-test's csrf() swaps in a cookie-less token repository for the
 * rest of a shared context, which would hide the real behaviour.
 */
@SpringBootTest(properties = "casita.security.data-dir=target/test-data/csrf-cookie")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class CsrfCookieTest {

    @Autowired
    private WebApplicationContext context;

    @Test
    void theSignInPageSetsAReadableCsrfCookieBeforeAnyPost() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        for (String page : new String[] {"/login", "/login.html"}) {
            mvc.perform(get(page)).andExpect(cookie().exists("XSRF-TOKEN")).andExpect(cookie().httpOnly("XSRF-TOKEN", false));
        }
    }
}
