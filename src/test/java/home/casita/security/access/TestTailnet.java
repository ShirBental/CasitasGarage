package home.casita.security.access;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import home.casita.security.store.SecurityStore;
import home.casita.security.store.SecurityStore.EnrollmentClaim;

/**
 * A pretend tailnet for tests: a fake tailscaled behind the real {@link TailscaleDeviceIdentity}, requests as
 * tailscale serve delivers them, and enrollment and sign-in through the real store and success handler (only
 * the WebAuthn cryptography is skipped: tests hand over a passkey as if Spring had just verified it).
 * Import {@link Config}.
 */
public final class TestTailnet {

    private TestTailnet() {}

    /** A phone on the tailnet: its address, its stable node id and its Tailscale user. */
    public record Phone(String address, String nodeId, String name, String login) {
        public DeviceIdentity identity() {
            return new DeviceIdentity(nodeId, name, login);
        }
    }

    public static final Phone SHIR_PHONE = new Phone("100.64.0.11", "nShirPhoneCNTRL", "shirs-iphone", "shir@example.com");
    public static final Phone SHIR_IPAD = new Phone("100.64.0.12", "nShirIpadCNTRL", "shirs-ipad", "shir@example.com");
    public static final Phone HUBBY_PHONE = new Phone("100.64.0.21", "nHubbyPhoneCNTRL", "hubbys-iphone", "hubby@example.com");

    /** tailscaled, as far as the tests are concerned. */
    public static final class FakeWhois implements TailscaleWhois {
        private final Map<String, Node> nodes = new ConcurrentHashMap<>();

        public FakeWhois() {
            reset();
        }

        public void reset() {
            nodes.clear();
            for (Phone p : List.of(SHIR_PHONE, SHIR_IPAD, HUBBY_PHONE)) nodes.put(p.address(), new Node(p.nodeId(), p.name(), p.login(), false));
        }

        public void put(String address, Node node) {
            nodes.put(address, node);
        }

        public void forget(String address) {
            nodes.remove(address);
        }

        @Override
        public Optional<Node> whois(String address) {
            return Optional.ofNullable(nodes.get(address));
        }
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        FakeWhois fakeWhois() {
            return new FakeWhois();
        }
    }

    /** A request from the phone through tailscale serve (Tomcat has already applied X-Forwarded-For). */
    public static RequestPostProcessor from(Phone phone) {
        return request -> {
            request.setRemoteAddr(phone.address());
            request.addHeader(TailscaleDeviceIdentity.USER_HEADER, phone.login());
            request.addHeader("X-Forwarded-Proto", "https");
            return request;
        };
    }

    /** The member's WebAuthn user handle, given to them the first time (as Spring does at passkey setup). */
    public static PublicKeyCredentialUserEntity user(SecurityStore store, String member) {
        Bytes handle = store.member(member).map(SecurityStore.Member::userHandle).orElse(null);
        if (handle == null) {
            handle = Bytes.random();
            if (!store.setUserHandle(member, handle)) throw new IllegalStateException("can't give " + member + " a user handle");
        }
        return ImmutablePublicKeyCredentialUserEntity.builder().name(member).id(handle).displayName(member).build();
    }

    public static CredentialRecord credential(PublicKeyCredentialUserEntity user, String credentialId) {
        return ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(Bytes.fromBase64(credentialId))
                .userEntityUserId(user.getId())
                .publicKey(new ImmutablePublicKeyCose(new byte[] {1, 2, 3}))
                .signatureCount(0)
                .uvInitialized(true)
                .transports(Set.of(AuthenticatorTransport.HYBRID))
                .backupEligible(true)
                .backupState(true)
                .label("iCloud Keychain")
                .created(Instant.parse("2026-10-01T00:00:00Z"))
                .lastUsed(Instant.parse("2026-10-01T00:00:00Z"))
                .build();
    }

    /** An invitation redeemed on the phone: the claim an enrollment session holds. */
    public static EnrollmentClaim redeemed(ApplicationContext context, String member, String label, Phone phone) {
        Invitations invitations = context.getBean(Invitations.class);
        return invitations.redeem(invitations.create(member, label).token(), phone.identity()).orElseThrow();
    }

    /** The phone enrolled with a new passkey, through an invitation and the enrollment transaction. */
    public static void enrollNewPasskey(ApplicationContext context, String member, String credentialId, Phone phone) {
        SecurityStore store = context.getBean(SecurityStore.class);
        EnrollmentClaim claim = redeemed(context, member, member + "'s phone", phone);
        if (!store.enroll(claim, credential(user(store, member), credentialId), null)) throw new IllegalStateException("enrollment refused");
    }

    /** Another phone enrolled with the member's existing (synced) passkey, through its own invitation. */
    public static void enrollExistingPasskey(ApplicationContext context, String member, String credentialId, Phone phone) {
        SecurityStore store = context.getBean(SecurityStore.class);
        EnrollmentClaim claim = redeemed(context, member, member + "'s other device", phone);
        if (!store.enroll(claim, null, Bytes.fromBase64(credentialId))) throw new IllegalStateException("enrollment refused");
    }

    /**
     * A passkey sign-in on the phone, finished by the real success handler, in {@code session} (a new one if null).
     * Returns null if it was accepted, otherwise the refusal; a refused session is left as the handler leaves it.
     */
    public static String signIn(ApplicationContext context, MockHttpSession session, String member, String credentialId, Phone phone) {
        SecurityStore store = context.getBean(SecurityStore.class);
        WebAuthnAuthentication auth = new WebAuthnAuthentication(user(store, member), List.of(new SimpleGrantedAuthority("ROLE_FAMILY")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(auth));
        session.setAttribute(AccessSession.SIGNED_IN_CREDENTIAL, credentialId);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login/webauthn");
        request.setSession(session);
        from(phone).postProcessRequest(request);
        String refusal = context.getBean(PasskeySignInSuccessHandler.class).complete(request, auth);
        if (refusal != null) session.invalidate();
        return refusal;
    }

    /** A member with a passkey, their phone enrolled, and a signed-in session on it. */
    public static MockHttpSession signedInOn(ApplicationContext context, String member, String credentialId, Phone phone) {
        enrollNewPasskey(context, member, credentialId, phone);
        MockHttpSession session = new MockHttpSession();
        String refusal = signIn(context, session, member, credentialId, phone);
        if (refusal != null) throw new IllegalStateException("sign-in refused: " + refusal);
        return session;
    }
}
