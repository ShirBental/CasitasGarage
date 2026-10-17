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

import home.casita.security.FilePasskeyStore;

/**
 * A pretend tailnet for tests: a fake tailscaled behind the real {@link TailscaleDeviceIdentity}, requests as
 * tailscale serve delivers them, and sessions as a real passkey sign-in leaves them. Import {@link Config}.
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

    /** A passkey stored as Spring would after registration (bypassing enrollment, which tests check elsewhere). */
    public static CredentialRecord storePasskey(ApplicationContext context, String member, String credentialId) {
        FilePasskeyStore passkeys = context.getBean(FilePasskeyStore.class);
        PublicKeyCredentialUserEntity user = passkeys.findByUsername(member);
        if (user == null) {
            user = ImmutablePublicKeyCredentialUserEntity.builder().name(member).id(Bytes.random()).displayName(member).build();
            passkeys.save(user);
        }
        CredentialRecord record = credential(user, credentialId);
        passkeys.setEvents(null);
        try {
            passkeys.save(record);
        } finally {
            passkeys.setEvents(context.getBean(CredentialEvents.class));
        }
        return record;
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

    /** The session a passkey sign-in leaves, before Casita has tied it to a device (the first request does). */
    public static MockHttpSession justSignedIn(ApplicationContext context, String member, String credentialId) {
        PublicKeyCredentialUserEntity user = context.getBean(FilePasskeyStore.class).findByUsername(member);
        WebAuthnAuthentication auth = new WebAuthnAuthentication(user, List.of(new SimpleGrantedAuthority("ROLE_FAMILY")));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(auth));
        session.setAttribute(AccessSession.SIGNED_IN_CREDENTIAL, credentialId);
        return session;
    }

    /** A member with a passkey, an authorized phone, and a signed-in session on it. */
    public static MockHttpSession signedInOn(ApplicationContext context, String member, String credentialId, Phone phone) {
        storePasskey(context, member, credentialId);
        context.getBean(AccessStore.class).authorize(member, member + "'s phone", phone.identity(), credentialId, "i-test");
        return justSignedIn(context, member, credentialId);
    }
}
