package home.casita.security.access;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import home.casita.security.store.SecurityStore;
import home.casita.security.store.SecurityStore.EnrollmentClaim;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Hears about passkeys as Spring's WebAuthn saves them, inside the request doing it:
 * <ul>
 *   <li>a <b>new</b> passkey (its attestation just verified) is stored only by the enrollment transaction:
 *       a live enrollment, for its member, on its device, re-verified now. Otherwise, or if the transaction is
 *       refused, the save fails, the registration fails, and nothing is stored;</li>
 *   <li>a <b>used</b> passkey (its assertion just verified) is noted on the request, so this sign-in's success
 *       handler can tie the session to it.</li>
 * </ul>
 */
public class CredentialEvents {

    private static final Logger log = LoggerFactory.getLogger(CredentialEvents.class);

    private final SecurityStore store;
    private final DeviceIdentityResolver devices;
    private final AccessDecisions decisions;

    public CredentialEvents(SecurityStore store, DeviceIdentityResolver devices, AccessDecisions decisions) {
        this.store = store;
        this.devices = devices;
        this.decisions = decisions;
    }

    public void newCredential(CredentialRecord record) {
        HttpServletRequest request = currentRequest().orElseThrow(() -> refuse("no request"));
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(AccessSession.ENROLLMENT) instanceof EnrollmentClaim claim)) throw refuse("no enrollment");
        String owner = store.memberByUserHandle(record.getUserEntityUserId()).map(SecurityStore.Member::id).orElse(null);
        if (!claim.memberId().equals(owner)) throw refuse("passkey for someone else");
        Optional<DeviceIdentity> device = devices.resolve(request);
        if (device.isEmpty()) throw refuse("device not verified");
        String refusal = decisions.enrollmentSession(claim.memberId(), claim, device.get());
        if (refusal != null) throw refuse(refusal);
        if (!store.enroll(claim, record, null)) throw refuse("the enrollment transaction was refused");
        session.removeAttribute(AccessSession.ENROLLMENT);
        log.info("Invite {} finished: new passkey for {} on device {} ({})", claim.inviteId(), claim.memberId(), claim.nodeName(), claim.label());
    }

    public void credentialUsed(CredentialRecord record) {
        // On this request only: the success handler of this same sign-in reads it, and no other request can supply it.
        currentRequest().ifPresent(r -> r.setAttribute(AccessSession.SIGNED_IN_CREDENTIAL, record.getCredentialId().toBase64UrlString()));
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
