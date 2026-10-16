package home.casita.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CasitaSecurityPropertiesTest {

    private static CasitaSecurityProperties props(String rpId, Set<String> origins, List<String> family) {
        return new CasitaSecurityProperties(rpId, origins, family, Path.of("data"));
    }

    @Test
    void theTailscaleSetupIsAccepted() {
        CasitaSecurityProperties p = props("casita.example-tailnet.ts.net", Set.of("https://casita.example-tailnet.ts.net"), List.of("shir", "hubby"));

        assertThat(p.family()).containsExactly("shir", "hubby");
    }

    @Test
    void plainHttpIsOnlyForLocalhost() {
        assertThat(props("localhost", Set.of("http://localhost:8080"), List.of()).allowedOrigins()).hasSize(1);
        assertThatThrownBy(() -> props("casita.example-tailnet.ts.net", Set.of("http://casita.example-tailnet.ts.net"), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("https");
    }

    @Test
    void anOriginMustBeOnTheRelyingPartyId() {
        assertThatThrownBy(() -> props("casita.example-tailnet.ts.net", Set.of("https://evil.example"), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rp-id");
    }

    @Test
    void familyNamesAreShortAndSafeForFileNames() {
        for (String bad : List.of("Shir", "../etc", "a b", "", "x".repeat(33))) {
            assertThatThrownBy(() -> props("localhost", Set.of("http://localhost:8080"), List.of(bad)))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
