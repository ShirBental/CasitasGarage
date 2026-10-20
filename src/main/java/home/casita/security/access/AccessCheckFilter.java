package home.casita.security.access;

import java.io.IOException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;
import org.springframework.web.filter.OncePerRequestFilter;

import home.casita.security.store.SecurityStore.EnrollmentClaim;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Every request of a signed-in session is checked again, against one consistent read of the security store,
 * so revoking anything takes effect on the next request and a session can't wander to another device. A
 * session that fails is ended on the spot, and the request goes on signed out.
 *
 * <ul>
 *   <li><b>Passkey sign-in:</b> bound at sign-in to its passkey and device ({@link PasskeySignInSuccessHandler});
 *       member active and in the family, passkey active and theirs, device authorized for them, same device.</li>
 *   <li><b>Enrollment:</b> its invitation is consumed by this enrollment, on this device, and still live.</li>
 *   <li>Any other kind of authentication: refused.</li>
 * </ul>
 */
public class AccessCheckFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessCheckFilter.class);

    private final DeviceIdentityResolver devices;
    private final AccessDecisions decisions;

    public AccessCheckFilter(DeviceIdentityResolver devices, AccessDecisions decisions) {
        this.devices = devices;
        this.decisions = decisions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        String refusal = check(request, auth);
        if (refusal != null) {
            log.info("Session ended for {}: {}", auth.getName(), refusal);
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            // Continue as anonymous: /api answers 401, pages go to the sign-in page.
            request.setAttribute("casita.accessRefusal", refusal);
        }
        chain.doFilter(request, response);
    }

    String check(HttpServletRequest request, Authentication auth) {
        HttpSession session = request.getSession(false);
        if (session == null) return "no session";
        Optional<DeviceIdentity> device = devices.resolve(request);
        if (device.isEmpty()) return "device not verified";
        if (auth.getAuthorities().stream().anyMatch(a -> AccessSession.ROLE_ENROLLING.equals(a.getAuthority()))) {
            EnrollmentClaim claim = session.getAttribute(AccessSession.ENROLLMENT) instanceof EnrollmentClaim c ? c : null;
            return decisions.enrollmentSession(auth.getName(), claim, device.get());
        }
        if (!(auth instanceof WebAuthnAuthentication)) return "not a passkey sign-in";
        AccessSession.Binding binding = session.getAttribute(AccessSession.BINDING) instanceof AccessSession.Binding b ? b : null;
        return decisions.passkeySession(auth.getName(), binding, device.get());
    }
}
