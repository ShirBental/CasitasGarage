package home.casita.security.shortcut;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import home.casita.security.CasitaSecurityProperties;
import home.casita.security.store.SecurityStore;

/**
 * Apple Shortcuts and Siri: their own filter chain for {@code /shortcut/**}, and only that (docs/SHORTCUTS.md).
 * The browser chain ({@code SecurityConfig}) is unchanged and never sees these requests; this chain never sees any other.
 *
 * <ul>
 *   <li>Stateless: no session is read or created, so a browser's session or browser key means nothing here, and a
 *       Shortcut token means nothing on {@code /api/**}.</li>
 *   <li>No CSRF protection, because there is nothing for it to protect: CSRF rides on credentials a browser sends by
 *       itself (cookies), and this chain reads none. The only credential is the Authorization header, which a page on
 *       another site can't make a browser add.</li>
 *   <li>{@link ShortcutAuthenticationFilter} decides who; the endpoint checks the token's door and action.</li>
 * </ul>
 */
@Configuration
class ShortcutSecurityConfig {

    @Bean
    ShortcutTokens shortcutTokens(CasitaSecurityProperties properties, Clock clock) {
        return new ShortcutTokens(properties.dataDir(), clock);
    }

    @Bean
    ShortcutRateLimiter shortcutRateLimiter(Clock clock) {
        return new ShortcutRateLimiter(clock);
    }

    /** Before the browser chain, which matches every other request. */
    @Bean
    @Order(1)
    SecurityFilterChain shortcutFilterChain(HttpSecurity http, ShortcutTokens tokens, SecurityStore store, CasitaSecurityProperties properties,
                                            ShortcutRateLimiter limits, Clock clock) throws Exception {
        // Built here, not as a bean: a filter bean would also be registered for every request by Spring Boot.
        ShortcutAuthenticationFilter authentication = new ShortcutAuthenticationFilter(tokens, store, properties.family(), limits, clock);
        return http
                .securityMatcher("/shortcut/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority(ShortcutAuthenticationFilter.AUTHORITY))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .logout(logout -> logout.disable())
                .anonymous(anonymous -> anonymous.disable())
                .addFilterBefore(authentication, AuthorizationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, failure) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            ShortcutAuthenticationFilter.refuse(response, 401, "unauthorized", "Casita didn't accept this Shortcut's key.");
                        })
                        .accessDeniedHandler((request, response, denied) ->
                                ShortcutAuthenticationFilter.refuse(response, 403, "forbidden", "This Shortcut isn't allowed to do that.")))
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .build();
    }
}
