package home.casita.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

class LocalAdminTest {

    @TempDir
    Path dir;

    @Test
    void onlyADirectLocalRequestWithTheSecretIsAllowed() throws Exception {
        LocalAdmin admin = new LocalAdmin(dir);
        String secret = Files.readString(dir.resolve("admin.secret")).trim();
        assertThat(secret).hasSize(64);

        assertThat(admin.allowed(request("127.0.0.1", secret))).isTrue();
        assertThat(admin.allowed(request("0:0:0:0:0:0:0:1", secret))).isTrue();
        assertThat(admin.allowed(request("127.0.0.1", null))).isFalse();
        assertThat(admin.allowed(request("127.0.0.1", secret.substring(1)))).isFalse();
        assertThat(admin.allowed(request("203.0.113.10", secret))).isFalse(); // a client address Tomcat took from the proxy
        for (String header : new String[] {"X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Port", "X-Real-IP", "Forwarded", "Via"}) {
            MockHttpServletRequest proxied = request("127.0.0.1", secret);
            proxied.addHeader(header, "x");
            assertThat(admin.allowed(proxied)).as(header).isFalse();
        }
    }

    @Test
    void theSecretIsKeptAcrossRestarts() throws Exception {
        new LocalAdmin(dir);
        String secret = Files.readString(dir.resolve("admin.secret")).trim();

        assertThat(new LocalAdmin(dir).allowed(request("127.0.0.1", secret))).isTrue();
    }

    private static MockHttpServletRequest request(String remote, String secret) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        if (secret != null) request.addHeader(LocalAdmin.HEADER, secret);
        return request;
    }
}
