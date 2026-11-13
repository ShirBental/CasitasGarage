package home.casita.security.access;

import static home.casita.security.access.TestBrowsers.SHIR_PHONE;
import static home.casita.security.access.TestBrowsers.from;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;

import home.casita.security.store.DynamoSecurityStore;
import home.casita.security.store.FaultyDynamo;
import home.casita.security.store.LocalDynamo;
import home.casita.security.store.SecurityStore;

/** The access flows with the DynamoDB store (DynamoDB Local, in the test JVM), plus failing closed in an outage. */
@SpringBootTest(properties = {"casita.storage.type=dynamodb", "casita.storage.region=us-east-1"})
@Import({TestBrowsers.Config.class, LocalDynamo.Config.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DynamoAccessFlowTest extends AccessFlowContract {

    @Autowired
    private FaultyDynamo dynamo;

    @AfterEach
    void dynamoBack() {
        dynamo.reset();
    }

    @Test
    void itReallyIsTheDynamoStore() {
        assertThat(store).isInstanceOf(DynamoSecurityStore.class);
    }

    @Test
    void duringAnOutageEverySessionIsRefused() throws Exception {
        MockHttpSession session = TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        expectGarage(session, SHIR_PHONE, 200);

        dynamo.down(true);
        mvc.perform(post("/api/open").session(session).with(from(SHIR_PHONE)).with(csrf())).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        assertThat(activity.recent(10)).isEmpty();
        dynamo.down(false);
    }

    @Test
    void duringAnOutageAnInviteIsntRedeemedAndStaysGood() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");

        dynamo.down(true);
        mvc.perform(post("/login/ott").param("token", invite[1]).with(csrf()).with(from(SHIR_PHONE))).andExpect(redirectedUrl("/login?error"));
        dynamo.down(false);

        assertThat(invitation(invite[0]).status()).isEqualTo(SecurityStore.InvitationStatus.PENDING);
        redeem(invite[1], SHIR_PHONE);
    }

    @Test
    void duringAnOutageAnEnrollmentSessionIsRefused() throws Exception {
        MockHttpSession enrolling = redeem(invite("shir", "Shir's iPhone")[1], SHIR_PHONE);

        dynamo.down(true);
        mvc.perform(get("/passkey").session(enrolling).with(from(SHIR_PHONE))).andExpect(redirectedUrl("/login"));
        dynamo.down(false);
        assertThat(enrolling.isInvalid()).isTrue();
    }

    @Test
    void anOutageDuringSignInGivesNoSession() {
        TestBrowsers.enrollNewPasskey(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        MockHttpSession session = new MockHttpSession();

        dynamo.failBefore("transactGetItems", 1); // the sign-in's consistent read of member, passkey and device
        String refusal = TestBrowsers.signIn(context, session, "shir", "c2hpcnMta2V5", SHIR_PHONE);

        assertThat(refusal).isEqualTo("security store unavailable");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void aRealPasskeySignInDuringAnOutageGetsNoSession() throws Exception {
        SoftAuthenticator iphone = new SoftAuthenticator();
        assertThat(realRegistration(iphone, redeem(invite("shir", "Shir's iPhone")[1], SHIR_PHONE), SHIR_PHONE)).isEqualTo(200);
        MockHttpSession session = new MockHttpSession();
        String options = optionsJson("/webauthn/authenticate/options", session, SHIR_PHONE);

        dynamo.down(true);
        int status;
        try {
            status = mvc.perform(post("/login/webauthn").session(session).with(from(SHIR_PHONE)).with(csrf())
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(iphone.assertion(options)))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            status = 500; // escaped as an error: no session either way
        }
        dynamo.down(false);

        assertThat(status).isNotEqualTo(200);
        expectGarage(session, SHIR_PHONE, 401);
    }

    @Test
    void aMemberRevocationWhoseCleanupFailsStillRefusesThemAndCanBeRerun() throws Exception {
        MockHttpSession session = TestBrowsers.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);

        dynamo.failBefore("query", 1); // the member record is updated, then the cleanup's first index query fails
        mvc.perform(delete("/admin/members/shir").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.cleanup").value("incomplete: run revoke-member again"));

        expectGarage(session, SHIR_PHONE, 401); // the member record alone refuses them
        assertThat(store.device(SHIR_PHONE.nodeId())).get().satisfies(d -> assertThat(d.status()).isEqualTo(SecurityStore.Status.ACTIVE));

        mvc.perform(delete("/admin/members/shir").header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(jsonPath("$.devices").value(1)).andExpect(jsonPath("$.credentials").value(1));
        assertThat(store.device(SHIR_PHONE.nodeId())).get().satisfies(d -> assertThat(d.status()).isEqualTo(SecurityStore.Status.REVOKED));
    }

    /* ---------- M1 against DynamoDB's failure modes ---------- */

    @Test
    void anInviteRevokedWhileTheIndexLagsIsStillRevokedThroughItsLinkFile() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");
        dynamo.indexesLag(true);

        mvc.perform(delete("/admin/invites/" + invite[0]).header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(true));

        dynamo.indexesLag(false);
        assertThat(invitation(invite[0]).status()).isEqualTo(SecurityStore.InvitationStatus.REVOKED);
    }

    @Test
    void withoutTheLinkFileAndALaggingIndexItIsNotFoundNotASuccess() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");
        java.nio.file.Files.delete(DATA.resolve("invites").resolve(invite[0] + ".txt"));
        dynamo.indexesLag(true);

        mvc.perform(delete("/admin/invites/" + invite[0]).header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.revoked").value(false));

        dynamo.indexesLag(false);
        assertThat(invitation(invite[0]).status()).isEqualTo(SecurityStore.InvitationStatus.PENDING);
        mvc.perform(delete("/admin/invites/" + invite[0]).header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revoked").value(true)); // the rerun the answer asks for
    }

    @Test
    void aRevocationDuringAnOutageIsAnErrorAndKeepsTheLinkFile() throws Exception {
        String[] invite = invite("shir", "Shir's iPhone");

        dynamo.down(true);
        mvc.perform(delete("/admin/invites/" + invite[0]).header(LocalAdmin.HEADER, adminSecret()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.revoked").doesNotExist());
        dynamo.down(false);

        assertThat(DATA.resolve("invites").resolve(invite[0] + ".txt")).exists();
        assertThat(invitation(invite[0]).status()).isEqualTo(SecurityStore.InvitationStatus.PENDING);
        mvc.perform(delete("/admin/invites/" + invite[0]).header(LocalAdmin.HEADER, adminSecret())).andExpect(status().isOk());
    }

    @Test
    void aRegistrationDuringAnOutageIsAClean503() throws Exception {
        SoftAuthenticator iphone = new SoftAuthenticator();
        MockHttpSession enrolling = redeem(invite("shir", "Shir's iPhone")[1], SHIR_PHONE);
        String body = iphone.register(optionsJson("/webauthn/register/options", enrolling, SHIR_PHONE), "iCloud Keychain");

        // The guards' reads pass; then, inside the registration, finding the passkey's member fails.
        dynamo.failBefore("query", 1);
        mvc.perform(post("/webauthn/register").session(enrolling).with(from(SHIR_PHONE)).with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("{\"success\":false}"));

        assertThat(store.device(SHIR_PHONE.nodeId())).isEmpty();
    }
}
