package home.casita.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.ClassPathResource;

/**
 * The home screen's polling rules (static/poller.js): visible only, idle slowdown, one request at a time,
 * backoff after failures. The scenarios are written in JavaScript (src/test/resources/js/poller.test.js) and run
 * here under GraalJS, so the build needs nothing but Maven and a JDK. Each scenario runs in a fresh context with
 * a fake clock: no real waiting.
 */
class PollerScriptTest {

    private static final Engine ENGINE = Engine.newBuilder("js").option("engine.WarnInterpreterOnly", "false").build();

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(path, e);
        }
    }

    private static Context context() {
        Context js = Context.newBuilder("js").engine(ENGINE).build();
        js.eval(Source.create("js", read("static/poller.js") + "\n" + read("js/poller.test.js")));
        return js;
    }

    @TestFactory
    Stream<DynamicTest> pollerScenarios() {
        List<String> names;
        try (Context js = context()) {
            names = Arrays.asList(js.eval("js", "Object.keys(TESTS)").as(String[].class));
        }
        return names.stream().map(name -> DynamicTest.dynamicTest(name, () -> run(name)));
    }

    private static void run(String name) {
        try (Context js = context()) {
            js.getBindings("js").getMember("runTest").execute(name);
            // Promise jobs run when the call above returns; the fake clock never needs real time.
            String result = js.eval("js", "RESULTS[" + jsString(name) + "]").asString();
            assertThat(result).as(name).isEqualTo("passed");
        }
    }

    private static String jsString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void theHomeScreenLoadsThePollerBeforeTheApp() {
        String index = read("static/index.html");
        assertThat(index.indexOf("/poller.js")).isPositive().isLessThan(index.indexOf("/app.js"));
        String app = read("static/app.js");
        assertThat(app).contains("CasitaPoller.create(").contains("\"visibilitychange\"").contains("\"pagehide\"")
                .doesNotContain("setTimeout(refresh");
    }
}
