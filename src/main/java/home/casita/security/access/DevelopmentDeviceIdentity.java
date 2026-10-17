package home.casita.security.access;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

/**
 * For local development only ({@code casita.security.device-identity: development}, allowed only with
 * rp-id localhost): a request straight to 127.0.0.1, with no proxy headers, is "this computer". Anything
 * forwarded is refused, so it can never stand in for Tailscale.
 */
public class DevelopmentDeviceIdentity implements DeviceIdentityResolver {

    static final DeviceIdentity THIS_COMPUTER = new DeviceIdentity("development:this-computer", "this computer (development)", "development");

    @Override
    public Optional<DeviceIdentity> resolve(HttpServletRequest request) {
        boolean loopback = "127.0.0.1".equals(request.getRemoteAddr()) || "0:0:0:0:0:0:0:1".equals(request.getRemoteAddr());
        boolean forwarded = request.getHeader("X-Forwarded-For") != null || request.getHeader("X-Forwarded-Host") != null
                || request.getHeader(TailscaleDeviceIdentity.USER_HEADER) != null;
        return loopback && !forwarded ? Optional.of(THIS_COMPUTER) : Optional.empty();
    }
}
