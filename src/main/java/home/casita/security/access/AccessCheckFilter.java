package home.casita.security.access;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;
import org.springframework.web.filter.OncePerRequestFilter;

import home.casita.security.FilePasskeyStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Every request of a signed-in session is checked again, so revoking anything takes effect on the next
 * request and a session can't wander to another device. A session that fails is ended on the spot.
 *
 * <ul>
 *   <li><b>Passkey sign-in:</b> on its first request it is tied to the passkey used and the verified device.
 *       If an enrollment from an invite is waiting on that device for the same member (another device of an
 *       Apple ID whose synced passkey already exists), that device is authorized now. Then, on every request:
 *       the member is still in the family, the passkey still exists and is theirs, the device is still
 *       authorized for them, and the request comes from that same device.</li>
 *   <li><b>Enrollment:</b> its invite is still live and the request comes from the invite's device.</li>
 *   <li>Any other kind of authentication: refused.</li>
 * </ul>
 */
public class AccessCheckFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessCheckFilter.class);

    private final DeviceIdentityResolver devices;
    private final AccessStore access;
    private final Invites invites;
    private final FilePasskeyStore passkeys;
    private final List<String> family;

    public AccessCheckFilter(DeviceIdentityResolver devices, AccessStore access, Invites invites, FilePasskeyStore passkeys, List<String> family) {
        this.devices = devices;
        this.access = access;
        this.invites = invites;
        this.passkeys = passkeys;
        this.family = family;
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

    /** Null when the session may go on; otherwise why not. */
    String check(HttpServletRequest request, Authentication auth) {
        HttpSession session = request.getSession(false);
        if (session == null) return "no session";
        Optional<DeviceIdentity> device = devices.resolve(request);
        if (device.isEmpty()) return "device not verified";

        if (auth.getAuthorities().stream().anyMatch(a -> AccessSession.ROLE_ENROLLING.equals(a.getAuthority()))) {
            if (!(session.getAttribute(AccessSession.ENROLLMENT) instanceof AccessSession.Enrollment enrollment)) return "enrollment finished";
            if (invites.activeEnrollment(enrollment.inviteId()).isEmpty()) return "invite revoked or expired";
            if (!enrollment.nodeId().equals(device.get().nodeId())) return "enrollment moved to another device";
            return null;
        }
        if (!(auth instanceof WebAuthnAuthentication passkey)) return "not a passkey sign-in";
        String member = passkey.getName();
        if (!family.contains(member)) return "not in the family";

        AccessSession.Binding binding = session.getAttribute(AccessSession.BINDING) instanceof AccessSession.Binding b ? b : null;
        if (binding == null) {
            String credentialId = (String) session.getAttribute(AccessSession.SIGNED_IN_CREDENTIAL);
            if (credentialId == null) return "sign-in without a known passkey";
            enrollWithExistingPasskey(session, member, credentialId, device.get());
            binding = new AccessSession.Binding(member, credentialId, device.get().nodeId());
            session.setAttribute(AccessSession.BINDING, binding);
            session.removeAttribute(AccessSession.SIGNED_IN_CREDENTIAL);
        }
        if (!binding.member().equals(member)) return "session belongs to someone else";
        if (!binding.nodeId().equals(device.get().nodeId())) return "request from another device";
        CredentialRecord credential = passkeys.findByCredentialId(org.springframework.security.web.webauthn.api.Bytes.fromBase64(binding.credentialId()));
        if (credential == null) return "passkey revoked";
        PublicKeyCredentialUserEntity owner = passkeys.findById(credential.getUserEntityUserId());
        if (owner == null || !owner.getName().equals(member)) return "passkey isn't theirs";
        if (access.find(member, binding.nodeId()).isEmpty()) return "device not authorized";
        return null;
    }

    /** Another device of the same member, whose synced passkey already existed: its invite enrolls it here. */
    private void enrollWithExistingPasskey(HttpSession session, String member, String credentialId, DeviceIdentity device) {
        if (!(session.getAttribute(AccessSession.ENROLLMENT) instanceof AccessSession.Enrollment enrollment)) return;
        boolean valid = enrollment.member().equals(member) && enrollment.nodeId().equals(device.nodeId())
                && invites.activeEnrollment(enrollment.inviteId()).isPresent();
        session.removeAttribute(AccessSession.ENROLLMENT);
        if (!valid) return;
        access.authorize(member, enrollment.label(), device, credentialId, enrollment.inviteId());
        invites.finish(enrollment.inviteId());
        log.info("Invite {} finished: existing passkey for {} on node {} ({})", enrollment.inviteId(), member, device.nodeName(), enrollment.label());
    }
}
