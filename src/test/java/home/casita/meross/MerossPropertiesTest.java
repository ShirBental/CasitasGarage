package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

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
        assertThat(props.commandMode()).isEqualTo(CommandMode.OFF);
        assertThat(props.commandChannels()).isEmpty();
    }

    @Test
    void anExplicitChannelMustBeOneOfTheNamedDoors() {
        assertThat(bind(Map.of("casita.meross.doors.1", "Ford", "casita.meross.doors.2", "Toyota", "casita.meross.channel", "2")).channel())
                .isEqualTo(2);

        assertThatThrownBy(() -> new MerossProperties("https://iotx-us.meross.com", null, null, null, 3, Duration.ofSeconds(8), CommandMode.OFF,
                Map.of(1, "Ford", 2, "Toyota"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("casita.meross.channel 3").hasMessageContaining("{1=Ford, 2=Toyota}");
    }

    @Test
    void withoutNamedDoorsAnyChannelIsAccepted() {
        assertThat(new MerossProperties("https://iotx-us.meross.com", null, null, null, 0, Duration.ofSeconds(8), CommandMode.OFF, null, null).doors()).isEmpty();
    }

    @Test
    void credentialsKeepTheDoorsAndChannel() {
        MerossProperties props = new MerossProperties("https://iotx-us.meross.com", null, null, null, 1, Duration.ofSeconds(8), CommandMode.OFF,
                Map.of(1, "Ford", 2, "Toyota"), Set.of());

        MerossProperties withCreds = props.withCredentials(new MerossLocalCredentials("a@example.com", "p", null));

        assertThat(withCreds.channel()).isEqualTo(1);
        assertThat(withCreds.doors()).isEqualTo(Map.of(1, "Ford", 2, "Toyota"));
        assertThat(withCreds.commandMode()).isEqualTo(CommandMode.OFF);
    }

    @Test
    void toStringShowsDoorsButNeitherCredentialsNorTheFullUuid() {
        String shown = new MerossProperties("https://iotx-us.meross.com", "a@example.com", "hunter2-secret",
                "1912089283245425180234298f1a2b3c", 1, Duration.ofSeconds(8), CommandMode.OFF, Map.of(1, "Ford", 2, "Toyota"), Set.of()).toString();

        assertThat(shown).contains("channel=1", "{1=Ford, 2=Toyota}", "…2b3c")
                .doesNotContain("a@example.com").doesNotContain("hunter2").doesNotContain("1912089283245425180234298f1a");
    }

    @Test
    void theCommittedApplicationYmlKeepsCommandsOffAndOnlyFordAllowed() throws Exception {
        // YAML reads a bare `off` as false; this proves the committed file binds to OFF, not a binding error.
        var sources = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application.yml", new org.springframework.core.io.ClassPathResource("application.yml"));
        MerossProperties props = new Binder(org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources))
                .bind("casita.meross", MerossProperties.class).get();

        assertThat(props.commandMode()).isEqualTo(CommandMode.OFF);
        assertThat(props.commandChannels()).containsExactly(1);
        assertThat(props.doors()).isEqualTo(Map.of(1, "Ford", 2, "Toyota"));
        assertThat(props.channel()).isNull();
    }

    @Test
    void bindsEachCommandModeAndTheAllowedChannels() {
        for (Map.Entry<String, CommandMode> mode : Map.of("off", CommandMode.OFF, "dry-run", CommandMode.DRY_RUN, "live", CommandMode.LIVE).entrySet()) {
            MerossProperties props = bind(Map.of("casita.meross.command-mode", mode.getKey(), "casita.meross.channel", "1",
                    "casita.meross.command-channels[0]", "1"));
            assertThat(props.commandMode()).isEqualTo(mode.getValue());
            assertThat(props.commandChannels()).containsExactly(1);
        }
    }

    @Test
    void withDoorsNamedEachDoorsOwnSwitchDecidesAndChannelIsNotNeeded() {
        Map<Integer, String> doors = Map.of(1, "Ford", 2, "Toyota");
        MerossProperties fordOnly = new MerossProperties("https://iotx-us.meross.com", null, null, null, null, Duration.ofSeconds(8),
                CommandMode.LIVE, doors, Set.of(1));

        assertThat(fordOnly.commandModeFor(1)).isEqualTo(CommandMode.LIVE);
        assertThat(fordOnly.commandModeFor(2)).isEqualTo(CommandMode.OFF);
        assertThat(fordOnly.commandModeFor(3)).isEqualTo(CommandMode.OFF);
        assertThat(fordOnly.commandModeFor(null)).isEqualTo(CommandMode.OFF);
        // An old CASITA_MEROSS_CHANNEL still starts, and changes nothing about which door is live.
        assertThat(new MerossProperties("https://iotx-us.meross.com", null, null, null, 2, Duration.ofSeconds(8), CommandMode.LIVE,
                doors, Set.of(1)).commandModeFor(2)).isEqualTo(CommandMode.OFF);
    }

    @Test
    void toyotaIsOffUnlessItsChannelIsSwitchedOnWhateverTheMode() {
        Map<Integer, String> doors = Map.of(1, "Ford", 2, "Toyota");
        for (CommandMode mode : CommandMode.values()) {
            assertThat(new MerossProperties("https://iotx-us.meross.com", null, null, null, null, Duration.ofSeconds(8), mode, doors,
                    Set.of(1)).commandModeFor(2)).isEqualTo(CommandMode.OFF);
            assertThat(new MerossProperties("https://iotx-us.meross.com", null, null, null, null, Duration.ofSeconds(8), mode, doors,
                    Set.of(1, 2)).commandModeFor(2)).isEqualTo(mode);
        }
    }

    @Test
    void withNoDoorsNamedLiveNeedsTheChannelSetAndAllowed() {
        assertThat(new MerossProperties("https://iotx-us.meross.com", null, null, null, 0, Duration.ofSeconds(8), CommandMode.LIVE, null,
                Set.of(0)).commandMode()).isEqualTo(CommandMode.LIVE);

        assertThatThrownBy(() -> new MerossProperties("https://iotx-us.meross.com", null, null, null, 0, Duration.ofSeconds(8), CommandMode.LIVE,
                null, Set.of())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("command-mode live");
        assertThatThrownBy(() -> new MerossProperties("https://iotx-us.meross.com", null, null, null, null, Duration.ofSeconds(8), CommandMode.LIVE,
                null, Set.of(0))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("command-mode live");
    }

    @Test
    void allowedCommandChannelsMustBeNamedDoors() {
        assertThatThrownBy(() -> new MerossProperties("https://iotx-us.meross.com", null, null, null, 1, Duration.ofSeconds(8), CommandMode.OFF,
                Map.of(1, "Ford", 2, "Toyota"), Set.of(1, 3)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("command-channels [1, 3]");
    }

    @Test
    void theRealDoorsStartWithoutAChannelChosenEachDoorOnItsOwn() {
        new ApplicationContextRunner()
                .withUserConfiguration(MerossConfig.class, BindProperties.class)
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("casita.door=meross", "casita.meross.email=a@example.com", "casita.meross.password=p",
                        "casita.meross.doors.1=Ford", "casita.meross.doors.2=Toyota")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(home.casita.garage.DoorRoster.class);
                    assertThat(context.getBean(home.casita.garage.DoorRoster.class).doors()).hasSize(2);
                });
    }

    @EnableConfigurationProperties(MerossProperties.class)
    static class BindProperties {
    }
}
