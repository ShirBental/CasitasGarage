package home.casita.security.access;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * An invitation that couldn't be redeemed (unknown, expired, used, revoked, for someone outside the family, or the
 * store unavailable): logged with the reason, never the token, and the phone goes to {@code /login?invite-failed},
 * which asks for a new invite. Not to {@code /login?error} ("Try Face ID again?"): a phone that isn't set up has
 * nothing of its own to sign in with, and Face ID there can only offer someone else's synced passkey.
 */
public class InviteFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(InviteFailureHandler.class);

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        // The messages are Casita's own fixed sentences (EnrollmentAuthenticationProvider); none contains the token.
        log.info("Invite not redeemed: {}", exception.getMessage());
        response.sendRedirect("/login?invite-failed");
    }
}
