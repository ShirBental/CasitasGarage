package home.casita.meross;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Expected values computed independently (Python hashlib). */
class MerossCryptoTest {

    @Test
    void md5() {
        assertThat(MerossCrypto.md5Hex("abc")).isEqualTo("900150983cd24fb0d6963f7d28e17f72");
    }

    @Test
    void httpRequestSignature() {
        assertThat(MerossCrypto.httpSign(1700000000000L, "ABCDEFGHIJKLMNOP", "e30="))
                .isEqualTo("25f64922be265cadcbb1eff4a691f017");
    }

    @Test
    void mqttMessageSignature() {
        assertThat(MerossCrypto.messageSign("0123456789abcdef0123456789abcdef", "mykey", 1700000000L))
                .isEqualTo("151740082fd24a89396fcb140ee3f700");
    }

    @Test
    void mqttPassword() {
        assertThat(MerossCrypto.mqttPassword("123456", "secretkey")).isEqualTo("8376b033947edafcbbc4a0add6d6a4f7");
    }

    @Test
    void idsHaveTheRightShape() {
        assertThat(MerossCrypto.newAppId()).matches("[0-9a-f]{32}");
        assertThat(MerossCrypto.newMessageId()).matches("[0-9a-f]{32}");
        assertThat(MerossCrypto.newNonce()).matches("[A-Z0-9]{16}");
        assertThat(MerossCrypto.newAppId()).isNotEqualTo(MerossCrypto.newAppId());
    }
}
