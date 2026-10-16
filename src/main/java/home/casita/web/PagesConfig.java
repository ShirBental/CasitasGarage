package home.casita.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The web app's pages, all static (src/main/resources/static): {@code /} is index.html. {@code /login} is
 * the sign-in page, {@code /passkey} sets up Face ID; Spring's passkey script returns to
 * {@code /webauthn/register?success} after setting one up, so that shows the same page.
 */
@Configuration
class PagesConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/login").setViewName("forward:/login.html");
        registry.addViewController("/passkey").setViewName("forward:/passkey.html");
        registry.addViewController("/webauthn/register").setViewName("forward:/passkey.html");
    }
}
