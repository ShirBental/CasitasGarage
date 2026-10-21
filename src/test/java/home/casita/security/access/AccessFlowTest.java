package home.casita.security.access;

import static home.casita.security.access.TestTailnet.HUBBY_PHONE;
import static home.casita.security.access.TestTailnet.SHIR_IPAD;
import static home.casita.security.access.TestTailnet.SHIR_PHONE;
import static home.casita.security.access.TestTailnet.from;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import home.casita.garage.ActivityLog;
import home.casita.security.FilePasskeyStore;

/**
 * One invite, one device, end to end through the real filter chain, with a pretend tailnet in front: invites,
 * enrollment-only sessions, passkey registration, an existing synced passkey on a second device, cross-device
 * use, and every kind of revocation. The door is the simulated one; nothing here reaches Meross.
 */
@SpringBootTest
@Import(TestTailnet.Config.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AccessFlowTest {

    private static final Path DATA;

    static {
        try {
            DATA = Files.createTempDirectory("casita-access");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("casita.security.data-dir", DATA::toString);
        registry.add("casita.security.family", () -> "shir,hubby");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private FilePasskeyStore passkeys;
    @Autowired
    private AccessStore access;
    @Autowired
    private Invites invites;
    @Autowired
    private ActivityLog activity;
    @Autowired
    private TestTailnet.FakeWhois whois;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** Each test starts with no passkeys or devices (the next context reads an empty data directory). */
    @AfterEach
    void cleanUp() throws IOException {
        Files.deleteIfExists(DATA.resolve("passkeys.json"));
        Files.deleteIfExists(DATA.resolve("devices.json"));
        whois.reset();
    }

    /* ---------- helpers: what the admin script, the phone and Spring's WebAuthn would do ---------- */

    private String adminSecret() throws IOException {
        return Files.readString(DATA.resolve("admin.secret")).trim();
    }

    /** {@code casita-access invite}: returns the invite id and its token (read from the link file). */
    private String[] invite(String member, String label) throws Exception {
        String body = mvc.perform(post("/admin/invites").param("member", member).param("label", label).header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("token");
        Matcher id = Pattern.compile("\"id\":\"(i-[0-9a-f]{8})\"").matcher(body);
        assertThat(id.find()).isTrue();
        Path file = DATA.resolve("invites").resolve(id.group(1) + ".txt");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        String link = Files.readString(file).trim();
        return new String[] {id.group(1), link.substring(link.indexOf("token=") + "token=".length())};
    }

    /** Tapping the invite link's Sign in on a phone: an enrollment-only session. */
    private MockHttpSession redeem(String token, TestTailnet.Phone phone) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login/ott").param("token", token).with(csrf()).with(from(phone)))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/passkey"))
                .andReturn().getRequest().getSession();
    }

    /** Spring's WebAuthn storing a newly registered passkey, inside the registration request. */
    private void registerPasskey(MockHttpSession session, TestTailnet.Phone phone, String credentialId, String member) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webauthn/register");
        request.setSession(session);
        from(phone).postProcessRequest(request);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            PublicKeyCredentialUserEntity user = passkeys.findByUsername(member);
            if (user == null) {
                user = ImmutablePublicKeyCredentialUserEntity.builder().name(member).id(Bytes.random()).displayName(member).build();
                passkeys.save(user);
            }
            passkeys.save(TestTailnet.credential(user, credentialId));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    /** A passkey sign-in completing inside an existing session (Spring keeps its attributes on sign-in). */
    private static void signInWithPasskey(MockHttpSession session, PublicKeyCredentialUserEntity user, String credentialId) {
        WebAuthnAuthentication auth = new WebAuthnAuthentication(user, List.of(new SimpleGrantedAuthority("ROLE_FAMILY")));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(auth));
        session.setAttribute(AccessSession.SIGNED_IN_CREDENTIAL, credentialId);
    }

    private void expectGarage(MockHttpSession session, TestTailnet.Phone phone, int status) throws Exception {
        mvc.perform(get("/api/doors").session(session).with(from(phone))).andExpect(status().is(status));
    }

    /* ---------- enrollment-only sessions ---------- */

    @Test
    void anEnrollmentSessionCanSetUpAPasskeyAndNothingElse() throws Exception {
        MockHttpSession enrolling = redeem(invite("shir", "Shir's iPhone")[1], SHIR_PHONE);

        mvc.perform(get("/passkey").session(enrolling).with(from(SHIR_PHONE))).andExpect(status().isOk());
        mvc.perform(get("/api/doors").session(enrolling).with(from(SHIR_PHONE))).andExpect(status().isForbidden());
        mvc.perform(get("/api/me").session(enrolling).with(from(SHIR_PHONE))).andExpect(status().isForbidden());
        mvc.perform(post("/api/open").session(enrolling).with(from(SHIR_PHONE)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/close").session(enrolling).with(from(SHIR_PHONE)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/").session(enrolling).with(from(SHIR_PHONE))).andExpect(status().isForbidden());
        assertThat(activity.recent(10)).isEmpty();

        // Passkey options: for Shir, discoverable, with user verification (Face ID or passcode) required.
        mvc.perform(post("/webauthn/register/options").session(enrolling).with(from(SHIR_PHONE)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.name").value("shir"))
                .andExpect(jsonPath("$.authenticatorSelection.residentKey").value("required"))
                .andExpect(jsonPath("$.authenticatorSelection.userVerification").value("required"));
    }

    @Test
    void passkeySignInAsksForUserVerification() throws Exception {
        mvc.perform(post("/webauthn/authenticate/options").with(csrf()).with(from(SHIR_PHONE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userVerification").value("required"));
    }

    @Test
    void anInviteIsSingleUse() throws Exception {
        String token = invite("shir", "Shir's iPhone")[1];
        redeem(token, SHIR_PHONE);

        mvc.perform(post("/login/ott").param("token", token).with(csrf()).with(from(SHIR_PHONE)))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void anInviteFromADeviceCasitaCantVerifyIsNotUsedUp() throws Exception {
        String token = invite("shir", "Shir's iPhone")[1];

        // Not through tailscale serve; then a node tailscaled doesn't know; then a tagged node; then a Funnel request.
        mvc.perform(post("/login/ott").param("token", token).with(csrf()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?device-unverified"));
        whois.forget(SHIR_PHONE.address());
        mvc.perform(post("/login/ott").param("token", token).with(csrf()).with(from(SHIR_PHONE)))
                .andExpect(redirectedUrl("/login?device-unverified"));
        whois.put(SHIR_PHONE.address(), new TailscaleWhois.Node(SHIR_PHONE.nodeId(), "server", SHIR_PHONE.login(), true));
        mvc.perform(post("/login/ott").param("token", token).with(csrf()).with(from(SHIR_PHONE)))
                .andExpect(redirectedUrl("/login?device-unverified"));
        whois.reset();
        mvc.perform(post("/login/ott").param("token", token).with(csrf()).with(from(SHIR_PHONE)).header("Tailscale-Funnel-Request", "?1"))
                .andExpect(redirectedUrl("/login?device-unverified"));

        // Still good from the real phone.
        redeem(token, SHIR_PHONE);
    }

    @Test
    void springsPublicTokenGenerationIsOff() throws Exception {
        mvc.perform(post("/ott/generate").param("username", "shir").with(csrf()).with(from(SHIR_PHONE)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200));
        assertThat(invites.pending()).isEmpty();
    }

    /* ---------- registering a passkey: enrollment only, on the invite's device ---------- */

    @Test
    void aNewPasskeyAuthorizesTheInvitesDeviceAndThenTheGarageWorks() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");
        MockHttpSession enrolling = redeem(invite[1], SHIR_PHONE);
        registerPasskey(enrolling, SHIR_PHONE, "c2hpcnMtcGhvbmUta2V5", "shir");

        assertThat(access.find("shir", SHIR_PHONE.nodeId())).get()
                .satisfies(d -> assertThat(d.label()).isEqualTo("Shir's iPhone"))
                .satisfies(d -> assertThat(d.inviteId()).isEqualTo(invite[0]));
        assertThat(invites.enrolling()).isEmpty();

        MockHttpSession session = TestTailnet.justSignedIn(context, "shir", "c2hpcnMtcGhvbmUta2V5");
        expectGarage(session, SHIR_PHONE, 200);
        mvc.perform(post("/api/open").session(session).with(from(SHIR_PHONE)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("MOVING"));
        assertThat(activity.recent(1).getFirst().who()).isEqualTo("shir");
    }

    @Test
    void aPasskeyCantBeRegisteredOutsideALiveEnrollmentOnItsDevice() throws Exception {
        // A signed-in family member: no adding passkeys.
        MockHttpSession family = TestTailnet.signedInOn(context, "shir", "c2hpcnMtZmlyc3Q", SHIR_PHONE);
        mvc.perform(post("/webauthn/register/options").session(family).with(from(SHIR_PHONE)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/webauthn/register").session(family).with(from(SHIR_PHONE)).with(csrf())).andExpect(status().isForbidden());

        // An enrollment, but the request comes from another device.
        MockHttpSession enrolling = redeem(invite("shir", "Shir's iPad")[1], SHIR_IPAD);
        mvc.perform(post("/webauthn/register/options").session(enrolling).with(from(SHIR_PHONE)).with(csrf())).andExpect(status().isForbidden());

        // And if the passkey is saved anyway (a request that slipped past the filters), the store refuses it.
        MockHttpSession again = redeem(invite("shir", "Shir's iPad")[1], SHIR_IPAD);
        assertThatThrownBy(() -> registerPasskey(again, SHIR_PHONE, "c2hpcnMtd3JvbmctZGV2aWNl", "shir")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registerPasskey(again, SHIR_IPAD, "aHViYnlzLWtleQ", "hubby")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registerPasskey(new MockHttpSession(), SHIR_IPAD, "bm8tZW5yb2xsbWVudA", "shir")).isInstanceOf(IllegalStateException.class);
        assertThat(passkeys.credentialsFor("shir")).extracting(c -> c.getCredentialId().toBase64UrlString()).containsExactly("c2hpcnMtZmlyc3Q");
        assertThat(passkeys.credentialsFor("hubby")).isEmpty();
        assertThat(access.find("shir", SHIR_IPAD.nodeId())).isEmpty();
    }

    @Test
    void anEnrollmentEndsIfItMovesToAnotherDevice() throws Exception {
        MockHttpSession enrolling = redeem(invite("shir", "Shir's iPhone")[1], SHIR_PHONE);

        mvc.perform(get("/passkey").session(enrolling).with(from(SHIR_IPAD))).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        assertThat(enrolling.isInvalid()).isTrue();
    }

    /* ---------- an existing synced passkey on another device ---------- */

    @Test
    void anExistingSyncedPasskeyNeedsItsOwnInviteOnEachDevice() throws Exception {
        TestTailnet.signedInOn(context, "shir", "c2hpcnMtc3luY2Vk", SHIR_PHONE);
        PublicKeyCredentialUserEntity shir = passkeys.findByUsername("shir");

        // The same synced passkey on her iPad, without an invite: signed in, but the device isn't authorized.
        MockHttpSession uninvited = new MockHttpSession();
        signInWithPasskey(uninvited, shir, "c2hpcnMtc3luY2Vk");
        expectGarage(uninvited, SHIR_IPAD, 401);
        assertThat(uninvited.isInvalid()).isTrue();

        // With an invite for the iPad: redeem it there, then sign in with the existing passkey.
        String[] invite = invite("shir", "Shir's iPad");
        MockHttpSession session = redeem(invite[1], SHIR_IPAD);
        signInWithPasskey(session, shir, "c2hpcnMtc3luY2Vk");
        expectGarage(session, SHIR_IPAD, 200);
        assertThat(access.find("shir", SHIR_IPAD.nodeId())).get().satisfies(d -> assertThat(d.inviteId()).isEqualTo(invite[0]));
        assertThat(invites.enrolling()).isEmpty();
        assertThat(passkeys.credentialsFor("shir")).hasSize(1); // no new passkey: the device was authorized, not a credential
    }

    @Test
    void someoneElsesInviteDoesntAuthorizeYourDevice() throws Exception {
        TestTailnet.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        PublicKeyCredentialUserEntity shir = passkeys.findByUsername("shir");

        // Hubby's invite, redeemed on Shir's iPad, then Shir signs in there.
        MockHttpSession session = redeem(invite("hubby", "Hubby's iPhone")[1], SHIR_IPAD);
        signInWithPasskey(session, shir, "c2hpcnMta2V5");
        expectGarage(session, SHIR_IPAD, 401);
        assertThat(access.find("shir", SHIR_IPAD.nodeId())).isEmpty();
        assertThat(access.find("hubby", SHIR_IPAD.nodeId())).isEmpty();
    }

    /* ---------- cross-device use ---------- */

    @Test
    void aSessionCantMoveToAnotherDevice() throws Exception {
        MockHttpSession session = TestTailnet.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        // Even another of Shir's authorized devices: the session is tied to the one it signed in on.
        access.authorize("shir", "Shir's iPad", SHIR_IPAD.identity(), "c2hpcnMta2V5", "i-test");
        expectGarage(session, SHIR_PHONE, 200);

        expectGarage(session, SHIR_IPAD, 401);
        assertThat(session.isInvalid()).isTrue();
        expectGarage(session, SHIR_PHONE, 401);
    }

    @Test
    void aSignInOnAnotherMembersDeviceIsRefused() throws Exception {
        TestTailnet.signedInOn(context, "hubby", "aHViYnlzLWtleQ", HUBBY_PHONE);
        MockHttpSession shir = TestTailnet.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        MockHttpSession onHubbysPhone = TestTailnet.justSignedIn(context, "shir", "c2hpcnMta2V5");

        expectGarage(onHubbysPhone, HUBBY_PHONE, 401);
        expectGarage(shir, SHIR_PHONE, 200);
    }

    @Test
    void aPasskeySessionNeedsTheRequestToComeThroughTheTailnet() throws Exception {
        MockHttpSession session = TestTailnet.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);

        mvc.perform(get("/api/doors").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    /* ---------- revocation: on the next request, and for good ---------- */

    @Test
    void revokingTheDeviceEndsItsSession() throws Exception {
        MockHttpSession session = TestTailnet.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        expectGarage(session, SHIR_PHONE, 200);
        String id = access.find("shir", SHIR_PHONE.nodeId()).orElseThrow().id();

        mvc.perform(delete("/admin/devices/" + id).header(LocalAdmin.HEADER, adminSecret())).andExpect(jsonPath("$.revoked").value(true));

        mvc.perform(post("/api/open").session(session).with(from(SHIR_PHONE)).with(csrf())).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        // Signing in again with the passkey doesn't help: the device needs a new invite.
        expectGarage(TestTailnet.justSignedIn(context, "shir", "c2hpcnMta2V5"), SHIR_PHONE, 401);
        assertThat(activity.recent(10)).isEmpty();
        assertThat(access.all()).singleElement().satisfies(d -> assertThat(d.revokedAt()).isNotNull());
    }

    @Test
    void revokingThePasskeyEndsItsSession() throws Exception {
        MockHttpSession session = TestTailnet.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        expectGarage(session, SHIR_PHONE, 200);

        mvc.perform(delete("/admin/credentials/c2hpcnMta2V5").header(LocalAdmin.HEADER, adminSecret())).andExpect(jsonPath("$.revoked").value(true));

        expectGarage(session, SHIR_PHONE, 401);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void revokingAMemberEndsEverythingTheyHave() throws Exception {
        MockHttpSession session = TestTailnet.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        MockHttpSession hubby = TestTailnet.signedInOn(context, "hubby", "aHViYnlzLWtleQ", HUBBY_PHONE);
        String pendingToken = invite("shir", "Shir's iPad")[1];

        mvc.perform(delete("/admin/members/shir").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(jsonPath("$.devices").value(1)).andExpect(jsonPath("$.credentials").value(1)).andExpect(jsonPath("$.invites").value(1));

        expectGarage(session, SHIR_PHONE, 401);
        mvc.perform(post("/login/ott").param("token", pendingToken).with(csrf()).with(from(SHIR_IPAD))).andExpect(redirectedUrl("/login?error"));
        expectGarage(hubby, HUBBY_PHONE, 200); // nobody else is affected
    }

    @Test
    void aRevokedInviteCantBeRedeemed() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");

        mvc.perform(delete("/admin/invites/" + invite[0]).header(LocalAdmin.HEADER, adminSecret())).andExpect(jsonPath("$.revoked").value(true));

        assertThat(DATA.resolve("invites").resolve(invite[0] + ".txt")).doesNotExist();
        mvc.perform(post("/login/ott").param("token", invite[1]).with(csrf()).with(from(SHIR_PHONE))).andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void revokingAnInviteEndsTheEnrollmentInProgress() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");
        MockHttpSession enrolling = redeem(invite[1], SHIR_PHONE);

        mvc.perform(delete("/admin/invites/" + invite[0]).header(LocalAdmin.HEADER, adminSecret()));

        mvc.perform(get("/passkey").session(enrolling).with(from(SHIR_PHONE))).andExpect(redirectedUrl("/login"));
        assertThat(enrolling.isInvalid()).isTrue();
        assertThatThrownBy(() -> registerPasskey(enrolling, SHIR_PHONE, "bGF0ZQ", "shir")).isInstanceOf(IllegalStateException.class);
    }

    /* ---------- administration: on the server only ---------- */

    @Test
    void administrationIsLocalAndNeedsTheSecret() throws Exception {
        mvc.perform(get("/admin/access")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/access").header(LocalAdmin.HEADER, "wrong")).andExpect(status().isForbidden());
        // Through tailscale serve, even with the secret, even signed in as family.
        MockHttpSession family = TestTailnet.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        mvc.perform(get("/admin/access").session(family).with(from(SHIR_PHONE)).header(LocalAdmin.HEADER, adminSecret())).andExpect(status().isForbidden());
        mvc.perform(get("/admin/access").header(LocalAdmin.HEADER, adminSecret()).header("X-Forwarded-For", "100.64.0.11")).andExpect(status().isForbidden());

        mvc.perform(get("/admin/access").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].name").value("shir"))
                .andExpect(jsonPath("$.members[0].devices[0].nodeId").value(SHIR_PHONE.nodeId()))
                .andExpect(jsonPath("$.members[0].credentials[0].syncable").value(true));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(DATA.resolve("admin.secret")))).isEqualTo("rw-------");
    }

    @Test
    void invitesAreForFamilyAndCarryALabel() throws Exception {
        mvc.perform(post("/admin/invites").param("member", "stranger").param("label", "x").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/invites").param("member", "shir").param("label", " ").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/invites").param("member", "shir").param("label", "x".repeat(61)).header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(status().isBadRequest());
        assertThat(invites.pending()).isEmpty();
    }
}
