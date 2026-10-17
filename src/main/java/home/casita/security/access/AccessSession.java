package home.casita.security.access;

import java.io.Serializable;

/**
 * What a session carries for access control: session attributes, kept on the server. Nothing here is a secret
 * or a token; every claim is checked against the security store on every request.
 */
public final class AccessSession {

    private AccessSession() {}

    /** A passkey sign-in, tied to the passkey used and the device it happened on. */
    public record Binding(String member, String credentialId, String nodeId) implements Serializable {}

    /** The enrollment in progress: a {@link home.casita.security.store.SecurityStore.EnrollmentClaim}. */
    public static final String ENROLLMENT = "casita.enrollment";
    public static final String BINDING = "casita.binding";
    /** The passkey a sign-in just used (set while Spring verifies the assertion). */
    public static final String SIGNED_IN_CREDENTIAL = "casita.signedInCredential";
    /** Request attribute: the device verified for an invite redemption, before the invite is consumed. */
    public static final String REDEEMING_DEVICE = "casita.redeemingDevice";

    public static final String ROLE_ENROLLING = "ROLE_ENROLLING";
}
