package home.casita.security.access;

import java.time.Clock;
import java.util.List;

import org.springframework.security.web.webauthn.api.Bytes;

import home.casita.security.store.SecurityStore;
import home.casita.security.store.SecurityStore.AccessView;
import home.casita.security.store.SecurityStore.EnrollmentClaim;
import home.casita.security.store.SecurityStore.EnrollmentView;
import home.casita.security.store.SecurityStore.Invitation;
import home.casita.security.store.StoreUnavailableException;

/**
 * The two questions every request asks, answered from one consistent read of the security store each.
 * Each method returns null when the session may go on, otherwise why not (for the log; never shown to anyone).
 * The store unavailable: refused.
 */
public class AccessDecisions {

    private final SecurityStore store;
    private final List<String> family;
    private final Clock clock;

    public AccessDecisions(SecurityStore store, List<String> family, Clock clock) {
        this.store = store;
        this.family = family;
        this.clock = clock;
    }

    /** A passkey session: member, passkey, device authorization and the device itself. */
    public String passkeySession(String member, AccessSession.Binding binding, DeviceIdentity device) {
        if (!family.contains(member)) return "not in the family";
        if (binding == null) return "sign-in not completed";
        if (!binding.member().equals(member)) return "session belongs to someone else";
        if (!binding.nodeId().equals(device.nodeId())) return "request from another device";
        AccessView view;
        try {
            view = store.accessView(member, Bytes.fromBase64(binding.credentialId()), binding.nodeId());
        } catch (StoreUnavailableException e) {
            return "security store unavailable";
        }
        if (view.member().isEmpty() || !view.member().get().active()) return "member revoked";
        if (view.passkey().isEmpty() || !view.passkey().get().activeFor(member)) return "passkey revoked or not theirs";
        if (view.device().isEmpty() || !view.device().get().activeFor(member)) return "device not authorized";
        return null;
    }

    /** An enrollment session: its invitation is consumed by this attempt, on this device, and still live. */
    public String enrollmentSession(String member, EnrollmentClaim claim, DeviceIdentity device) {
        if (!family.contains(member)) return "not in the family";
        if (claim == null) return "enrollment finished";
        if (!claim.memberId().equals(member)) return "enrollment belongs to someone else";
        if (!claim.nodeId().equals(device.nodeId())) return "enrollment moved to another device";
        EnrollmentView view;
        try {
            view = store.enrollmentView(claim.tokenHash(), member);
        } catch (StoreUnavailableException e) {
            return "security store unavailable";
        }
        if (view.member().isEmpty() || !view.member().get().active()) return "member revoked";
        if (view.invitation().isEmpty()) return "invitation unknown";
        Invitation i = view.invitation().get();
        if (!i.enrolling(clock.instant())) return "invitation revoked, expired or already used";
        if (!claim.enrollmentId().equals(i.enrollmentId()) || !claim.nodeId().equals(i.nodeId())) return "not this enrollment";
        return null;
    }
}
