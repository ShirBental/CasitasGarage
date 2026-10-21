package home.casita.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.util.DigestUtils;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.ContentVersionStrategy;
import org.springframework.web.servlet.resource.VersionResourceResolver;

/**
 * How the web app's static files (src/main/resources/static) are served and cached. The pages themselves
 * ({@code /}, {@code /login}, {@code /passkey}, the manifest) come from {@link PagesController}.
 *
 * <ul>
 *   <li>Every script, stylesheet and picture is linked by a URL carrying a hash of its content, e.g.
 *       {@code /app-<md5>.js} or {@code /brand/watson-idle-<md5>.webp}: the pages are rewritten to use them
 *       ({@link PagesController}), and so are {@code /api/brand}'s pictures. Those URLs never change content,
 *       so they are cached for a year ({@link #IMMUTABLE}); a deploy that changes a file, new Watson artwork
 *       included, changes its URL.</li>
 *   <li>The same files by their plain names (e.g. {@code /app.js}) still work, but must be revalidated
 *       ({@code no-cache}).</li>
 *   <li>Everything else (the API, sign-in, passkeys, invites) keeps Spring Security's {@code no-store}.</li>
 * </ul>
 * Who may fetch what is unchanged: that is SecurityConfig's, which permits the sign-in page's files by both
 * names.
 */
@Configuration
class PagesConfig implements WebMvcConfigurer {

    /** A file name with a content hash in it, as {@link VersionResourceResolver} writes it: name-md5.ext. */
    static final String VERSIONED_FILE = "{file:[A-Za-z0-9._-]+-[0-9a-f]{32}\\.[a-z0-9]+}";

    static final CacheControl IMMUTABLE = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

    private static final String STATIC = "classpath:/static/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Versioned names: the most specific patterns, so they win over /** below.
        for (String dir : new String[] {"", "brand/", "icons/"}) {
            registry.addResourceHandler("/" + dir + VERSIONED_FILE)
                    .addResourceLocations(STATIC + dir)
                    .setCacheControl(IMMUTABLE)
                    .resourceChain(true)
                    .addResolver(versions());
        }
        // Plain names: revalidated every time (Last-Modified, so usually a 304).
        registry.addResourceHandler("/**")
                .addResourceLocations(STATIC)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(versions());
    }

    private static VersionResourceResolver versions() {
        return new VersionResourceResolver().addVersionStrategy(new LinkedContentVersionStrategy(), "/**");
    }

    /**
     * A content hash, and for a stylesheet also the content of what it links to with {@code url("/...")}:
     * casita.css's URL then changes when the app icon it shows does (Spring rewrites that link to the icon's
     * versioned URL, but would otherwise hash the stylesheet before the rewrite).
     */
    static final class LinkedContentVersionStrategy extends ContentVersionStrategy {

        private static final Pattern CSS_URL = Pattern.compile("url\\(\\s*[\"']?(/[^\"')?#]+)");

        @Override
        public String getResourceVersion(Resource resource) {
            String version = super.getResourceVersion(resource);
            String name = resource.getFilename();
            if (name == null || !name.endsWith(".css")) return version;
            try {
                StringBuilder all = new StringBuilder(version);
                Matcher links = CSS_URL.matcher(resource.getContentAsString(StandardCharsets.UTF_8));
                while (links.find()) {
                    Resource linked = new ClassPathResource("static" + links.group(1));
                    if (linked.exists()) all.append(DigestUtils.md5DigestAsHex(linked.getContentAsByteArray()));
                }
                return DigestUtils.md5DigestAsHex(all.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("Can't read " + resource, e);
            }
        }
    }
}
