package home.casita.security.access;

import java.io.IOException;
import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import home.casita.security.store.SecurityStore.EnrollmentClaim;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Setting up a passkey ({@code /webauthn/register/options}, {@code /webauthn/register}) is allowed only in an
 * enrollment session whose invitation is still live, from the device it was redeemed on. A signed-in family
 * member can't add passkeys any other way. Runs before Spring's WebAuthn registration filters.
 */
public class EnrollmentGuardFilter extends OncePerRequestFilter {

    private final AccessDecisions decisions;
    private final DeviceIdentityResolver devices;

    public EnrollmentGuardFilter(AccessDecisions decisions, DeviceIdentityResolver devices) {
        this.decisions = decisions;
        this.devices = devices;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !("POST".equals(request.getMethod()) && (path.equals("/webauthn/register/options") || path.equals("/webauthn/register")));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!allowed(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean allowed(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream().noneMatch(a -> AccessSession.ROLE_ENROLLING.equals(a.getAuthority()))) return false;
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(AccessSession.ENROLLMENT) instanceof EnrollmentClaim claim)) return false;
        Optional<DeviceIdentity> device = devices.resolve(request);
        return device.isPresent() && decisions.enrollmentSession(auth.getName(), claim, device.get()) == null;
    }
}
