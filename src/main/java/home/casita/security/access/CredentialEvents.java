package home.casita.security.access;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Hears about passkeys as the passkey store saves them, inside the request doing it:
 * <ul>
 *   <li>a <b>new</b> passkey must come from a live enrollment, for the invite's member, on the invite's device;
 *       then that device is authorized and the invite is finished. Otherwise the save is refused, so the
 *       registration fails and nothing is stored;</li>
 *   <li>a <b>used</b> passkey (a sign-in updating its record) is noted in the session, so the sign-in can be tied to it.</li>
 * </ul>
 */
public class CredentialEvents {

    private static final Logger log = LoggerFactory.getLogger(CredentialEvents.class);

    private final Invites invites;
    private final AccessStore access;
    private final DeviceIdentityResolver devices;

    public CredentialEvents(Invites invites, AccessStore access, DeviceIdentityResolver devices) {
        this.invites = invites;
        this.access = access;
        this.devices = devices;
    }

    public void beforeNewCredential(CredentialRecord record, PublicKeyCredentialUserEntityRepository users) {
        HttpServletRequest request = currentRequest().orElseThrow(() -> refuse("no request"));
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(AccessSession.ENROLLMENT) instanceof AccessSession.Enrollment enrollment)) {
            throw refuse("no enrollment");
        }
        PublicKeyCredentialUserEntity owner = users.findById(record.getUserEntityUserId());
        if (owner == null || !owner.getName().equals(enrollment.member())) throw refuse("passkey for someone else");
        if (invites.activeEnrollment(enrollment.inviteId()).isEmpty()) throw refuse("invite revoked or expired");
        Optional<DeviceIdentity> device = devices.resolve(request);
        if (device.isEmpty() || !device.get().nodeId().equals(enrollment.nodeId())) throw refuse("not the invite's device");

        access.authorize(enrollment.member(), enrollment.label(), device.get(), record.getCredentialId().toBase64UrlString(), enrollment.inviteId());
        invites.finish(enrollment.inviteId());
        session.removeAttribute(AccessSession.ENROLLMENT);
        log.info("Invite {} finished: new passkey for {} on node {} ({})", enrollment.inviteId(), enrollment.member(),
                device.get().nodeName(), enrollment.label());
    }

    public void credentialUsed(CredentialRecord record) {
        currentRequest().map(r -> r.getSession(false)).ifPresent(s ->
                s.setAttribute(AccessSession.SIGNED_IN_CREDENTIAL, record.getCredentialId().toBase64UrlString()));
    }

    private static Optional<HttpServletRequest> currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? Optional.of(attributes.getRequest()) : Optional.empty();
    }

    private static IllegalStateException refuse(String why) {
        log.warn("Passkey registration refused: {}", why);
        return new IllegalStateException("Passkey registration refused: " + why);
    }
}
