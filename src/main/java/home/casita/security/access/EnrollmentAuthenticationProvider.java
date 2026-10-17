package home.casita.security.access;

import java.util.List;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import home.casita.security.store.SecurityStore.EnrollmentClaim;
import home.casita.security.store.StoreUnavailableException;

/**
 * Redeems an invitation on the device RedemptionGuardFilter verified. The result can only enroll that device
 * (ROLE_ENROLLING): it can't see or move the door, and an invitation is never a way to sign in day to day.
 */
public class EnrollmentAuthenticationProvider implements AuthenticationProvider {

    private final Invitations invitations;
    private final List<String> family;

    public EnrollmentAuthenticationProvider(Invitations invitations, List<String> family) {
        this.invitations = invitations;
        this.family = family;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String token = ((OneTimeTokenAuthenticationToken) authentication).getTokenValue();
        DeviceIdentity device = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
                && a.getRequest().getAttribute(AccessSession.REDEEMING_DEVICE) instanceof DeviceIdentity d ? d : null;
        if (device == null) throw new BadCredentialsException("Device not verified");
        EnrollmentClaim claim;
        try {
            claim = invitations.redeem(token, device).orElseThrow(() -> new BadCredentialsException("Invalid, used, expired or revoked invitation"));
        } catch (StoreUnavailableException e) {
            throw new AuthenticationServiceException("Security store unavailable", e);
        }
        if (!family.contains(claim.memberId())) {
            invitations.revoke(claim.inviteId());
            throw new BadCredentialsException("Invitation for someone outside the family");
        }
        OneTimeTokenAuthenticationToken enrolling = OneTimeTokenAuthenticationToken.authenticated(claim.memberId(),
                List.of(new SimpleGrantedAuthority(AccessSession.ROLE_ENROLLING)));
        enrolling.setDetails(claim);
        return enrolling;
    }

    @Override
    public boolean supports(Class<?> type) {
        return OneTimeTokenAuthenticationToken.class.isAssignableFrom(type);
    }
}
