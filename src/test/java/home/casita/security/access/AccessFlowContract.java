package home.casita.security.access;

import static home.casita.security.access.TestBrowsers.HUBBY_PHONE;
import static home.casita.security.access.TestBrowsers.SHIR_IPAD;
import static home.casita.security.access.TestBrowsers.SHIR_PHONE;
import static home.casita.security.access.TestBrowsers.from;
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
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import home.casita.garage.ActivityLog;
import home.casita.security.StorePasskeys;
import home.casita.security.store.SecurityStore;
import home.casita.security.store.SecurityStore.Invitation;

/**
 * One invite, one device, end to end through the real filter chain, with enrolled test browsers in front, run
 * against each security store (FileAccessFlowTest, DynamoAccessFlowTest): invites, enrollment-only sessions,
 * passkey registration, an existing synced passkey on a second device, cross-device use, and every kind of
 * revocation. The door is the simulated one; nothing here reaches Meross.
 */
abstract class AccessFlowContract {

    protected static final Path DATA;

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
    protected WebApplicationContext context;
    @Autowired
    protected StorePasskeys passkeys;
    @Autowired
    protected SecurityStore store;
    @Autowired
    protected ActivityLog activity;
    @Autowired
    protected TestBrowsers.Browsers browsers;

    protected MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** Each test starts empty (the next context reads an empty data directory, or a new DynamoDB Local). */
    @AfterEach
    void cleanUp() throws IOException {
        Files.deleteIfExists(DATA.resolve("security.json"));
        browsers.reset();
    }

    /* ---------- helpers: what the admin script, the phone and Spring's WebAuthn would do ---------- */

    protected String adminSecret() throws IOException {
        return Files.readString(DATA.resolve("admin.secret")).trim();
    }

    /** {@code casita-access invite}: returns the invite id and its token (read from the link file). */
    protected String[] invite(String member, String label) throws Exception {
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
    protected MockHttpSession redeem(String token, TestBrowsers.Phone phone) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login/ott").param("token", token).with(csrf()).with(from(phone)))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/passkey"))
                .andReturn().getRequest().getSession();
    }

    /** Spring's WebAuthn storing a newly registered (verified) passkey, inside the registration request. */
    protected void registerPasskey(MockHttpSession session, TestBrowsers.Phone phone, String credentialId, String member) {
        MockHttpServletRequest request = TestBrowsers.request(context, "POST", "/webauthn/register");
        request.setSession(session);
        from(phone).postProcessRequest(request);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            passkeys.save(TestBrowsers.credential(TestBrowsers.user(store, member), credentialId));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    protected void expectGarage(MockHttpSession session, TestBrowsers.Phone phone, int status) throws Exception {
        mvc.perform(get("/api/doors").session(session).with(from(phone))).andExpect(status().is(status));
    }

    protected Invitation invitation(String inviteId) {
        return store.invitationById(inviteId).orElseThrow();
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
        mvc.perform(post("/api/resume").session(enrolling).with(from(SHIR_PHONE)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/status").session(enrolling).with(from(SHIR_PHONE))).andExpect(status().isForbidden());
        mvc.perform(get("/api/activity").session(enrolling).with(from(SHIR_PHONE))).andExpect(status().isForbidden());
        mvc.perform(get("/api/brand").session(enrolling).with(from(SHIR_PHONE))).andExpect(status().isForbidden());
        mvc.perform(get("/").session(enrolling).with(from(SHIR_PHONE))).andExpect(status().isForbidden());
        assertThat(activity.recent(10)).isEmpty();

        // Passkey options: for Shir, discoverable, with user verification (Face ID or passcode) required.
        mvc.perform(post("/webauthn/register/options").session(enrolling).with(from(SHIR_PHONE)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.name").value("shir"))
                .andExpect(jsonPath("$.authenticatorSelection.residentKey").value("required"))
                .andExpect(jsonPath("$.authenticatorSelection.userVerification").value("required"));
    }

    /* ---------- browser keys: what stands in for the device ---------- */

    @Test
    void aBrowserWithoutAKeyIsGivenOneWhenItRedeemsAnInviteAndThatKeyIsTheDeviceEnrolled() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");
        MvcResult redeemed = mvc.perform(post("/login/ott").param("token", invite[1]).with(csrf()).secure(true))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/passkey")).andReturn();
        String setCookie = redeemed.getResponse().getHeaders("Set-Cookie").stream()
                .filter(c -> c.startsWith(DeviceKeys.COOKIE + "=")).findFirst().orElseThrow();
        assertThat(setCookie).contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax", "Max-Age=").doesNotContain("Domain=");
        String value = setCookie.substring((DeviceKeys.COOKIE + "=").length(), setCookie.indexOf(';'));
        String deviceId = value.substring(0, value.indexOf('.'));
        assertThat(deviceId).matches("b-[0-9a-f]{32}");
        assertThat(invitation(invite[0]).nodeId()).isEqualTo(deviceId);

        // Face ID set up in that same browser: its key is the device now authorized for Shir.
        TestBrowsers.Phone browser = new TestBrowsers.Phone(deviceId, "a new browser");
        registerPasskey((MockHttpSession) redeemed.getRequest().getSession(), browser, "bmV3LWtleQ", "shir");
        assertThat(store.device(deviceId)).get().satisfies(d -> assertThat(d.activeFor("shir")).isTrue());

        MockHttpSession session = new MockHttpSession();
        assertThat(TestBrowsers.signIn(context, session, "shir", "bmV3LWtleQ", browser)).isNull();
        expectGarage(session, browser, 200);
        // The same synced passkey in a browser without that key gets no session.
        assertThat(TestBrowsers.signIn(context, new MockHttpSession(), "shir", "bmV3LWtleQ", SHIR_IPAD)).isEqualTo("device not authorized");
    }

    @Test
    void aForgedBrowserKeyOrSpoofedIdentityHeadersAreNoDevice() throws Exception {
        MockHttpSession session = TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        expectGarage(session, SHIR_PHONE, 200);

        // The device id as listed by the admin, with a MAC it can't have: no device, and the session ends.
        browsers.forget(SHIR_PHONE);
        mvc.perform(get("/api/doors").session(session).with(from(SHIR_PHONE))
                        .cookie(new jakarta.servlet.http.Cookie(DeviceKeys.COOKIE, SHIR_PHONE.nodeId() + ".AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")))
                .andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        browsers.reset();

        // Tailscale's identity headers and a forwarded address, without the browser key: nothing.
        MockHttpSession again = new MockHttpSession();
        assertThat(TestBrowsers.signIn(context, again, "shir", "c2hpcnMta2V5", SHIR_PHONE)).isNull();
        mvc.perform(get("/api/doors").session(again).secure(true)
                        .header("Tailscale-User-Login", "shir@example.com").header("X-Forwarded-For", "100.64.0.11"))
                .andExpect(status().isUnauthorized());
        assertThat(again.isInvalid()).isTrue();

        // A valid key over plain http (not through the proxy) isn't accepted either.
        MockHttpSession third = new MockHttpSession();
        assertThat(TestBrowsers.signIn(context, third, "shir", "c2hpcnMta2V5", SHIR_PHONE)).isNull();
        mvc.perform(get("/api/doors").session(third).with(from(SHIR_PHONE)).with(r -> { r.setSecure(false); r.setScheme("http"); return r; }))
                .andExpect(status().isUnauthorized());
        assertThat(activity.recent(10)).isEmpty();
    }

    @Test
    void passkeySignInAsksForUserVerification() throws Exception {
        mvc.perform(post("/webauthn/authenticate/options").with(csrf()).with(from(SHIR_PHONE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userVerification").value("required"));
    }

    @Test
    void anInviteIsSingleUseAndRecordedAsConsumed() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");
        redeem(invite[1], SHIR_PHONE);

        mvc.perform(post("/login/ott").param("token", invite[1]).with(csrf()).with(from(SHIR_PHONE)))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?invite-failed"));
        assertThat(invitation(invite[0])).satisfies(i -> {
            assertThat(i.status()).isEqualTo(SecurityStore.InvitationStatus.CONSUMED);
            assertThat(i.nodeId()).isEqualTo(SHIR_PHONE.nodeId());
        });
    }

    @Test
    void anInviteFromADeviceCasitaCantVerifyIsNotUsedUp() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");
        String token = invite[1];

        // Not over HTTPS (not through the reverse proxy): the browser couldn't keep a browser key, so nothing is
        // redeemed and no key is handed out; Tailscale identity headers mean nothing any more.
        mvc.perform(post("/login/ott").param("token", token).with(csrf()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/login?device-unverified"))
                .andExpect(result -> assertThat(result.getResponse().getHeaders("Set-Cookie")).noneMatch(c -> c.startsWith(DeviceKeys.COOKIE)));
        mvc.perform(post("/login/ott").param("token", token).with(csrf())
                        .header("Tailscale-User-Login", "shir@example.com").header("X-Forwarded-For", "100.64.0.11"))
                .andExpect(redirectedUrl("/login?device-unverified"));
        assertThat(invitation(invite[0]).status()).isEqualTo(SecurityStore.InvitationStatus.PENDING);

        redeem(token, SHIR_PHONE); // still good from the real phone
    }

    @Test
    void anInviteOnADeviceThatIsAlreadySetUpIsNotUsedUp() throws Exception {
        TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        String[] invite = invite("hubby", "Hubby's iPhone");

        mvc.perform(post("/login/ott").param("token", invite[1]).with(csrf()).with(from(SHIR_PHONE)))
                .andExpect(redirectedUrl("/login?device-already-set-up"));
        assertThat(invitation(invite[0]).status()).isEqualTo(SecurityStore.InvitationStatus.PENDING);
    }

    @Test
    void springsPublicTokenGenerationIsOff() throws Exception {
        mvc.perform(post("/ott/generate").param("username", "shir").with(csrf()).with(from(SHIR_PHONE)))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200));
        assertThat(store.invitationsOf("shir")).isEmpty();
    }

    /* ---------- registering a passkey: enrollment only, on the invite's device ---------- */

    @Test
    void aNewPasskeyAuthorizesTheInvitesDeviceAndThenTheGarageWorks() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");
        MockHttpSession enrolling = redeem(invite[1], SHIR_PHONE);
        registerPasskey(enrolling, SHIR_PHONE, "c2hpcnMtcGhvbmUta2V5", "shir");

        assertThat(store.device(SHIR_PHONE.nodeId())).get().satisfies(d -> {
            assertThat(d.activeFor("shir")).isTrue();
            assertThat(d.label()).isEqualTo("Shir's iPhone");
            assertThat(d.inviteId()).isEqualTo(invite[0]);
        });
        assertThat(invitation(invite[0]).enrolledAt()).isNotNull();
        // The enrollment session is over: it can't set up another passkey.
        mvc.perform(get("/passkey").session(enrolling).with(from(SHIR_PHONE))).andExpect(redirectedUrl("/login"));

        MockHttpSession session = new MockHttpSession();
        assertThat(TestBrowsers.signIn(context, session, "shir", "c2hpcnMtcGhvbmUta2V5", SHIR_PHONE)).isNull();
        expectGarage(session, SHIR_PHONE, 200);
        mvc.perform(post("/api/open").session(session).with(from(SHIR_PHONE)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("MOVING"));
        assertThat(activity.recent(1).getFirst().who()).isEqualTo("shir");
    }

    @Test
    void aPasskeyCantBeRegisteredOutsideALiveEnrollmentOnItsDevice() throws Exception {
        // A signed-in family member: no adding passkeys.
        MockHttpSession family = TestBrowsers.signedInOn(context, "shir", "c2hpcnMtZmlyc3Q", SHIR_PHONE);
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
        assertThat(store.passkeysOf("shir")).extracting(SecurityStore.Passkey::id).containsExactly("c2hpcnMtZmlyc3Q");
        assertThat(store.passkeysOf("hubby")).isEmpty();
        assertThat(store.device(SHIR_IPAD.nodeId())).isEmpty();
    }

    @Test
    void anEnrollmentEndsIfItMovesToAnotherDevice() throws Exception {
        MockHttpSession enrolling = redeem(invite("shir", "Shir's iPhone")[1], SHIR_PHONE);

        mvc.perform(get("/passkey").session(enrolling).with(from(SHIR_IPAD))).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        assertThat(enrolling.isInvalid()).isTrue();
    }

    @Test
    void passkeysCantBeDeletedThroughSpring() {
        assertThatThrownBy(() -> passkeys.delete(Bytes.random())).isInstanceOf(UnsupportedOperationException.class);
    }

    /* ---------- an existing synced passkey on another device ---------- */

    @Test
    void anExistingSyncedPasskeyNeedsItsOwnInviteOnEachDevice() throws Exception {
        TestBrowsers.signedInOn(context, "shir", "c2hpcnMtc3luY2Vk", SHIR_PHONE);

        // The same synced passkey on her iPad, without an invite: the sign-in itself is refused.
        MockHttpSession uninvited = new MockHttpSession();
        assertThat(TestBrowsers.signIn(context, uninvited, "shir", "c2hpcnMtc3luY2Vk", SHIR_IPAD)).isEqualTo("device not authorized");
        assertThat(uninvited.isInvalid()).isTrue();

        // With an invite for the iPad: redeem it there, then sign in with the existing passkey there.
        String[] invite = invite("shir", "Shir's iPad");
        MockHttpSession session = redeem(invite[1], SHIR_IPAD);
        assertThat(TestBrowsers.signIn(context, session, "shir", "c2hpcnMtc3luY2Vk", SHIR_IPAD)).isNull();
        expectGarage(session, SHIR_IPAD, 200);
        assertThat(store.device(SHIR_IPAD.nodeId())).get().satisfies(d -> assertThat(d.inviteId()).isEqualTo(invite[0]));
        assertThat(invitation(invite[0]).enrolledAt()).isNotNull();
        assertThat(store.passkeysOf("shir")).hasSize(1); // no new passkey: the device was authorized, not a credential
    }

    @Test
    void someoneElsesInviteDoesntAuthorizeYourDevice() throws Exception {
        TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);

        // Hubby's invite, redeemed on Shir's iPad, then Shir signs in there.
        String[] invite = invite("hubby", "Hubby's iPhone");
        MockHttpSession session = redeem(invite[1], SHIR_IPAD);
        assertThat(TestBrowsers.signIn(context, session, "shir", "c2hpcnMta2V5", SHIR_IPAD)).isNotNull();
        assertThat(store.device(SHIR_IPAD.nodeId())).isEmpty();
        assertThat(invitation(invite[0]).enrolledAt()).isNull();
    }

    /* ---------- cross-device use ---------- */

    @Test
    void aSessionCantMoveToAnotherDevice() throws Exception {
        MockHttpSession session = TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        // Even to another of Shir's authorized devices: the session is tied to the one it signed in on.
        TestBrowsers.enrollExistingPasskey(context, "shir", "c2hpcnMta2V5", SHIR_IPAD);
        expectGarage(session, SHIR_PHONE, 200);

        expectGarage(session, SHIR_IPAD, 401);
        assertThat(session.isInvalid()).isTrue();
        expectGarage(session, SHIR_PHONE, 401);
    }

    @Test
    void aSignInOnAnotherMembersDeviceIsRefused() throws Exception {
        TestBrowsers.signedInOn(context, "hubby", "aHViYnlzLWtleQ", HUBBY_PHONE);
        MockHttpSession shir = TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);

        assertThat(TestBrowsers.signIn(context, new MockHttpSession(), "shir", "c2hpcnMta2V5", HUBBY_PHONE)).isEqualTo("device not authorized");
        expectGarage(shir, SHIR_PHONE, 200);
    }

    @Test
    void aPasskeySessionNeedsTheRequestToCarryItsBrowserKeyOverHttps() throws Exception {
        MockHttpSession session = TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);

        mvc.perform(get("/api/doors").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    /* ---------- revocation: on the next request, and for good ---------- */

    @Test
    void revokingTheDeviceEndsItsSession() throws Exception {
        MockHttpSession session = TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        expectGarage(session, SHIR_PHONE, 200);

        mvc.perform(delete("/admin/devices/" + SHIR_PHONE.nodeId()).header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(jsonPath("$.revoked").value(true));

        mvc.perform(post("/api/open").session(session).with(from(SHIR_PHONE)).with(csrf())).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        // Signing in again with the passkey doesn't help: the device needs a new invite.
        assertThat(TestBrowsers.signIn(context, new MockHttpSession(), "shir", "c2hpcnMta2V5", SHIR_PHONE)).isEqualTo("device not authorized");
        assertThat(activity.recent(10)).isEmpty();
        assertThat(store.device(SHIR_PHONE.nodeId())).get().satisfies(d -> assertThat(d.status()).isEqualTo(SecurityStore.Status.REVOKED));
    }

    @Test
    void aRevokedDeviceIsReleasedOnlyExplicitly() throws Exception {
        TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        mvc.perform(post("/admin/devices/" + SHIR_PHONE.nodeId() + "/release").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(jsonPath("$.released").value(false)); // active: revoke first
        mvc.perform(delete("/admin/devices/" + SHIR_PHONE.nodeId()).header(LocalAdmin.HEADER, adminSecret()));
        mvc.perform(post("/admin/devices/" + SHIR_PHONE.nodeId() + "/release").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(jsonPath("$.released").value(true));
    }

    @Test
    void revokingThePasskeyEndsItsSession() throws Exception {
        MockHttpSession session = TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        expectGarage(session, SHIR_PHONE, 200);

        mvc.perform(delete("/admin/credentials/c2hpcnMta2V5").header(LocalAdmin.HEADER, adminSecret())).andExpect(jsonPath("$.revoked").value(true));

        expectGarage(session, SHIR_PHONE, 401);
        assertThat(session.isInvalid()).isTrue();
        assertThat(passkeys.findByCredentialId(Bytes.fromBase64("c2hpcnMta2V5"))).isNull(); // can't even start a sign-in
    }

    @Test
    void revokingAMemberEndsEverythingTheyHave() throws Exception {
        MockHttpSession session = TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        MockHttpSession hubby = TestBrowsers.signedInOn(context, "hubby", "aHViYnlzLWtleQ", HUBBY_PHONE);
        String pendingToken = invite("shir", "Shir's iPad")[1];

        mvc.perform(delete("/admin/members/shir").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.devices").value(1)).andExpect(jsonPath("$.credentials").value(1)).andExpect(jsonPath("$.invites").value(1));

        expectGarage(session, SHIR_PHONE, 401);
        mvc.perform(post("/login/ott").param("token", pendingToken).with(csrf()).with(from(SHIR_IPAD))).andExpect(redirectedUrl("/login?invite-failed"));
        mvc.perform(post("/admin/invites").param("member", "shir").param("label", "again").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(status().isBadRequest());
        expectGarage(hubby, HUBBY_PHONE, 200); // nobody else is affected
    }

    @Test
    void aRevokedMemberIsRefusedEvenBeforeCleanup() throws Exception {
        MockHttpSession session = TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        store.revokeMember("shir"); // the base record only: devices and passkeys still say ACTIVE

        expectGarage(session, SHIR_PHONE, 401);
        assertThat(store.device(SHIR_PHONE.nodeId())).get().satisfies(d -> assertThat(d.status()).isEqualTo(SecurityStore.Status.ACTIVE));
        assertThat(TestBrowsers.signIn(context, new MockHttpSession(), "shir", "c2hpcnMta2V5", SHIR_PHONE)).isEqualTo("member revoked");
    }

    @Test
    void aRevokedInviteCantBeRedeemed() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");

        mvc.perform(delete("/admin/invites/" + invite[0]).header(LocalAdmin.HEADER, adminSecret())).andExpect(jsonPath("$.revoked").value(true));

        assertThat(DATA.resolve("invites").resolve(invite[0] + ".txt")).doesNotExist();
        mvc.perform(post("/login/ott").param("token", invite[1]).with(csrf()).with(from(SHIR_PHONE))).andExpect(redirectedUrl("/login?invite-failed"));
        assertThat(invitation(invite[0]).status()).isEqualTo(SecurityStore.InvitationStatus.REVOKED);
    }

    @Test
    void revokingAnInviteEndsTheEnrollmentInProgress() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");
        MockHttpSession enrolling = redeem(invite[1], SHIR_PHONE);

        mvc.perform(delete("/admin/invites/" + invite[0]).header(LocalAdmin.HEADER, adminSecret()));

        // Registration, had it started before the revocation, can't finish: the transaction checks the invitation.
        MockHttpSession copy = new MockHttpSession();
        copy.setAttribute(AccessSession.ENROLLMENT, enrolling.getAttribute(AccessSession.ENROLLMENT));
        assertThatThrownBy(() -> registerPasskey(copy, SHIR_PHONE, "bGF0ZQ", "shir")).isInstanceOf(IllegalStateException.class);
        mvc.perform(get("/passkey").session(enrolling).with(from(SHIR_PHONE))).andExpect(redirectedUrl("/login"));
        assertThat(enrolling.isInvalid()).isTrue();
        assertThat(store.device(SHIR_PHONE.nodeId())).isEmpty();
    }

    /* ---------- real WebAuthn: real keys and signatures through Spring's verification ---------- */

    protected String optionsJson(String path, MockHttpSession session, TestBrowsers.Phone phone) throws Exception {
        return mvc.perform(post(path).session(session).with(from(phone)).with(csrf())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * The phone's browser registering a passkey in its enrollment session: options, then the attestation.
     * Returns the HTTP status; a refusal must be a clean {@code 400 {"success":false}} (RegistrationErrorFilter),
     * so an exception escaping fails the test.
     */
    protected int realRegistration(SoftAuthenticator passkey, MockHttpSession enrolling, TestBrowsers.Phone phone) throws Exception {
        String body = passkey.register(optionsJson("/webauthn/register/options", enrolling, phone), "iCloud Keychain");
        MvcResult r = mvc.perform(post("/webauthn/register").session(enrolling).with(from(phone)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        String json = r.getResponse().getContentAsString();
        if (r.getResponse().getStatus() == 200) assertThat(json).contains("\"success\":true");
        else assertThat(json).isEqualTo("{\"success\":false}");
        return r.getResponse().getStatus();
    }

    /** The phone's browser signing in with a passkey: options, then the assertion. Returns the HTTP status. */
    protected int realSignIn(SoftAuthenticator passkey, MockHttpSession session, TestBrowsers.Phone phone) throws Exception {
        String body = passkey.assertion(optionsJson("/webauthn/authenticate/options", session, phone));
        return mvc.perform(post("/login/webauthn").session(session).with(from(phone)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
    }

    @Test
    void aRealPasskeyEnrollsTheInvitesPhoneAndThenSignsIn() throws Exception {
        SoftAuthenticator iphone = new SoftAuthenticator();
        MockHttpSession enrolling = redeem(invite("shir", "Shir's iPhone")[1], SHIR_PHONE);

        assertThat(realRegistration(iphone, enrolling, SHIR_PHONE)).isEqualTo(200);
        assertThat(store.device(SHIR_PHONE.nodeId())).get().satisfies(d -> {
            assertThat(d.activeFor("shir")).isTrue();
            assertThat(d.credentialId()).isEqualTo(iphone.credentialId());
        });

        MockHttpSession session = new MockHttpSession();
        assertThat(realSignIn(iphone, session, SHIR_PHONE)).isEqualTo(200);
        expectGarage(session, SHIR_PHONE, 200);
        mvc.perform(post("/api/open").session(session).with(from(SHIR_PHONE)).with(csrf())).andExpect(jsonPath("$.outcome").value("MOVING"));
    }

    @Test
    void aRegistrationWithoutUserVerificationIsRejectedAndAuthorizesNothing() throws Exception {
        SoftAuthenticator noFaceId = new SoftAuthenticator();
        noFaceId.skipUserVerification = true;
        String[] invite = invite("shir", "Shir's iPhone");
        MockHttpSession enrolling = redeem(invite[1], SHIR_PHONE);

        assertThat(realRegistration(noFaceId, enrolling, SHIR_PHONE)).isEqualTo(400);

        assertThat(store.passkey(Bytes.fromBase64(noFaceId.credentialId()))).isEmpty();
        assertThat(store.device(SHIR_PHONE.nodeId())).isEmpty();
        assertThat(invitation(invite[0]).enrolledAt()).isNull();
    }

    @Test
    void aRegistrationForAnotherOriginIsRejected() throws Exception {
        SoftAuthenticator phished = new SoftAuthenticator();
        phished.origin = "https://casita.example-phish.net";
        MockHttpSession enrolling = redeem(invite("shir", "Shir's iPhone")[1], SHIR_PHONE);

        assertThat(realRegistration(phished, enrolling, SHIR_PHONE)).isEqualTo(400);
        assertThat(store.device(SHIR_PHONE.nodeId())).isEmpty();
    }

    @Test
    void aRealSyncedPasskeyAloneGetsANewDeviceNothing() throws Exception {
        SoftAuthenticator synced = new SoftAuthenticator(); // the same key on her iPhone and, through iCloud, her iPad
        assertThat(realRegistration(synced, redeem(invite("shir", "Shir's iPhone")[1], SHIR_PHONE), SHIR_PHONE)).isEqualTo(200);

        MockHttpSession ipad = new MockHttpSession();
        assertThat(realSignIn(synced, ipad, SHIR_IPAD)).isEqualTo(401);
        expectGarage(ipad, SHIR_IPAD, 401);
        assertThat(store.device(SHIR_IPAD.nodeId())).isEmpty();
    }

    @Test
    void aSyncedPasskeyAuthorizesTheInvitedDeviceOnlyAfterItsAssertionVerifies() throws Exception {
        SoftAuthenticator synced = new SoftAuthenticator();
        assertThat(realRegistration(synced, redeem(invite("shir", "Shir's iPhone")[1], SHIR_PHONE), SHIR_PHONE)).isEqualTo(200);
        String[] invite = invite("shir", "Shir's iPad");
        MockHttpSession enrolling = redeem(invite[1], SHIR_IPAD);

        // A forged assertion inside the iPad's live enrollment: refused before anything is authorized.
        synced.badSignature = true;
        assertThat(realSignIn(synced, enrolling, SHIR_IPAD)).isEqualTo(401);
        assertThat(store.device(SHIR_IPAD.nodeId())).isEmpty();
        assertThat(invitation(invite[0]).enrolledAt()).isNull();

        // Without user verification: refused the same way.
        synced.badSignature = false;
        synced.skipUserVerification = true;
        assertThat(realSignIn(synced, enrolling, SHIR_IPAD)).isEqualTo(401);
        assertThat(store.device(SHIR_IPAD.nodeId())).isEmpty();

        // The real thing, with Face ID: now, and only now, the iPad is authorized.
        synced.skipUserVerification = false;
        assertThat(realSignIn(synced, enrolling, SHIR_IPAD)).isEqualTo(200);
        assertThat(store.device(SHIR_IPAD.nodeId())).get().satisfies(d -> assertThat(d.inviteId()).isEqualTo(invite[0]));
        expectGarage(enrolling, SHIR_IPAD, 200);
    }

    @Test
    void aSignInWithoutUserVerificationIsRejectedEvenOnAnAuthorizedPhone() throws Exception {
        SoftAuthenticator iphone = new SoftAuthenticator();
        assertThat(realRegistration(iphone, redeem(invite("shir", "Shir's iPhone")[1], SHIR_PHONE), SHIR_PHONE)).isEqualTo(200);

        iphone.skipUserVerification = true;
        MockHttpSession session = new MockHttpSession();
        assertThat(realSignIn(iphone, session, SHIR_PHONE)).isEqualTo(401);
        expectGarage(session, SHIR_PHONE, 401);
    }

    @Test
    void aReplayedAssertionIsRejected() throws Exception {
        SoftAuthenticator iphone = new SoftAuthenticator();
        assertThat(realRegistration(iphone, redeem(invite("shir", "Shir's iPhone")[1], SHIR_PHONE), SHIR_PHONE)).isEqualTo(200);
        MockHttpSession first = new MockHttpSession();
        String body = iphone.assertion(optionsJson("/webauthn/authenticate/options", first, SHIR_PHONE));
        mvc.perform(post("/login/webauthn").session(first).with(from(SHIR_PHONE)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        // The same captured assertion, in a new session with its own (different) challenge.
        MockHttpSession replay = new MockHttpSession();
        optionsJson("/webauthn/authenticate/options", replay, SHIR_PHONE);
        mvc.perform(post("/login/webauthn").session(replay).with(from(SHIR_PHONE)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        expectGarage(replay, SHIR_PHONE, 401);
    }

    @Test
    void aRevokedRealPasskeyCantSignInAgain() throws Exception {
        SoftAuthenticator iphone = new SoftAuthenticator();
        assertThat(realRegistration(iphone, redeem(invite("shir", "Shir's iPhone")[1], SHIR_PHONE), SHIR_PHONE)).isEqualTo(200);
        MockHttpSession session = new MockHttpSession();
        assertThat(realSignIn(iphone, session, SHIR_PHONE)).isEqualTo(200);

        mvc.perform(delete("/admin/credentials/" + iphone.credentialId()).header(LocalAdmin.HEADER, adminSecret()));

        expectGarage(session, SHIR_PHONE, 401);
        assertThat(realSignIn(iphone, new MockHttpSession(), SHIR_PHONE)).isEqualTo(401);
    }

    /* ---------- path variants: nothing slips past the guards ---------- */

    @Test
    void pathVariantsCantRedeemAnInviteFromAnUnverifiedDevice() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");
        for (String path : new String[] {"/login/ott/", "/login/ott;x=y", "/login//ott", "/LOGIN/OTT", "/login/./ott"}) {
            mvc.perform(post(path).param("token", invite[1]).with(csrf()));
        }
        assertThat(invitation(invite[0]).status()).isEqualTo(SecurityStore.InvitationStatus.PENDING);
    }

    @Test
    void pathVariantsCantGiveASignedInMemberPasskeyOptions() throws Exception {
        MockHttpSession family = TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        for (String path : new String[] {"/webauthn/register/options/", "/webauthn/register/", "/webauthn/register;x=y"}) {
            MvcResult r = mvc.perform(post(path).session(family).with(from(SHIR_PHONE)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
            assertThat(r.getResponse().getContentAsString()).as(path).doesNotContain("challenge").doesNotContain("\"success\":true");
        }
        assertThat(store.passkeysOf("shir")).hasSize(1);
    }

    /* ---------- M1: revoking an invitation says what happened, and never claims a success it didn't have ---------- */

    private org.springframework.test.web.servlet.ResultActions revokeInvite(String id) throws Exception {
        return mvc.perform(delete("/admin/invites/" + id).header(LocalAdmin.HEADER, adminSecret()));
    }

    @Test
    void anInviteRevokedTheMomentItIsCreatedIsRevokedAndSaysSo() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");

        revokeInvite(invite[0]).andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true)).andExpect(jsonPath("$.state").value("revoked"));

        assertThat(DATA.resolve("invites").resolve(invite[0] + ".txt")).doesNotExist();
        assertThat(invitation(invite[0]).status()).isEqualTo(SecurityStore.InvitationStatus.REVOKED);
        mvc.perform(post("/login/ott").param("token", invite[1]).with(csrf()).with(from(SHIR_PHONE))).andExpect(redirectedUrl("/login?invite-failed"));
    }

    @Test
    void revokingTwiceSaysItWasAlreadyRevoked() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");
        revokeInvite(invite[0]).andExpect(status().isOk());

        // Its link file is gone now, so this goes by id; the store says what it knows.
        revokeInvite(invite[0]).andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true)).andExpect(jsonPath("$.state").value("already-revoked"));
    }

    @Test
    void anUnknownOrMalformedInviteIsAnErrorNotASuccess() throws Exception {
        revokeInvite("i-0badc0de").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.revoked").value(false)).andExpect(jsonPath("$.state").value("not-found"));
        revokeInvite("../../admin.secret").andExpect(status().is4xxClientError());
        revokeInvite("i-XYZ").andExpect(status().isBadRequest());
        assertThat(DATA.resolve("admin.secret")).exists();
    }

    @Test
    void anInviteThatAlreadyEnrolledItsDeviceIsAConflictAndTheDeviceStays() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");
        registerPasskey(redeem(invite[1], SHIR_PHONE), SHIR_PHONE, "c2hpcnMta2V5", "shir");

        revokeInvite(invite[0]).andExpect(status().isConflict())
                .andExpect(jsonPath("$.revoked").value(false)).andExpect(jsonPath("$.state").value("already-enrolled"));

        assertThat(store.device(SHIR_PHONE.nodeId())).get().satisfies(d -> assertThat(d.activeFor("shir")).isTrue());
    }

    @Test
    void anInviteWhoseLinkFileIsGoneIsRevokedById() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");
        Files.delete(DATA.resolve("invites").resolve(invite[0] + ".txt"));

        revokeInvite(invite[0]).andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(true));
        assertThat(invitation(invite[0]).status()).isEqualTo(SecurityStore.InvitationStatus.REVOKED);
    }

    @Test
    void aLinkFileForSomeOtherInviteRevokesNothing() throws Exception {
        String[] first = invite("shir", "Shir's iPhone");
        String[] second = invite("shir", "Shir's iPad");
        // The first invite's file now holds the second invite's link (a mix-up, or tampering): ids don't match.
        Files.writeString(DATA.resolve("invites").resolve(first[0] + ".txt"), "http://localhost:8080/login/ott?token=" + second[1] + "\n");

        revokeInvite(first[0]).andExpect(status().isNotFound()).andExpect(jsonPath("$.revoked").value(false));

        assertThat(invitation(first[0]).status()).isEqualTo(SecurityStore.InvitationStatus.PENDING);
        assertThat(invitation(second[0]).status()).isEqualTo(SecurityStore.InvitationStatus.PENDING);
        assertThat(DATA.resolve("invites").resolve(first[0] + ".txt")).exists(); // kept: nothing was revoked
    }

    /* ---------- L2: refused registrations and passkey deletion answer cleanly ---------- */

    @Test
    void springsPasskeyDeletionIsNotAllowed() throws Exception {
        MockHttpSession family = TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);

        mvc.perform(delete("/webauthn/register/c2hpcnMta2V5").session(family).with(from(SHIR_PHONE)).with(csrf()))
                .andExpect(status().isMethodNotAllowed());

        assertThat(store.passkey(Bytes.fromBase64("c2hpcnMta2V5"))).get().satisfies(p -> assertThat(p.activeFor("shir")).isTrue());
        mvc.perform(delete("/webauthn/register/c2hpcnMta2V5").with(csrf())).andExpect(status().is3xxRedirection()); // signed out: sign in first
    }

    /* ---------- L3: the passkey a sign-in used comes from that request only ---------- */

    @Test
    void aPasskeyRememberedInTheSessionIsNotUsedToCompleteASignIn() {
        TestBrowsers.enrollNewPasskey(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AccessSession.SIGNED_IN_CREDENTIAL, "c2hpcnMta2V5"); // left by some other request
        MockHttpServletRequest request = TestBrowsers.request(context, "POST", "/login/webauthn");
        request.setSession(session);
        from(SHIR_PHONE).postProcessRequest(request);
        var auth = new org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication(TestBrowsers.user(store, "shir"),
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_FAMILY")));

        assertThat(context.getBean(PasskeySignInSuccessHandler.class).complete(request, auth)).isEqualTo("sign-in without a known passkey");
        assertThat(session.getAttribute(AccessSession.BINDING)).isNull();
    }

    /* ---------- L4 and L5: refused before the invite is used, with a reason the page shows ---------- */

    @Test
    void aDeviceTiedToAnotherMemberIsRefusedUntilReleasedAndTheInviteStaysGood() throws Exception {
        TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        store.revokeDevice(SHIR_PHONE.nodeId());
        String[] invite = invite("hubby", "Hubby's iPhone");

        mvc.perform(post("/login/ott").param("token", invite[1]).with(csrf()).with(from(SHIR_PHONE)))
                .andExpect(redirectedUrl("/login?device-not-available"));
        assertThat(invitation(invite[0]).status()).isEqualTo(SecurityStore.InvitationStatus.PENDING);

        store.releaseDevice(SHIR_PHONE.nodeId());
        redeem(invite[1], SHIR_PHONE);
    }

    @Test
    void aRevokedMembersLeftoverInviteIsRefusedWithoutBeingUsed() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");
        store.revokeMember("shir"); // the member record only, as if cleanup hadn't run

        mvc.perform(post("/login/ott").param("token", invite[1]).with(csrf()).with(from(SHIR_PHONE))).andExpect(redirectedUrl("/login?invite-failed"));

        assertThat(invitation(invite[0]).status()).isEqualTo(SecurityStore.InvitationStatus.PENDING);
    }

    @Test
    void theSignInPageExplainsEachDeviceRefusal() throws Exception {
        String script = mvc.perform(get("/login.js")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(script).contains("\"device-unverified\"", "\"device-already-set-up\"", "\"device-not-available\"");
    }

    /* ---------- an invite that can't be redeemed (Shahar's first try on watson) ---------- */

    @Test
    void aFailedInviteAsksForANewOneAndNeverLogsItsToken() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(InviteFailureHandler.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        String[] invite = invite("hubby", "Hubby's iPhone");
        try {
            redeem(invite[1], HUBBY_PHONE);
            // Used already (or expired, revoked, unknown): not "Try Face ID again", but "ask for a new invite".
            mvc.perform(post("/login/ott").param("token", invite[1]).with(csrf()).with(from(HUBBY_PHONE)))
                    .andExpect(status().isFound()).andExpect(redirectedUrl("/login?invite-failed"));
            mvc.perform(post("/login/ott").param("token", "not-a-token").with(csrf()).with(from(HUBBY_PHONE)))
                    .andExpect(redirectedUrl("/login?invite-failed"));
        } finally {
            logger.detachAppender(logged);
        }
        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                .hasSize(2).allSatisfy(m -> assertThat(m).startsWith("Invite not redeemed: ").doesNotContain(invite[1]));
        String script = mvc.perform(get("/login.js")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(script).contains("\"invite-failed\"", "Ask for a new one", "don't sign in with Face ID on this phone");
    }

    @Test
    void anInviteLastsFifteenMinutes() throws Exception {
        Invitation i = invitation(invite("hubby", "Hubby's iPhone")[0]);
        assertThat(Duration.between(i.createdAt(), i.expiresAt())).isEqualTo(Duration.ofMinutes(15)).isEqualTo(Invitations.LIFETIME);
    }

    @Test
    void someoneElsesSyncedPasskeyDuringAnEnrollmentIsRefusedAndChangesNothing() throws Exception {
        // Shir is set up; her passkey syncs to the other phone too (shared iCloud Keychain).
        MockHttpSession shir = TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        MockHttpSession enrolling = redeem(invite("hubby", "Hubby's iPhone")[1], HUBBY_PHONE);

        // On that phone, Face ID offers Shir's passkey: "Passkey sign-in refused for shir: device not authorized".
        assertThat(TestBrowsers.signIn(context, enrolling, "shir", "c2hpcnMta2V5", HUBBY_PHONE)).isEqualTo("device not authorized");
        assertThat(store.device(HUBBY_PHONE.nodeId())).isEmpty();
        assertThat(store.passkeysOf("hubby")).isEmpty();
        expectGarage(enrolling, HUBBY_PHONE, 401);
        // Shir's own device, passkey and session are untouched.
        assertThat(store.device(SHIR_PHONE.nodeId())).get().satisfies(d -> assertThat(d.activeFor("shir")).isTrue());
        assertThat(store.passkeysOf("shir")).hasSize(1);
        expectGarage(shir, SHIR_PHONE, 200);
    }

    /* ---------- administration: on the server only ---------- */

    @Test
    void administrationIsLocalAndNeedsTheSecret() throws Exception {
        mvc.perform(get("/admin/access")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/access").header(LocalAdmin.HEADER, "wrong")).andExpect(status().isForbidden());
        // Through the reverse proxy, even with the secret, even signed in as family.
        MockHttpSession family = TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        mvc.perform(get("/admin/access").session(family).with(from(SHIR_PHONE)).header(LocalAdmin.HEADER, adminSecret())).andExpect(status().isForbidden());
        for (String header : new String[] {"X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Host", "Forwarded", "Via"}) {
            mvc.perform(get("/admin/access").header(LocalAdmin.HEADER, adminSecret()).header(header, "127.0.0.1")).andExpect(status().isForbidden());
        }

        mvc.perform(get("/admin/access").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[0].name").value("shir"))
                .andExpect(jsonPath("$.members[0].status").value("ACTIVE"))
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
        assertThat(store.invitationsOf("shir")).isEmpty();
    }
}
