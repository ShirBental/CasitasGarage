package home.casita.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CasitaSecurityPropertiesTest {

    private static CasitaSecurityProperties props(String rpId, Set<String> origins, List<String> family) {
        return new CasitaSecurityProperties(rpId, origins, family, Path.of("data"), CasitaSecurityProperties.DeviceIdentityMode.BROWSER, null);
    }

    @Test
    void thePublicSetupIsAccepted() {
        CasitaSecurityProperties p = props("watsonbental.duckdns.org", Set.of("https://watsonbental.duckdns.org"), List.of("shir", "hubby"));

        assertThat(p.family()).containsExactly("shir", "hubby");
        assertThat(p.deviceKey()).isEqualTo(Path.of("data", "device.key"));
    }

    @Test
    void theDeviceKeyFileCanBeSetApart() {
        CasitaSecurityProperties p = new CasitaSecurityProperties("localhost", Set.of("http://localhost:8080"), List.of(), Path.of("data"),
                CasitaSecurityProperties.DeviceIdentityMode.BROWSER, Path.of("/var/lib/casita/device.key"));
        assertThat(p.deviceKey()).isEqualTo(Path.of("/var/lib/casita/device.key"));
    }

    @Test
    void plainHttpIsOnlyForLocalhost() {
        assertThat(props("localhost", Set.of("http://localhost:8080"), List.of()).allowedOrigins()).hasSize(1);
        assertThatThrownBy(() -> props("casita.example.org", Set.of("http://casita.example.org"), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("https");
    }

    @Test
    void anOriginMustBeOnTheRelyingPartyId() {
        assertThatThrownBy(() -> props("casita.example.org", Set.of("https://evil.example"), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rp-id");
    }

    @Test
    void familyNamesAreShortAndSafeForFileNames() {
        for (String bad : List.of("Shir", "../etc", "a b", "", "x".repeat(33))) {
            assertThatThrownBy(() -> props("localhost", Set.of("http://localhost:8080"), List.of(bad)))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void developmentDeviceIdentityIsOnlyForLocalhost() {
        assertThat(new CasitaSecurityProperties("localhost", Set.of("http://localhost:8080"), List.of(), Path.of("data"),
                CasitaSecurityProperties.DeviceIdentityMode.DEVELOPMENT, null).deviceIdentity())
                .isEqualTo(CasitaSecurityProperties.DeviceIdentityMode.DEVELOPMENT);
        assertThatThrownBy(() -> new CasitaSecurityProperties("casita.example.org", Set.of("https://casita.example.org"),
                List.of(), Path.of("data"), CasitaSecurityProperties.DeviceIdentityMode.DEVELOPMENT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
