package home.casita.security.access;

import java.io.IOException;
import java.util.Optional;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Before an invite is redeemed (POST /login/ott), the device must be verifiable. If it isn't, the invite is
 * left unused and the person is told why, rather than spending it on a device Casita can't identify.
 */
public class RedemptionGuardFilter extends OncePerRequestFilter {

    private final DeviceIdentityResolver devices;

    public RedemptionGuardFilter(DeviceIdentityResolver devices) {
        this.devices = devices;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && "/login/ott".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<DeviceIdentity> device = devices.resolve(request);
        if (device.isEmpty()) {
            response.sendRedirect("/login?device-unverified");
            return;
        }
        request.setAttribute(AccessSession.REDEEMING_DEVICE, device.get());
        chain.doFilter(request, response);
    }
}
