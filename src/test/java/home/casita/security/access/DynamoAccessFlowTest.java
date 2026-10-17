package home.casita.security.access;

import static home.casita.security.access.TestTailnet.SHIR_PHONE;
import static home.casita.security.access.TestTailnet.from;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
@Import({TestTailnet.Config.class, LocalDynamo.Config.class})
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
        MockHttpSession session = TestTailnet.signedInOn(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
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
        TestTailnet.enrollNewPasskey(context, "shir", "c2hpcnMta2V5", SHIR_PHONE);
        MockHttpSession session = new MockHttpSession();

        dynamo.failBefore("transactGetItems", 1); // the sign-in's consistent read of member, passkey and device
        String refusal = TestTailnet.signIn(context, session, "shir", "c2hpcnMta2V5", SHIR_PHONE);

        assertThat(refusal).isEqualTo("security store unavailable");
        assertThat(session.isInvalid()).isTrue();
    }
}
