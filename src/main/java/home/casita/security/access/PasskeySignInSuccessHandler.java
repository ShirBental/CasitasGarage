package home.casita.security.access;

import java.io.IOException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpMessageConverterAuthenticationSuccessHandler;
import org.springframework.security.web.webauthn.api.Bytes;

import home.casita.security.store.SecurityStore;
import home.casita.security.store.SecurityStore.EnrollmentClaim;
import home.casita.security.store.StoreUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * After a passkey sign-in has been fully verified (assertion, user verification, user): ties the session to
 * the passkey and the device, then lets it in only if the member, passkey and device all check out.
 *
 * <p>If the session carries an enrollment for this member on this device (an invitation redeemed here, then a
 * sign-in with the member's existing synced passkey), the enrollment transaction authorizes the device first.
 * Anything refused: the session ends and the sign-in answers 401, so the device never gets a session.
 */
public class PasskeySignInSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(PasskeySignInSuccessHandler.class);

    private final SecurityStore store;
    private final DeviceIdentityResolver devices;
    private final AccessDecisions decisions;
    private final AuthenticationSuccessHandler next = new HttpMessageConverterAuthenticationSuccessHandler();

    public PasskeySignInSuccessHandler(SecurityStore store, DeviceIdentityResolver devices, AccessDecisions decisions) {
        this.store = store;
        this.devices = devices;
        this.decisions = decisions;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, jakarta.servlet.ServletException {
        String refusal = complete(request, authentication);
        if (refusal != null) {
            log.info("Passkey sign-in refused for {}: {}", authentication.getName(), refusal);
            HttpSession session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"authenticated\":false}");
            return;
        }
        next.onAuthenticationSuccess(request, response, authentication);
    }

    /** Null when the sign-in may proceed (and the session is now bound); otherwise why not. */
    public String complete(HttpServletRequest request, Authentication authentication) {
        HttpSession session = request.getSession(false);
        if (session == null) return "no session";
        String member = authentication.getName();
        String credentialId = (String) session.getAttribute(AccessSession.SIGNED_IN_CREDENTIAL);
        session.removeAttribute(AccessSession.SIGNED_IN_CREDENTIAL);
        if (credentialId == null) return "sign-in without a known passkey";
        Optional<DeviceIdentity> device = devices.resolve(request);
        if (device.isEmpty()) return "device not verified";

        Object pending = session.getAttribute(AccessSession.ENROLLMENT);
        session.removeAttribute(AccessSession.ENROLLMENT);
        if (pending instanceof EnrollmentClaim claim && claim.memberId().equals(member) && claim.nodeId().equals(device.get().nodeId())) {
            try {
                if (decisions.enrollmentSession(member, claim, device.get()) == null && store.enroll(claim, null, Bytes.fromBase64(credentialId))) {
                    log.info("Invite {} finished: existing passkey for {} on node {} ({})", claim.inviteId(), member, claim.nodeName(), claim.label());
                }
            } catch (StoreUnavailableException e) {
                return "security store unavailable";
            }
        }

        AccessSession.Binding binding = new AccessSession.Binding(member, credentialId, device.get().nodeId());
        String refusal = decisions.passkeySession(member, binding, device.get());
        if (refusal != null) return refusal;
        session.setAttribute(AccessSession.BINDING, binding);
        return null;
    }
}
