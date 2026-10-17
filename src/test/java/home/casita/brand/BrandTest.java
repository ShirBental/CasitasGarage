package home.casita.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The family's words come from configuration, reach only signed-in family, and stay out of the pages' code. */
@SpringBootTest(properties = {
        "casita.security.data-dir=target/test-data/brand",
        "casita.brand.household=test household",
        "casita.brand.companion=Rex",
        "casita.brand.about-line=A test line."})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class BrandTest {

    @Autowired
    private WebApplicationContext context;

    @Test
    void theFamilysWordsAreForTheFamilyOnly() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/api/brand")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/brand").with(user("shir").roles("FAMILY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.household").value("test household"))
                .andExpect(jsonPath("$.companion").value("Rex"))
                .andExpect(jsonPath("$.aboutLine").value("A test line."));
    }

    @Test
    void theManifestIsPublicAndCarriesOnlyTheAppsName() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String manifest = mvc.perform(get("/manifest.webmanifest")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(manifest).contains("\"name\": \"Casita\"").doesNotContainIgnoringCase("wild");
    }

    @Test
    void nothingInThePagesHardcodesTheFamilysWords() throws Exception {
        for (String file : new String[] {"app.js", "voice.js", "index.html", "login.html", "passkey.html", "login.js"}) {
            String text = new String(new ClassPathResource("static/" + file).getContentAsByteArray());
            assertThat(text).as(file).doesNotContainIgnoringCase("wild").doesNotContain("Watson's");
        }
    }

    @Test
    void casitaNeverSaysTheDoorIsOpenOnlyThatItIsntClosed() throws Exception {
        String voice = new String(new ClassPathResource("static/voice.js").getContentAsByteArray());
        assertThat(voice).doesNotContain("\"Open\"").doesNotContain("is open").doesNotContain("fully open\"")
                .doesNotContain("already open");
    }
}
