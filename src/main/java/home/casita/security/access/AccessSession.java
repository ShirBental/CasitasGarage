package home.casita.security.access;

import java.io.Serializable;

/** What a session carries for access control. Session attributes only; nothing here is a secret. */
public final class AccessSession {

    private AccessSession() {}

    /** An enrollment in progress: which invite, for whom, which device, and the device's label. */
    public record Enrollment(String inviteId, String member, String label, String nodeId, String nodeName) implements Serializable {}

    /** A passkey sign-in, tied to the passkey used and the device it happened on. */
    public record Binding(String member, String credentialId, String nodeId) implements Serializable {}

    public static final String ENROLLMENT = "casita.enrollment";
    public static final String BINDING = "casita.binding";
    /** The passkey a sign-in just used (set while Spring verifies the assertion). */
    public static final String SIGNED_IN_CREDENTIAL = "casita.signedInCredential";
    /** Request attribute: the device verified for an invite redemption, before the invite is used. */
    public static final String REDEEMING_DEVICE = "casita.redeemingDevice";

    public static final String ROLE_ENROLLING = "ROLE_ENROLLING";
}
