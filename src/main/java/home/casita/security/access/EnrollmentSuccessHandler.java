package home.casita.security.access;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import home.casita.security.store.SecurityStore.EnrollmentClaim;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** After an invitation is redeemed: keep the enrollment claim in the session (server-side) and go set up Face ID. */
public class EnrollmentSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(EnrollmentSuccessHandler.class);

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        EnrollmentClaim claim = (EnrollmentClaim) authentication.getDetails();
        request.getSession().setAttribute(AccessSession.ENROLLMENT, claim);
        log.info("Invite {} for {} redeemed on device {}; enrollment started", claim.inviteId(), claim.memberId(), claim.nodeName());
        response.sendRedirect("/passkey");
    }
}
