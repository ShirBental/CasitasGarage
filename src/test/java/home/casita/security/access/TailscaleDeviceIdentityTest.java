package home.casita.security.access;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** The device check behind every access decision: anything missing or inconsistent is "unverified". */
class TailscaleDeviceIdentityTest {

    private final TestTailnet.FakeWhois whois = new TestTailnet.FakeWhois();
    private final TailscaleDeviceIdentity identity = new TailscaleDeviceIdentity(whois);

    private static MockHttpServletRequest fromPhone() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(TestTailnet.SHIR_PHONE.address());
        request.addHeader(TailscaleDeviceIdentity.USER_HEADER, TestTailnet.SHIR_PHONE.login());
        return request;
    }

    @Test
    void aTailnetPhoneThroughTailscaleServeIsItsStableNode() {
        assertThat(identity.resolve(fromPhone())).contains(TestTailnet.SHIR_PHONE.identity());
    }

    @Test
    void aFunnelRequestIsRefused() {
        MockHttpServletRequest request = fromPhone();
        request.addHeader(TailscaleDeviceIdentity.FUNNEL_HEADER, "?1");
        assertThat(identity.resolve(request)).isEmpty();
    }

    @Test
    void anAddressOffTheTailnetIsRefused() {
        for (String address : new String[] {"127.0.0.1", "192.168.1.20", "100.128.0.1", "::1", "fd7a:115c:a1e1::1", "", "casita.local"}) {
            MockHttpServletRequest request = fromPhone();
            request.setRemoteAddr(address);
            whois.put(address, new TailscaleWhois.Node("nX", "x", TestTailnet.SHIR_PHONE.login(), false));
            assertThat(identity.resolve(request)).as(address).isEmpty();
        }
        assertThat(TailscaleDeviceIdentity.isTailnetAddress("100.64.0.1")).isTrue();
        assertThat(TailscaleDeviceIdentity.isTailnetAddress("100.127.255.254")).isTrue();
        assertThat(TailscaleDeviceIdentity.isTailnetAddress("fd7a:115c:a1e0::abcd")).isTrue();
    }

    @Test
    void withoutTheUserHeaderFromTailscaleServeItIsRefused() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(TestTailnet.SHIR_PHONE.address());
        assertThat(identity.resolve(request)).isEmpty();
    }

    @Test
    void anAddressTailscaledDoesntKnowIsRefused() {
        whois.forget(TestTailnet.SHIR_PHONE.address());
        assertThat(identity.resolve(fromPhone())).isEmpty();
    }

    @Test
    void aUserHeaderThatDisagreesWithTailscaledIsRefused() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(TestTailnet.SHIR_PHONE.address());
        request.addHeader(TailscaleDeviceIdentity.USER_HEADER, TestTailnet.HUBBY_PHONE.login());
        assertThat(identity.resolve(request)).isEmpty();
    }

    @Test
    void aTaggedNodeIsRefused() {
        whois.put(TestTailnet.SHIR_PHONE.address(), new TailscaleWhois.Node("nServer", "server", TestTailnet.SHIR_PHONE.login(), true));
        assertThat(identity.resolve(fromPhone())).isEmpty();
    }

    @Test
    void developmentModeIsOnlyThisComputerDirectly() {
        DevelopmentDeviceIdentity dev = new DevelopmentDeviceIdentity();
        MockHttpServletRequest local = new MockHttpServletRequest();
        assertThat(dev.resolve(local)).isPresent();

        MockHttpServletRequest proxied = new MockHttpServletRequest();
        proxied.addHeader("X-Forwarded-For", "100.64.0.11");
        assertThat(dev.resolve(proxied)).isEmpty();
        MockHttpServletRequest remote = new MockHttpServletRequest();
        remote.setRemoteAddr("100.64.0.11");
        assertThat(dev.resolve(remote)).isEmpty();
    }
}
