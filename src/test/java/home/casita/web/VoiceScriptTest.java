package home.casita.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * What the home screen shows for a door (static/voice.js), under GraalJS: "Opening…" / "Closing…" never outlast the
 * door's travel time, whatever the last reply said, and waiting for the sensor has its own words.
 */
class VoiceScriptTest {

    private static final Engine ENGINE = Engine.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();
    private static final long SINCE_MS = 1_763_294_400_000L; // 2025-11-16T12:00:00Z
    private Context js;

    @BeforeEach
    void load() throws IOException {
        js = Context.newBuilder("js").engine(ENGINE).build();
        js.eval(Source.create("js", new ClassPathResource("static/voice.js").getContentAsString(StandardCharsets.UTF_8)));
    }

    @AfterEach
    void close() {
        js.close();
    }

    private String shown(String door, long secondsLater) {
        return js.eval("js", "VOICE.shown({door: '" + door + "', since: new Date(" + SINCE_MS + ").toISOString(), travelTimeSeconds: 6}, "
                + (SINCE_MS + secondsLater * 1000) + ")").asString();
    }

    @Test
    void openingAndClosingAreShownOnlyForTheTravelTime() {
        assertThat(shown("OPENING", 3)).isEqualTo("OPENING");
        assertThat(shown("OPENING", 7)).isEqualTo("OPENING"); // a second's grace for the poll
        assertThat(shown("OPENING", 8)).isEqualTo("WAITING_FOR_SENSOR");
        assertThat(shown("CLOSING", 41)).isEqualTo("WAITING_FOR_SENSOR");
        assertThat(shown("CLOSED", 600)).isEqualTo("CLOSED");
        assertThat(shown("NOT_CLOSED", 600)).isEqualTo("NOT_CLOSED");
    }

    @Test
    void theHomeScreenScriptParses() throws IOException {
        // app.js needs a page to run; parsing it catches a broken edit without one.
        js.parse(Source.create("js", new ClassPathResource("static/app.js").getContentAsString(StandardCharsets.UTF_8)));
    }

    @Test
    void waitingForTheSensorHasItsOwnWords() {
        assertThat(js.eval("js", "VOICE.state({door: 'WAITING_FOR_SENSOR'}, false)[0]").asString()).isEqualTo("Waiting for sensor");
        assertThat(js.eval("js", "VOICE.detail({door: 'WAITING_FOR_SENSOR', name: 'Toyota'}, false)").asString())
                .contains("sensor hasn't caught up");
        assertThat(js.eval("js", "VOICE.state({door: 'OPENING'}, false)[0]").asString()).isEqualTo("Opening…");
    }
}
