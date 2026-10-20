package home.casita.security.access;

import static home.casita.security.access.TestTailnet.SHIR_PHONE;
import static home.casita.security.access.TestTailnet.from;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import home.casita.security.store.FaultyFirestore;
import home.casita.security.store.FirestoreEmulator;
import home.casita.security.store.FirestoreSecurityStore;
import home.casita.security.store.SecurityStore;

/**
 * The access flows with the Firestore store (the emulator, each test in its own project), plus failing closed in an
 * outage, and the security events a real enrollment and sign-in leave behind.
 */
@SpringBootTest
@Import({TestTailnet.Config.class, FirestoreEmulator.Config.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class FirestoreAccessFlowTest extends AccessFlowContract {

    @Autowired
    private FaultyFirestore firestore;

    @BeforeAll
    static void emulator() {
        assumeTrue(FirestoreEmulator.available(), "No Firestore emulator: set -Dfirestore.emulator.jar or -Dfirestore.emulator.host");
    }

    @DynamicPropertySource
    static void firestoreSettings(DynamicPropertyRegistry registry) {
        if (!FirestoreEmulator.available()) return;
        registry.add("casita.storage.type", () -> "firestore");
        // A new project per context: every test starts with an empty database.
        registry.add("casita.storage.firestore.project-id", FirestoreEmulator::newProject);
        registry.add("casita.storage.firestore.emulator-host", FirestoreEmulator::host);
        registry.add("casita.storage.firestore.timeout", () -> "2s"); // outages answer in two seconds, not five
    }

    @AfterEach
    void firestoreBack() {
        firestore.reset();
    }

    @Test
    void itReallyIsTheFirestoreStore() {
        assertThat(store).isInstanceOf(FirestoreSecurityStore.class);
    }

    @Test
    void duringAnOutageEverySessionIsRefused() throws Exception {
        MockHttpSession session = TestTailnet.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        expectGarage(session, SHIR_PHONE, 200);

        firestore.down(true);
        mvc.perform(post("/api/open").session(session).with(from(SHIR_PHONE)).with(csrf())).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        assertThat(activity.recent(10)).isEmpty();
        firestore.down(false);
    }

    @Test
    void duringAnOutageAnInviteIsntRedeemedAndStaysGood() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");

        firestore.down(true);
        mvc.perform(post("/login/ott").param("token", invite[1]).with(csrf()).with(from(SHIR_PHONE))).andExpect(redirectedUrl("/login?error"));
        firestore.down(false);

        assertThat(invitation(invite[0]).status()).isEqualTo(SecurityStore.InvitationStatus.PENDING);
        redeem(invite[1], SHIR_PHONE);
    }

    @Test
    void duringAnOutageAnEnrollmentSessionIsRefused() throws Exception {
        MockHttpSession enrolling = redeem(invite("shir", "Shir's iPhone")[1], SHIR_PHONE);

        firestore.down(true);
        mvc.perform(get("/passkey").session(enrolling).with(from(SHIR_PHONE))).andExpect(redirectedUrl("/login"));
        firestore.down(false);
        assertThat(enrolling.isInvalid()).isTrue();
    }

    @Test
    void anOutageDuringSignInGivesNoSession() {
        TestTailnet.enrollNewPasskey(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        MockHttpSession session = new MockHttpSession();

        // The test's own lookup of Shir goes through; then the sign-in's consistent read of member, passkey and device fails.
        firestore.failBefore("BatchGetDocuments", 1_000, 1);
        String refusal = TestTailnet.signIn(context, session, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        firestore.reset();

        assertThat(refusal).isEqualTo("security store unavailable");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aRealPasskeySignInDuringAnOutageGetsNoSession() throws Exception {
        SoftAuthenticator iphone = new SoftAuthenticator();
        assertThat(realRegistration(iphone, redeem(invite("shir", "Shir's iPhone")[1], SHIR_PHONE), SHIR_PHONE)).isEqualTo(200);
        MockHttpSession session = new MockHttpSession();
        String options = optionsJson("/webauthn/authenticate/options", session, SHIR_PHONE);

        firestore.down(true);
        int status;
        try {
            status = mvc.perform(post("/login/webauthn").session(session).with(from(SHIR_PHONE)).with(csrf())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(iphone.assertion(options)))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            status = 500; // escaped as an error: no session either way
        }
        firestore.down(false);

        assertThat(status).isNotEqualTo(200);
        expectGarage(session, SHIR_PHONE, 401);
    }

    @Test
    void aMemberRevocationWhoseCleanupFailsStillRefusesThemAndCanBeRerun() throws Exception {
        MockHttpSession session = TestTailnet.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);

        firestore.failBefore("RunQuery", 1_000); // the member record is updated, then the cleanup's first query fails
        mvc.perform(delete("/admin/members/shir").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.cleanup").value("incomplete: run revoke-member again"));
        firestore.reset();

        expectGarage(session, SHIR_PHONE, 401); // the member record alone refuses them
        assertThat(store.device(SHIR_PHONE.nodeId())).get().satisfies(d -> assertThat(d.status()).isEqualTo(SecurityStore.Status.ACTIVE));

        mvc.perform(delete("/admin/members/shir").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(jsonPath("$.devices").value(1)).andExpect(jsonPath("$.credentials").value(1));
        assertThat(store.device(SHIR_PHONE.nodeId())).get().satisfies(d -> assertThat(d.status()).isEqualTo(SecurityStore.Status.REVOKED));
    }

    @Test
    void aRevocationDuringAnOutageIsAnErrorAndKeepsTheLinkFile() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");

        firestore.down(true);
        mvc.perform(delete("/admin/invites/" + invite[0]).header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.revoked").doesNotExist());
        firestore.down(false);

        assertThat(DATA.resolve("invites").resolve(invite[0] + ".txt")).exists();
        assertThat(invitation(invite[0]).status()).isEqualTo(SecurityStore.InvitationStatus.PENDING);
        mvc.perform(delete("/admin/invites/" + invite[0]).header(LocalAdmin.HEADER, adminSecret())).andExpect(status().isOk());
    }

    @Test
    void anInviteRevokedByIdTheMomentItIsCreatedIsRevoked() throws Exception {
        // No index lag in Firestore: even without the link file, revoking by id finds it at once.
        String[] invite = invite("shir", "Shir's iPhone");
        java.nio.file.Files.delete(DATA.resolve("invites").resolve(invite[0] + ".txt"));

        mvc.perform(delete("/admin/invites/" + invite[0]).header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(true));
        assertThat(invitation(invite[0]).status()).isEqualTo(SecurityStore.InvitationStatus.REVOKED);
    }

    @Test
    void aRealEnrollmentAndSignInAreRecordedAsSecurityEvents() throws Exception {
        SoftAuthenticator iphone = new SoftAuthenticator();
        String[] invite = invite("shir", "Shir's iPhone");
        assertThat(realRegistration(iphone, redeem(invite[1], SHIR_PHONE), SHIR_PHONE)).isEqualTo(200);
        assertThat(realSignIn(iphone, new MockHttpSession(), SHIR_PHONE)).isEqualTo(200);

        var events = ((FirestoreSecurityStore) store).securityEvents(50);
        assertThat(events).extracting(FirestoreSecurityStore.SecurityEvent::type)
                .contains("INVITATION_CREATED", "INVITATION_CONSUMED", "DEVICE_ENROLLED", "SIGNED_IN");
        assertThat(events).filteredOn(e -> e.type().equals("DEVICE_ENROLLED")).singleElement().satisfies(e -> {
            assertThat(e.inviteId()).isEqualTo(invite[0]);
            assertThat(e.nodeId()).isEqualTo(SHIR_PHONE.nodeId());
            assertThat(e.credentialId()).isEqualTo(iphone.credentialId());
        });
        assertThat(events).allSatisfy(e -> assertThat(e.toString()).doesNotContain(invite[1]));
    }
}
