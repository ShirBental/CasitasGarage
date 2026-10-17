package home.casita.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** The illustration slot: off until the approved artwork exists, and only ever a file Casita serves itself. */
class BrandPropertiesTest {

    @Test
    void noIllustrationUntilTheApprovedArtworkIsConfigured() {
        assertThat(new BrandProperties("", "Watson", "", "").illustration()).isEmpty();
    }

    @Test
    void theIllustrationIsAFileInTheBrandFolder() {
        assertThat(new BrandProperties("", "Watson", "", "/brand/watson-home.png").illustration()).isEqualTo("/brand/watson-home.png");
        for (String bad : new String[] {"https://example.com/dog.png", "//example.com/dog.png", "/brand/../login.js",
                "watson.png", "/watson.svg", "/brand/watson.gif", "javascript:alert(1)"}) {
            assertThatThrownBy(() -> new BrandProperties("", "Watson", "", bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theHomeScreenShowsNoPlaceholderWhileTheSlotIsEmpty() throws Exception {
        String html = new String(new ClassPathResource("static/index.html").getContentAsByteArray());
        // The slot starts hidden and has no picture of its own; app.js fills it only from casita.brand.illustration.
        assertThat(html).containsPattern("<figure class=\"companion\" id=\"companion\" hidden>")
                .doesNotContain("watson.svg");
    }

    @Test
    void offlineKeepsTheJokeExactlyAndSaysNothingStale() throws Exception {
        String voice = new String(new ClassPathResource("static/voice.js").getContentAsByteArray());
        assertThat(voice).contains("\"ET phone home\"").contains("Can't reach home. Trying again…")
                .contains("Waiting for connection");
    }
}
