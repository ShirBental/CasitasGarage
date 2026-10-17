package home.casita.security.access;

import java.io.IOException;
import java.util.Optional;

import org.springframework.web.filter.OncePerRequestFilter;

import home.casita.security.store.SecurityStore;
import home.casita.security.store.StoreUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Before an invitation is redeemed (POST /login/ott): the device must be verifiable, and not already
 * authorized (the enrollment transaction would refuse it; checking first spares the invitation). Otherwise
 * the invitation is left unused and the page says why. The store unavailable: refused, invitation untouched.
 */
public class RedemptionGuardFilter extends OncePerRequestFilter {

    private final DeviceIdentityResolver devices;
    private final SecurityStore store;

    public RedemptionGuardFilter(DeviceIdentityResolver devices, SecurityStore store) {
        this.devices = devices;
        this.store = store;
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
        try {
            if (store.device(device.get().nodeId()).filter(d -> d.status() == SecurityStore.Status.ACTIVE).isPresent()) {
                response.sendRedirect("/login?device-already-set-up");
                return;
            }
        } catch (StoreUnavailableException e) {
            response.sendRedirect("/login?error");
            return;
        }
        request.setAttribute(AccessSession.REDEEMING_DEVICE, device.get());
        chain.doFilter(request, response);
    }
}
