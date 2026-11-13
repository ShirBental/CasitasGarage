package home.casita.security.access;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.authentication.WebAuthnAuthentication;
import org.springframework.web.filter.OncePerRequestFilter;

import home.casita.security.store.SecurityStore;
import home.casita.security.store.StoreUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Signs an enrolled browser back in from its remembered sign-in ({@link RememberedSignIns}) when it has no session:
 * after a restart, or once the session has ended. Runs just before {@link AccessCheckFilter}, which then checks the new
 * session again like any other. A session is restored only when all of these hold:
 * <ol>
 *   <li>HTTPS, no signed-in session already, and the cookie's MAC and lifetime check out;</li>
 *   <li>the request's browser key verifies and is the one the cookie was issued to;</li>
 *   <li>the security store agrees, read now: member in the family and active, the passkey active and theirs, the browser
 *       authorized for them ({@link AccessDecisions#passkeySession}).</li>
 * </ol>
 * Refused: the cookie is removed and the request goes on signed out (sign-in page, or 401 for the API). Security store
 * unreachable: signed out too (fail closed), but the cookie is kept, so the browser signs itself back in once the store
 * is back. A restored session gets a new session id and is bound to the same passkey and browser as the original
 * sign-in; it carries the extra authority {@value #AUTHORITY}, so anything that ever needs a fresh Face ID can tell.
 */
public class RememberedSignInFilter extends OncePerRequestFilter {

    public static final String AUTHORITY = "CASITA_REMEMBERED";
    private static final Logger log = LoggerFactory.getLogger(RememberedSignInFilter.class);

    private final RememberedSignIns signIns;
    private final DeviceIdentityResolver devices;
    private final AccessDecisions decisions;
    private final SecurityStore store;
    private final String role;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();
    private final SecurityContextRepository repository = new HttpSessionSecurityContextRepository();

    /** @param role the authority a family member's session has (e.g. {@code ROLE_FAMILY}) */
    public RememberedSignInFilter(RememberedSignIns signIns, DeviceIdentityResolver devices, AccessDecisions decisions, SecurityStore store,
                                  String role) {
        this.signIns = signIns;
        this.devices = devices;
        this.decisions = decisions;
        this.store = store;
        this.role = role;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String value = signIns.enabled() && request.isSecure() ? cookie(request) : null;
        if (value != null) {
            if (!signedIn()) restore(request, response, value);
            else renewInUse(request, response, value);
        }
        chain.doFilter(request, response);
    }

    /**
     * A browser in daily use within one long session: keep its remembered sign-in from lapsing for idleness (at most
     * once a day). Only the session's own sign-in (same member, passkey and browser) is renewed, never past its expiry.
     */
    private void renewInUse(HttpServletRequest request, HttpServletResponse response, String value) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(AccessSession.BINDING) instanceof AccessSession.Binding binding)) return;
        java.time.Instant now = signIns.now();
        if (session.getAttribute(AccessSession.REMEMBER_RENEWED) instanceof java.time.Instant last
                && now.isBefore(last.plus(java.time.Duration.ofDays(1)))) return;
        Optional<RememberedSignIns.Claim> claim = signIns.verify(value).filter(c -> c.member().equals(binding.member())
                && c.credentialId().equals(binding.credentialId()) && c.nodeId().equals(binding.nodeId()));
        if (claim.isEmpty()) return;
        session.setAttribute(AccessSession.REMEMBER_RENEWED, now);
        response.addHeader(HttpHeaders.SET_COOKIE, signIns.renew(claim.get()).toString());
    }

    private void restore(HttpServletRequest request, HttpServletResponse response, String value) {
        Optional<RememberedSignIns.Claim> found = signIns.verify(value);
        if (found.isEmpty()) {
            refuse(response, "unknown", "expired, signed with another key, or not a remembered sign-in");
            return;
        }
        RememberedSignIns.Claim claim = found.get();
        Optional<DeviceIdentity> device = devices.resolve(request);
        if (device.isEmpty() || !device.get().nodeId().equals(claim.nodeId())) {
            refuse(response, claim.member(), "not the browser it was issued to");
            return;
        }
        AccessSession.Binding binding = new AccessSession.Binding(claim.member(), claim.credentialId(), claim.nodeId());
        String refusal = decisions.passkeySession(claim.member(), binding, device.get());
        Optional<SecurityStore.Member> member = Optional.empty();
        if (refusal == null) {
            try {
                member = store.member(claim.member()).filter(m -> m.active() && m.userHandle() != null);
                if (member.isEmpty()) {
                    refusal = "member revoked";
                } else {
                    // A browser revoked and later authorized again (always through a new invite) starts over: a cookie
                    // issued under an earlier authorization, such as a copy that prompted the revocation, never matches.
                    // An exact comparison of ids, not of times, so nothing depends on clocks or on how close together
                    // the revocation, the new authorization and the cookie were.
                    String current = store.device(claim.nodeId()).map(SecurityStore.Device::inviteId).orElse(null);
                    if (current == null || !current.equals(claim.authorization())) refusal = "issued under an earlier authorization of this browser";
                }
            } catch (StoreUnavailableException e) {
                refusal = AccessDecisions.STORE_UNAVAILABLE;
            }
        }
        if (AccessDecisions.STORE_UNAVAILABLE.equals(refusal)) {
            log.warn("Remembered sign-in for {} on {} not restored: security store unavailable", claim.member(), device.get().nodeName());
            return; // signed out for now; the cookie stays for when the store is back
        }
        if (refusal != null) {
            refuse(response, claim.member(), refusal);
            return;
        }

        // A new session id: nothing from before (an anonymous session, a fixed id) carries into the signed-in one.
        if (request.getSession(false) != null) request.changeSessionId();
        HttpSession session = request.getSession(true);
        session.setAttribute(AccessSession.BINDING, binding);
        session.setAttribute(AccessSession.FACE_ID_UNTIL, signIns.expiry(claim));
        session.setAttribute(AccessSession.REMEMBER_RENEWED, signIns.now());
        SecurityStore.Member m = member.get();
        WebAuthnAuthentication auth = new WebAuthnAuthentication(
                ImmutablePublicKeyCredentialUserEntity.builder().name(m.id()).id(m.userHandle()).displayName(m.id()).build(),
                List.of(new SimpleGrantedAuthority(role), new SimpleGrantedAuthority(AUTHORITY)));
        SecurityContext context = contexts.createEmptyContext();
        context.setAuthentication(auth);
        contexts.setContext(context);
        repository.saveContext(context, request, response);
        response.addHeader(HttpHeaders.SET_COOKIE, signIns.renew(claim).toString());
        devices.renew(request, response, device.get());
        log.info("Remembered sign-in: {} on {}, last passkey sign-in {}", claim.member(), device.get().nodeName(), claim.verifiedAt());
    }

    private void refuse(HttpServletResponse response, String member, String reason) {
        log.info("Remembered sign-in refused for {}: {}", member, reason);
        response.addHeader(HttpHeaders.SET_COOKIE, RememberedSignIns.cleared().toString());
    }

    private boolean signedIn() {
        Authentication auth = contexts.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    private static String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) if (RememberedSignIns.COOKIE.equals(c.getName())) return c.getValue();
        return null;
    }
}
