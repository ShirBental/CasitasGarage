package home.casita.brand;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.ResourceUrlProvider;

/**
 * The family's words for the app ({@link BrandProperties}). Signed-in family only, like the rest of {@code /api}.
 * The pictures are given by their content-hashed URLs (PagesConfig), so new artwork is never served from a
 * stale cache.
 */
@RestController
class BrandController {

    private final BrandProperties brand;
    private final ResourceUrlProvider urls;

    BrandController(BrandProperties brand, ResourceUrlProvider urls) {
        this.brand = brand;
        this.urls = urls;
    }

    @GetMapping("/api/brand")
    BrandProperties brand() {
        return new BrandProperties(brand.household(), brand.companion(), brand.aboutLine(), brand.appName(),
                versioned(brand.illustration()), versioned(brand.illustrationExcited()));
    }

    private String versioned(String path) {
        if (path.isEmpty()) return path;
        String versioned = urls.getForLookupPath(path);
        return versioned != null ? versioned : path;
    }
}
