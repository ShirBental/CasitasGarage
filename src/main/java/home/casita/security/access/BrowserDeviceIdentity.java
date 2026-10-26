package home.casita.security.access;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The device behind a request is the browser key in its {@value DeviceKeys#COOKIE} cookie (docs/ACCESS.md):
 * <ul>
 *   <li>only over HTTPS (behind the reverse proxy, Tomcat takes X-Forwarded-Proto from 127.0.0.1 only), since
 *       the browser keeps a {@code __Host-} cookie for HTTPS alone;</li>
 *   <li>only a cookie whose MAC checks out under the server's device key: anything else is no device;</li>
 *   <li>a new key is minted only when an invite is redeemed ({@link #resolveOrIssue}); signing in never mints
 *       one, so a browser without an enrolled key can't get a session, whatever passkey it has.</li>
 * </ul>
 * Whether that device is authorized, and for whom, is the security store's answer, checked on every request.
 */
public class BrowserDeviceIdentity implements DeviceIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(BrowserDeviceIdentity.class);

    private final DeviceKeys keys;

    public BrowserDeviceIdentity(DeviceKeys keys) {
        this.keys = keys;
    }

    @Override
    public Optional<DeviceIdentity> resolve(HttpServletRequest request) {
        if (!request.isSecure()) return Optional.empty();
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        for (Cookie cookie : cookies) {
            if (!DeviceKeys.COOKIE.equals(cookie.getName())) continue;
            Optional<String> id = keys.verify(cookie.getValue());
            if (id.isPresent()) return Optional.of(identity(id.get()));
        }
        return Optional.empty();
    }

    @Override
    public Optional<DeviceIdentity> resolveOrIssue(HttpServletRequest request, HttpServletResponse response) {
        Optional<DeviceIdentity> existing = resolve(request);
        if (existing.isPresent() || !request.isSecure()) return existing;
        DeviceKeys.Issued issued = keys.issue();
        response.addHeader(HttpHeaders.SET_COOKIE, DeviceKeys.cookie(issued.cookieValue()).toString());
        log.info("New browser key {} for an invite redemption", identity(issued.deviceId()).nodeName());
        return Optional.of(identity(issued.deviceId()));
    }

    @Override
    public void renew(HttpServletRequest request, HttpServletResponse response, DeviceIdentity device) {
        if (!request.isSecure() || response.isCommitted()) return;
        response.addHeader(HttpHeaders.SET_COOKIE, DeviceKeys.cookie(keys.cookieValueFor(device.nodeId())).toString());
    }

    /** "browser b-1a2b3c4d": enough to tell devices apart in logs and listings. */
    private static DeviceIdentity identity(String deviceId) {
        return new DeviceIdentity(deviceId, "browser " + deviceId.substring(0, Math.min(deviceId.length(), 10)));
    }
}
