package home.casita.security;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.ui.DefaultResourcesFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Who can use Casita, and how they prove it. See docs/APP.md for the threat model.
 *
 * <ul>
 *   <li><b>Passkeys</b> (Face ID) are the only way to sign in day to day: Spring Security's WebAuthn
 *       support, bound to {@code casita.security.rp-id} and the allowed origins.</li>
 *   <li><b>Invites</b> are Spring Security one-time tokens, for enrolling a phone: the link is written to a
 *       file on the server ({@link InviteLinks}), never shown to whoever asked. Signing in with one leads
 *       straight to setting up a passkey.</li>
 *   <li>Only the people in {@code casita.security.family} exist. They have no usable password, and there is
 *       no password sign-in at all.</li>
 *   <li><b>CSRF</b> protection stays on, in Spring's single-page-app mode: the page reads the token from the
 *       {@code XSRF-TOKEN} cookie and sends it as {@code X-XSRF-TOKEN}. The one exception is asking for an
 *       invite, which returns nothing to the caller.</li>
 *   <li>{@code /api/**} answers 401 when not signed in; pages send you to {@code /login}.</li>
 *   <li>A strict content security policy: the pages load only their own scripts and styles, no inline code.</li>
 * </ul>
 */
@Configuration
class SecurityConfig {

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

    static final String ROLE = "FAMILY";
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'";

    @Bean
    FilePasskeyStore passkeys(CasitaSecurityProperties properties) {
        return new FilePasskeyStore(properties.dataDir());
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

    /** Redirects to {@code /login} relatively: behind tailscale serve, Casita sees http://127.0.0.1, not the real host. */
    private static LoginUrlAuthenticationEntryPoint loginPage() {
        LoginUrlAuthenticationEntryPoint entryPoint = new LoginUrlAuthenticationEntryPoint("/login");
        entryPoint.setFavorRelativeUris(true);
        return entryPoint;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CasitaSecurityProperties properties) throws Exception {
        String baseUrl = properties.allowedOrigins().stream().sorted().findFirst().orElseThrow();
        PathPatternRequestMatcher.Builder path = PathPatternRequestMatcher.withDefaults();
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/healthz", "/error").permitAll()
                        // The sign-in page and what it needs; nothing about the door.
                        .requestMatchers("/login", "/login.html", "/login.js", "/login/webauthn.js", "/common.js", "/casita.css",
                                "/watson.svg", "/manifest.webmanifest")
                        .permitAll()
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
                        .tokenGenerationSuccessHandler(new InviteLinks(properties.dataDir(), baseUrl, properties.family()))
                        .authenticationSuccessHandler(new SimpleUrlAuthenticationSuccessHandler("/passkey")))
                // Spring's passkey script for the sign-in and passkey pages, served from here (no CDN).
                .addFilterBefore(DefaultResourcesFilter.webauthn(), CsrfFilter.class)
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers(path.matcher("/ott/generate")))
                // The token is created lazily; load it on every request so the XSRF-TOKEN cookie is there
                // before the page's first POST (Spring Security's documented single-page-app setup).
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), path.matcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(loginPage(), AnyRequestMatcher.INSTANCE))
                .logout(logout -> logout.logoutSuccessUrl("/login?signed-out"))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CSP)))
                .build();
    }
}
