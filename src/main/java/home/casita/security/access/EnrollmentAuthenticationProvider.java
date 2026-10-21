package home.casita.security.access;

import java.util.List;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Redeems an invite. The result can only enroll a device (ROLE_ENROLLING): it can't see or move the door,
 * and an invite is never a way to sign in day to day.
 */
public class EnrollmentAuthenticationProvider implements AuthenticationProvider {

    private final Invites invites;
    private final List<String> family;

    public EnrollmentAuthenticationProvider(Invites invites, List<String> family) {
        this.invites = invites;
        this.family = family;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String token = ((OneTimeTokenAuthenticationToken) authentication).getTokenValue();
        Invites.Invite invite = invites.redeem(token).orElseThrow(() -> new BadCredentialsException("Invalid or expired invite"));
        if (!family.contains(invite.member())) {
            invites.revoke(invite.id());
            throw new BadCredentialsException("Invite for someone outside the family");
        }
        OneTimeTokenAuthenticationToken enrolling = OneTimeTokenAuthenticationToken.authenticated(invite.member(),
                List.of(new SimpleGrantedAuthority(AccessSession.ROLE_ENROLLING)));
        enrolling.setDetails(invite);
        return enrolling;
    }

    @Override
    public boolean supports(Class<?> type) {
        return OneTimeTokenAuthenticationToken.class.isAssignableFrom(type);
    }
}
