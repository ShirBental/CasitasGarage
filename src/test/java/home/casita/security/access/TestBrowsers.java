package home.casita.security.access;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
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
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import home.casita.security.store.SecurityStore;
import home.casita.security.store.SecurityStore.EnrollmentClaim;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.Cookie;

/**
 * Enrolled browsers for tests: phones whose browsers hold a browser key ({@link DeviceKeys}) signed with the
 * application's own device key, requests as the reverse proxy delivers them (HTTPS), and enrollment and sign-in
 * through the real store and success handler (only the WebAuthn cryptography is skipped: tests hand over a
 * passkey as if Spring had just verified it). Import {@link Config}.
 */
public final class TestBrowsers {

    private TestBrowsers() {}

    /** A phone's browser, holding the browser key for {@code nodeId} (a valid browser-key device id). */
    public record Phone(String nodeId, String name) {
        public DeviceIdentity identity() {
            return new DeviceIdentity(nodeId, "browser " + nodeId.substring(0, 10));
        }
    }

    public static final Phone SHIR_PHONE = new Phone("b-5a17000000000000000000000000000a", "shirs-iphone");
    public static final Phone SHIR_IPAD = new Phone("b-5a17000000000000000000000000000b", "shirs-ipad");
    public static final Phone HUBBY_PHONE = new Phone("b-4b0b000000000000000000000000000c", "hubbys-iphone");

    /** Which phones have lost their browser key (cleared website data, a private tab): their requests carry none. */
    public static final class Browsers {
        private final Set<Phone> forgotten = ConcurrentHashMap.newKeySet();

        public void reset() {
            forgotten.clear();
        }

        public void forget(Phone phone) {
            forgotten.add(phone);
        }

        boolean forgot(Phone phone) {
            return forgotten.contains(phone);
        }
    }

    @TestConfiguration
    public static class Config {
        @Bean
        Browsers browsers() {
            return new Browsers();
        }
    }

    /**
     * A request from the phone's browser through the reverse proxy (Tomcat has already applied X-Forwarded-*):
     * HTTPS, from the phone's address, with its browser key cookie. Needs the request's servlet context to be the
     * application's (MockMvc's is; see {@link #request}).
     */
    public static RequestPostProcessor from(Phone phone) {
        return request -> {
            WebApplicationContext context = applicationOf(request.getServletContext());
            request.setSecure(true);
            request.setScheme("https");
            request.setRemoteAddr("203.0.113.10");
            if (!context.getBean(Browsers.class).forgot(phone)) {
                Cookie key = new Cookie(DeviceKeys.COOKIE, cookieValue(context, phone));
                Cookie[] existing = request.getCookies();
                if (existing == null) {
                    request.setCookies(key);
                } else {
                    Cookie[] all = Arrays.copyOf(existing, existing.length + 1);
                    all[existing.length] = key;
                    request.setCookies(all);
                }
            }
            return request;
        };
    }

    /** The application a (mock) servlet context belongs to. */
    private static WebApplicationContext applicationOf(ServletContext servletContext) {
        WebApplicationContext context = WebApplicationContextUtils.getWebApplicationContext(servletContext);
        if (context != null) return context;
        for (String name : java.util.Collections.list(servletContext.getAttributeNames())) {
            if (servletContext.getAttribute(name) instanceof WebApplicationContext found) return found;
        }
        throw new IllegalStateException("TestBrowsers.from needs a request in the application's servlet context: "
                + "use MockMvc, or TestBrowsers.request(context, ...)");
    }

    /** The phone's browser key cookie value, as the application's device key signs it. */
    public static String cookieValue(ApplicationContext context, Phone phone) {
        return context.getBean(DeviceKeys.class).cookieValueFor(phone.nodeId());
    }

    /** A request built by hand (not through MockMvc) that {@link #from} can still be applied to. */
    public static MockHttpServletRequest request(ApplicationContext context, String method, String uri) {
        return new MockHttpServletRequest(((WebApplicationContext) context).getServletContext(), method, uri);
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
        MockHttpServletRequest request = request(context, "POST", "/login/webauthn");
        request.setSession(session);
        request.setAttribute(AccessSession.SIGNED_IN_CREDENTIAL, credentialId); // as CredentialEvents does, during verification
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
