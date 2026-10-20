package home.casita.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.resource.ResourceUrlProvider;

/**
 * The app's pages, all static files (src/main/resources/static) with their links rewritten to the files'
 * content-hashed URLs ({@link PagesConfig}). {@code /} is index.html, {@code /login} the sign-in page,
 * {@code /passkey} sets up Face ID; Spring's passkey script returns to {@code /webauthn/register?success}
 * after setting one up, so that shows the same page.
 *
 * <p>Pages and the manifest are {@code no-cache}: the browser revalidates them every time (an ETag, so
 * usually a 304), and a deploy is picked up on the next load. Everything they link to is then cached for a
 * year under its hashed URL.
 */
@Controller
class PagesController {

    static final MediaType MANIFEST = MediaType.parseMediaType("application/manifest+json");

    /** src="/x" and href="/x" in a page; "src": "/x" in the manifest. */
    private static final Pattern LINK = Pattern.compile("((?:src|href)=\"|\"src\": \")(/[^\"?#]+)\"");

    /** Served here, not as files: never linked by a hashed URL. */
    private static final Set<String> PAGES = Set.of("/index.html", "/login.html", "/passkey.html", "/manifest.webmanifest");

    private final ResourceUrlProvider urls;
    private final Map<String, Page> rendered = new ConcurrentHashMap<>();

    private record Page(String body, String etag) {}

    PagesController(ResourceUrlProvider urls) {
        this.urls = urls;
    }

    @GetMapping({"/", "/index.html"})
    ResponseEntity<String> home() {
        return page("index.html", MediaType.TEXT_HTML);
    }

    @GetMapping({"/login", "/login.html"})
    ResponseEntity<String> login() {
        return page("login.html", MediaType.TEXT_HTML);
    }

    @GetMapping({"/passkey", "/passkey.html", "/webauthn/register"})
    ResponseEntity<String> passkey() {
        return page("passkey.html", MediaType.TEXT_HTML);
    }

    @GetMapping("/manifest.webmanifest")
    ResponseEntity<String> manifest() {
        return page("manifest.webmanifest", MANIFEST);
    }

    /** No-cache with an ETag: Spring answers 304 when the browser's copy is current. */
    private ResponseEntity<String> page(String file, MediaType type) {
        Page page = rendered.computeIfAbsent(file, this::render);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .eTag(page.etag())
                .contentType(new MediaType(type, StandardCharsets.UTF_8))
                .body(page.body());
    }

    private Page render(String file) {
        String text;
        try {
            text = new ClassPathResource("static/" + file).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Can't read static/" + file, e);
        }
        Matcher links = LINK.matcher(text);
        StringBuilder body = new StringBuilder();
        while (links.find()) {
            String path = links.group(2);
            boolean isFile = path.substring(path.lastIndexOf('/')).contains(".");
            String versioned = isFile && !PAGES.contains(path) ? urls.getForLookupPath(path) : null;
            links.appendReplacement(body, Matcher.quoteReplacement(links.group(1) + (versioned != null ? versioned : path) + "\""));
        }
        links.appendTail(body);
        String html = body.toString();
        return new Page(html, DigestUtils.md5DigestAsHex(html.getBytes(StandardCharsets.UTF_8)));
    }
}
