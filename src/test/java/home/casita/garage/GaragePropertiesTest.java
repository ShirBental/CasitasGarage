package home.casita.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class GaragePropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(PropertiesConfig.class);

    @Configuration
    @EnableConfigurationProperties(GarageProperties.class)
    static class PropertiesConfig {}

    @Test
    void travelTimeMustBePositive() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GarageProperties(Duration.ZERO, Duration.ofSeconds(2), true))
                .withMessageContaining("casita.garage.travel-time must be positive");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GarageProperties(Duration.ofSeconds(-1), Duration.ofSeconds(2), true))
                .withMessageContaining("casita.garage.travel-time must be positive");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GarageProperties(null, Duration.ofSeconds(2), true))
                .withMessageContaining("casita.garage.travel-time must be positive");
    }

    @Test
    void verifyGraceMustNotBeNegative() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GarageProperties(Duration.ofSeconds(10), Duration.ofMillis(-1), true))
                .withMessageContaining("casita.garage.verify-grace must be zero or positive");
        assertThatNoException()
                .isThrownBy(() -> new GarageProperties(Duration.ofSeconds(10), Duration.ZERO, true));
    }

    @Test
    void defaultsBind() {
        contextRunner.run(context -> {
            assertThat(context.getStartupFailure()).isNull();
            GarageProperties props = context.getBean(GarageProperties.class);
            assertThat(props.travelTime()).isEqualTo(Duration.ofSeconds(10));
            assertThat(props.verifyGrace()).isEqualTo(Duration.ofSeconds(2));
        });
    }

    @Test
    void zeroTravelTimeStopsStartupWithAClearMessage() {
        contextRunner.withPropertyValues("casita.garage.travel-time=0s").run(context ->
                assertThat(causeMessages(context.getStartupFailure()))
                        .contains("casita.garage.travel-time must be positive"));
    }

    @Test
    void negativeVerifyGraceStopsStartupWithAClearMessage() {
        contextRunner.withPropertyValues("casita.garage.verify-grace=-1s").run(context ->
                assertThat(causeMessages(context.getStartupFailure()))
                        .contains("casita.garage.verify-grace must be zero or positive"));
    }

    /** All messages in the cause chain, so the test doesn't depend on how Spring wraps it. */
    private static String causeMessages(Throwable failure) {
        assertThat(failure).as("startup should fail").isNotNull();
        StringBuilder sb = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            sb.append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void aDoorCanHaveItsOwnTravelTimeAndOthersKeepTheDefault() {
        contextRunner.withPropertyValues("casita.garage.travel-time=20s", "casita.garage.travel-times.Toyota=15s").run(context -> {
            GarageProperties properties = context.getBean(GarageProperties.class);
            assertThat(properties.travelTime("Toyota")).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.travelTime("toyota")).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.travelTime("Ford")).isEqualTo(Duration.ofSeconds(20));
        });
    }

    @Test
    void aDoorsOwnTravelTimeMustBePositive() {
        contextRunner.withPropertyValues("casita.garage.travel-times.Toyota=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void sensorLagsAreZeroOrMoreAndKeyedByDoor() {
        GarageProperties p = new GarageProperties(Duration.ofSeconds(10), Duration.ofSeconds(2), true,
                java.util.Map.of("Ford", Duration.ofSeconds(20)), java.util.Map.of("Ford", Duration.ofSeconds(5), "Toyota", Duration.ZERO));
        assertThat(p.sensorLag("Ford")).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.sensorLag("ford")).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.sensorLag("Toyota")).isEqualTo(Duration.ZERO);
        assertThat(p.sensorLag("Garage")).as("not given: zero").isEqualTo(Duration.ZERO);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new GarageProperties(Duration.ofSeconds(10), Duration.ofSeconds(2), true,
                        java.util.Map.of(), java.util.Map.of("Ford", Duration.ofSeconds(-1))))
                .withMessageContaining("casita.garage.sensor-lags.Ford");
    }

    @Test
    void aDoorIsMeasuredOnlyWithItsOwnTravelTimeAndLag() {
        GarageProperties p = new GarageProperties(Duration.ofSeconds(10), Duration.ofSeconds(2), true,
                java.util.Map.of("Ford", Duration.ofSeconds(20), "Toyota", Duration.ofSeconds(25)), java.util.Map.of("Ford", Duration.ZERO));
        assertThat(p.measured("Ford")).isTrue();
        assertThat(p.measured("Toyota")).as("travel time only").isFalse();
        assertThat(new GarageProperties(Duration.ofSeconds(10), Duration.ofSeconds(2), true).measured("Ford")).as("defaults").isFalse();
    }
}
