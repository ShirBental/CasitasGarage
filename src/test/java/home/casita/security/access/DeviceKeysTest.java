package home.casita.security.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.Cookie;

/** Browser keys: minted only with the server secret, checked in constant time, nothing else accepted. */
class DeviceKeysTest {

    @TempDir
    Path dir;

    @Test
    void anIssuedKeyVerifiesAsItsOwnDeviceAndTheSecretIsOwnerOnly() throws Exception {
        DeviceKeys keys = new DeviceKeys(dir.resolve("device.key"));
        DeviceKeys.Issued issued = keys.issue();

        assertThat(issued.deviceId()).matches("b-[0-9a-f]{32}");
        assertThat(keys.verify(issued.cookieValue())).contains(issued.deviceId());
        assertThat(keys.issue().deviceId()).isNotEqualTo(issued.deviceId());
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("device.key")))).isEqualTo("rw-------");
        assertThat(issued.toString()).doesNotContain(issued.cookieValue().substring(issued.cookieValue().indexOf('.')));
    }

    @Test
    void theSecretIsKeptAcrossRestartsAndAnotherSecretMeansNoDevice() {
        DeviceKeys.Issued issued = new DeviceKeys(dir.resolve("device.key")).issue();

        assertThat(new DeviceKeys(dir.resolve("device.key")).verify(issued.cookieValue())).contains(issued.deviceId());
        assertThat(new DeviceKeys(dir.resolve("other.key")).verify(issued.cookieValue())).isEmpty();
    }

    @Test
    void anythingButAnIntactKeyIsNoDevice() {
        DeviceKeys keys = new DeviceKeys(dir.resolve("device.key"));
        String good = keys.issue().cookieValue();
        String id = good.substring(0, good.indexOf('.'));
        String other = keys.issue().cookieValue();
        for (String bad : new String[] {null, "", id, id + ".", good + "x", good.substring(0, good.length() - 1),
                id + other.substring(other.indexOf('.')), "b-" + "0".repeat(32) + good.substring(good.indexOf('.')),
                "nShirPhoneCNTRL", "../" + good, good.toUpperCase(), "x".repeat(500)}) {
            assertThat(keys.verify(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }

    @Test
    void aShortOrGarbledSecretStopsStartup() throws Exception {
        Files.writeString(dir.resolve("short.key"), "abcd\n");
        assertThatThrownBy(() -> new DeviceKeys(dir.resolve("short.key"))).isInstanceOf(IllegalStateException.class);
        Files.writeString(dir.resolve("garbled.key"), "not hex at all\n");
        assertThatThrownBy(() -> new DeviceKeys(dir.resolve("garbled.key"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theCookieIsHostOnlyHttpsOnlyAndHiddenFromScripts() {
        String cookie = DeviceKeys.cookie("value").toString();
        assertThat(cookie).startsWith("__Host-casita-device=value").contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax", "Max-Age=34560000")
                .doesNotContain("Domain");
    }

    @Test
    void theResolverReadsTheKeyOverHttpsOnlyAndMintsOnlyWhenAskedTo() {
        DeviceKeys keys = new DeviceKeys(dir.resolve("device.key"));
        BrowserDeviceIdentity identity = new BrowserDeviceIdentity(keys);
        DeviceKeys.Issued issued = keys.issue();

        MockHttpServletRequest https = new MockHttpServletRequest();
        https.setSecure(true);
        https.setCookies(new Cookie("JSESSIONID", "x"), new Cookie(DeviceKeys.COOKIE, "forged"), new Cookie(DeviceKeys.COOKIE, issued.cookieValue()));
        assertThat(identity.resolve(https)).get().extracting(DeviceIdentity::nodeId).isEqualTo(issued.deviceId());

        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setCookies(new Cookie(DeviceKeys.COOKIE, issued.cookieValue()));
        assertThat(identity.resolve(http)).isEmpty();
        MockHttpServletResponse plainResponse = new MockHttpServletResponse();
        assertThat(identity.resolveOrIssue(http, plainResponse)).isEmpty();
        assertThat(plainResponse.getHeaders("Set-Cookie")).isEmpty();

        // A browser with no key, over HTTPS: resolve never mints; resolveOrIssue (invite redemption) does.
        MockHttpServletRequest fresh = new MockHttpServletRequest();
        fresh.setSecure(true);
        assertThat(identity.resolve(fresh)).isEmpty();
        MockHttpServletResponse response = new MockHttpServletResponse();
        DeviceIdentity minted = identity.resolveOrIssue(fresh, response).orElseThrow();
        assertThat(minted.nodeId()).matches("b-[0-9a-f]{32}");
        assertThat(response.getHeader("Set-Cookie")).startsWith(DeviceKeys.COOKIE + "=" + minted.nodeId() + ".");

        // A browser that already has a valid key keeps it.
        MockHttpServletResponse unchanged = new MockHttpServletResponse();
        assertThat(identity.resolveOrIssue(https, unchanged)).get().extracting(DeviceIdentity::nodeId).isEqualTo(issued.deviceId());
        assertThat(unchanged.getHeaders("Set-Cookie")).isEmpty();
    }
}
