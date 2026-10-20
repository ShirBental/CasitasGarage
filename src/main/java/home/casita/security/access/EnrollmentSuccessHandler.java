package home.casita.security.access;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** After an invite is redeemed: remember the enrollment (with the device verified beforehand) and go set up Face ID. */
public class EnrollmentSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(EnrollmentSuccessHandler.class);

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        Invites.Invite invite = (Invites.Invite) authentication.getDetails();
        DeviceIdentity device = (DeviceIdentity) request.getAttribute(AccessSession.REDEEMING_DEVICE);
        request.getSession().setAttribute(AccessSession.ENROLLMENT,
                new AccessSession.Enrollment(invite.id(), invite.member(), invite.label(), device.nodeId(), device.nodeName()));
        log.info("Invite {} for {} redeemed on node {}; enrollment started", invite.id(), invite.member(), device.nodeName());
        response.sendRedirect("/passkey");
    }
}
