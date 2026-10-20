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
 * Before an invitation is redeemed (POST /login/ott), with consistent reads and without consuming anything:
 * <ul>
 *   <li>the device must be verifiable;</li>
 *   <li>it must not be authorized already, and not tied to another member (revoked for them and not released);</li>
 *   <li>the invitation's member must not be revoked.</li>
 * </ul>
 * The enrollment transaction would refuse all of these anyway; checking first leaves the invitation unused and
 * says why. The store unavailable: refused, invitation untouched.
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
        String refusal;
        try {
            refusal = refusal(device.get(), request.getParameter("token"));
        } catch (StoreUnavailableException e) {
            refusal = "/login?error";
        }
        if (refusal != null) {
            response.sendRedirect(refusal);
            return;
        }
        request.setAttribute(AccessSession.REDEEMING_DEVICE, device.get());
        chain.doFilter(request, response);
    }

    /** Where to send the person instead of redeeming, or null to go ahead. */
    private String refusal(DeviceIdentity device, String token) {
        Optional<SecurityStore.Device> authorization = store.device(device.nodeId());
        if (authorization.filter(d -> d.status() == SecurityStore.Status.ACTIVE).isPresent()) return "/login?device-already-set-up";
        Optional<SecurityStore.Invitation> invitation = token == null || token.isBlank() ? Optional.empty()
                : store.invitation(Invitations.hash(token));
        if (invitation.isEmpty()) return null; // unknown token: redemption fails the ordinary way
        String member = invitation.get().memberId();
        if (store.member(member).filter(SecurityStore.Member::active).isEmpty()) return "/login?error";
        boolean tiedToSomeoneElse = authorization.filter(d -> d.status() == SecurityStore.Status.REVOKED && !d.memberId().equals(member))
                .isPresent();
        return tiedToSomeoneElse ? "/login?device-not-available" : null;
    }
}
