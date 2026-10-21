package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The door and channel settings: named doors, no silent channel 0, and the safety default. */
class MerossPropertiesTest {

    private static MerossProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bind("casita.meross", MerossProperties.class).get();
    }

    @Test
    void bindsNamedDoorsAndLeavesTheChannelUnsetByDefault() {
        MerossProperties props = bind(Map.of("casita.meross.doors.1", "Ford", "casita.meross.doors.2", "Toyota"));

        assertThat(props.doors()).isEqualTo(Map.of(1, "Ford", 2, "Toyota"));
        assertThat(props.channel()).isNull();
        assertThat(props.commandsEnabled()).isFalse();
    }

    @Test
    void anExplicitChannelMustBeOneOfTheNamedDoors() {
        assertThat(bind(Map.of("casita.meross.doors.1", "Ford", "casita.meross.doors.2", "Toyota", "casita.meross.channel", "2")).channel())
                .isEqualTo(2);

        assertThatThrownBy(() -> new MerossProperties("https://iotx-us.meross.com", null, null, null, 3, Duration.ofSeconds(8), false,
                Map.of(1, "Ford", 2, "Toyota")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("casita.meross.channel 3").hasMessageContaining("{1=Ford, 2=Toyota}");
    }

    @Test
    void withoutNamedDoorsAnyChannelIsAccepted() {
        assertThat(new MerossProperties("https://iotx-us.meross.com", null, null, null, 0, Duration.ofSeconds(8), false, null).doors()).isEmpty();
    }

    @Test
    void credentialsKeepTheDoorsAndChannel() {
        MerossProperties props = new MerossProperties("https://iotx-us.meross.com", null, null, null, 1, Duration.ofSeconds(8), false,
                Map.of(1, "Ford", 2, "Toyota"));

        MerossProperties withCreds = props.withCredentials(new MerossLocalCredentials("a@example.com", "p", null));

        assertThat(withCreds.channel()).isEqualTo(1);
        assertThat(withCreds.doors()).isEqualTo(Map.of(1, "Ford", 2, "Toyota"));
        assertThat(withCreds.commandsEnabled()).isFalse();
    }

    @Test
    void toStringShowsDoorsButNeitherCredentialsNorTheFullUuid() {
        String shown = new MerossProperties("https://iotx-us.meross.com", "a@example.com", "hunter2-secret",
                "1912089283245425180234298f1a2b3c", 1, Duration.ofSeconds(8), false, Map.of(1, "Ford", 2, "Toyota")).toString();

        assertThat(shown).contains("channel=1", "{1=Ford, 2=Toyota}", "…2b3c")
                .doesNotContain("a@example.com").doesNotContain("hunter2").doesNotContain("1912089283245425180234298f1a");
    }

    @Test
    void theRealDoorRefusesToStartWithSeveralDoorsAndNoChannelChosen() {
        new ApplicationContextRunner()
                .withUserConfiguration(MerossConfig.class, BindProperties.class)
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("casita.door=meross", "casita.meross.email=a@example.com", "casita.meross.password=p",
                        "casita.meross.doors.1=Ford", "casita.meross.doors.2=Toyota")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("Several doors are configured")
                            .hasMessageContaining("set casita.meross.channel");
                });
    }

    @Test
    void theRealDoorStartsOnceAChannelIsChosen() {
        new ApplicationContextRunner()
                .withUserConfiguration(MerossConfig.class, BindProperties.class)
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("casita.door=meross", "casita.meross.email=a@example.com", "casita.meross.password=p",
                        "casita.meross.doors.1=Ford", "casita.meross.doors.2=Toyota", "casita.meross.channel=1")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(home.casita.garage.DoorDevice.class));
    }

    @EnableConfigurationProperties(MerossProperties.class)
    static class BindProperties {
    }
}
