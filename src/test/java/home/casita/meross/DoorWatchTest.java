package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The read-only door watch's description of the opener's own door timers. */
class DoorWatchTest {

    @Test
    void showsEachChannelsNumericSettingsOnly() {
        Map<String, Object> answer = Map.of("config", List.of(
                Map.of("channel", 1, "doorOpenDuration", 15000, "doorCloseDuration", 15000, "signalDuration", 1000, "name", "secret-ish"),
                Map.of("channel", 2, "doorOpenDuration", 30000, "doorCloseDuration", 30000)));

        String text = DoorWatch.describeConfig(answer);

        assertThat(text).contains("channel 1: doorCloseDuration=15000 doorOpenDuration=15000 signalDuration=1000")
                .contains("channel 2: doorCloseDuration=30000 doorOpenDuration=30000").doesNotContain("secret-ish");
    }

    @Test
    void saysSoWhenNothingIsReported() {
        assertThat(DoorWatch.describeConfig(Map.of())).endsWith("none reported");
        assertThat(DoorWatch.describeConfig(null)).endsWith("none reported");
    }
}
