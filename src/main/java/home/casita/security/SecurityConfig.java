package home.casita.security;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.ui.DefaultResourcesFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;
import org.springframework.web.filter.OncePerRequestFilter;

import home.casita.security.access.AccessCheckFilter;
import home.casita.security.access.AccessSession;
import home.casita.security.access.AccessStore;
import home.casita.security.access.CliTailscaleWhois;
import home.casita.security.access.CredentialEvents;
import home.casita.security.access.DevelopmentDeviceIdentity;
import home.casita.security.access.DeviceIdentityResolver;
import home.casita.security.access.EnrollmentAuthenticationProvider;
import home.casita.security.access.EnrollmentGuardFilter;
import home.casita.security.access.EnrollmentSuccessHandler;
import home.casita.security.access.Invites;
import home.casita.security.access.LocalAdmin;
import home.casita.security.access.RedemptionGuardFilter;
import home.casita.security.access.TailscaleDeviceIdentity;
import home.casita.security.access.TailscaleWhois;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Who can use Casita, from which devices, and how they prove it. See docs/ACCESS.md.
 *
 * <ul>
 *   <li><b>Passkey</b> (Face ID or the device passcode; user verification required) says <i>who</i>. The
 *       <b>tailnet node</b>, verified through tailscale serve and the local tailscaled, says <i>which device</i>.
 *       A session needs both, for the same member, on every request ({@link AccessCheckFilter}).</li>
 *   <li><b>One invite, one device.</b> An invite is created on the server for a member and a device label. It
 *       gives an enrollment-only session that can do one thing: set up a passkey, or use the member's existing
 *       synced passkey, on that same device, which authorizes the device. Never the garage.</li>
 *   <li>Unverifiable device: no invite redemption, no enrollment, no session. Fail closed.</li>
 *   <li>Access is administered on the server only ({@link LocalAdmin}).</li>
 *   <li>CSRF on (single-page-app mode); a strict content security policy; {@code /api/**} answers 401,
 *       pages go to {@code /login} (relative, safe behind tailscale serve).</li>
 * </ul>
 */
@Configuration
class SecurityConfig {

    static final String ROLE = "FAMILY";
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'";

    /** Loads the deferred CSRF token so the cookie is written on every response. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) token.getToken();
            chain.doFilter(request, response);
        }
    }

    /* ---------- the parts ---------- */

    @Bean
    FilePasskeyStore passkeys(CasitaSecurityProperties properties) {
        return new FilePasskeyStore(properties.dataDir());
    }

    @Bean
    AccessStore accessStore(CasitaSecurityProperties properties, Clock clock) {
        return new AccessStore(properties.dataDir(), clock);
    }

    @Bean
    Invites invites(Clock clock) {
        return new Invites(clock);
    }

    /** The local tailscaled, asked through {@code tailscale whois}. */
    @Bean
    TailscaleWhois tailscaleWhois(CasitaSecurityProperties properties, Clock clock) {
        return new CliTailscaleWhois(properties.tailscaleCli(), clock);
    }

    @Bean
    DeviceIdentityResolver deviceIdentity(CasitaSecurityProperties properties, TailscaleWhois whois) {
        return properties.deviceIdentity() == CasitaSecurityProperties.DeviceIdentityMode.DEVELOPMENT
                ? new DevelopmentDeviceIdentity()
                : new TailscaleDeviceIdentity(whois);
    }

    @Bean
    CredentialEvents credentialEvents(Invites invites, AccessStore access, DeviceIdentityResolver devices, FilePasskeyStore passkeys) {
        CredentialEvents events = new CredentialEvents(invites, access, devices);
        passkeys.setEvents(events);
        return events;
    }

    @Bean
    LocalAdmin localAdmin(CasitaSecurityProperties properties) {
        return new LocalAdmin(properties.dataDir());
    }

    /** The family, and nobody else. Their passwords are random and never used: there's no password sign-in. */
    @Bean
    UserDetailsService family(CasitaSecurityProperties properties) {
        SecureRandom random = new SecureRandom();
        List<UserDetails> users = properties.family().stream().map(name -> {
            byte[] unusable = new byte[32];
            random.nextBytes(unusable);
            return User.withUsername(name).password("{noop}" + HexFormat.of().formatHex(unusable)).roles(ROLE).build();
        }).toList();
        return new InMemoryUserDetailsManager(users);
    }

    /** Spring's WebAuthn, with user verification (Face ID or passcode) required to create and to use a passkey. */
    @Bean
    Webauthn4JRelyingPartyOperations relyingParty(FilePasskeyStore passkeys, CasitaSecurityProperties properties) {
        Webauthn4JRelyingPartyOperations operations = new Webauthn4JRelyingPartyOperations(passkeys, passkeys,
                PublicKeyCredentialRpEntity.builder().id(properties.rpId()).name("Casita").build(), properties.allowedOrigins());
        operations.setCustomizeCreationOptions(options -> options.authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                .residentKey(ResidentKeyRequirement.REQUIRED)
                .userVerification(UserVerificationRequirement.REQUIRED)
                .build()));
        operations.setCustomizeRequestOptions(options -> options.userVerification(UserVerificationRequirement.REQUIRED));
        return operations;
    }

    /** Redirects to {@code /login} relatively: behind tailscale serve, Casita sees http://127.0.0.1, not the real host. */
    private static LoginUrlAuthenticationEntryPoint loginPage() {
        LoginUrlAuthenticationEntryPoint entryPoint = new LoginUrlAuthenticationEntryPoint("/login");
        entryPoint.setFavorRelativeUris(true);
        return entryPoint;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CasitaSecurityProperties properties, Invites invites, AccessStore access,
                                            DeviceIdentityResolver devices, FilePasskeyStore passkeys, LocalAdmin localAdmin) throws Exception {
        PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/healthz", "/error").permitAll()
                        // The sign-in page and what it needs; nothing about the door.
                        .requestMatchers("/login", "/login.html", "/login.js", "/login/webauthn.js", "/common.js", "/casita.css",
                                "/watson.svg", "/manifest.webmanifest", "/passkey.html", "/passkey.js")
                        .permitAll()
                        // Where Spring's passkey script lands after setting one up: a static page, no data.
                        .requestMatchers(path.matcher(HttpMethod.GET, "/webauthn/register")).permitAll()
                        // Access administration: on the server only.
                        .requestMatchers("/admin/**").access(localAdmin)
                        // Enrollment: setting up a passkey for the invite's device, nothing else.
                        .requestMatchers("/passkey").hasAuthority(AccessSession.ROLE_ENROLLING)
                        .requestMatchers(path.matcher(HttpMethod.POST, "/webauthn/register")).hasAuthority(AccessSession.ROLE_ENROLLING)
                        .anyRequest().hasRole(ROLE))
                .webAuthn(webAuthn -> webAuthn
                        .rpName("Casita")
                        .rpId(properties.rpId())
                        .allowedOrigins(properties.allowedOrigins())
                        .disableDefaultRegistrationPage(true))
                .oneTimeTokenLogin(ott -> ott
                        .loginPage("/login")
                        // A custom login page would otherwise move token sign-in onto POST /login.
                        .loginProcessingUrl("/login/ott")
                        .tokenService(invites)
                        // Invites come from the server-side admin only: Spring's public /ott/generate does nothing.
                        .generateRequestResolver(request -> null)
                        .tokenGenerationSuccessHandler((request, response, token) -> response.sendError(HttpServletResponse.SC_NOT_FOUND))
                        .authenticationProvider(new EnrollmentAuthenticationProvider(invites, properties.family()))
                        .authenticationSuccessHandler(new EnrollmentSuccessHandler()))
                // Spring's passkey script for the sign-in and passkey pages, served from here (no CDN).
                .addFilterBefore(DefaultResourcesFilter.webauthn(), CsrfFilter.class)
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers(path.matcher("/admin/**")))
                // The token is created lazily; load it on every request so the XSRF-TOKEN cookie is there
                // before the page's first POST (Spring Security's documented single-page-app setup).
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                // An invite is redeemed only from a verifiable device, before it is used up.
                .addFilterAfter(new RedemptionGuardFilter(devices), CsrfFilter.class)
                // Passkeys can be set up only in a live enrollment, on its device.
                .addFilterAfter(new EnrollmentGuardFilter(invites, devices), RedemptionGuardFilter.class)
                // Every signed-in request: member, passkey, device authorization and device, again.
                .addFilterBefore(new AccessCheckFilter(devices, access, invites, passkeys, properties.family()), AuthorizationFilter.class)
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), path.matcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.FORBIDDEN), path.matcher("/admin/**"))
                        .defaultAuthenticationEntryPointFor(loginPage(), AnyRequestMatcher.INSTANCE))
                .logout(logout -> logout.logoutSuccessUrl("/login?signed-out"))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CSP)))
                .build();
    }
}
