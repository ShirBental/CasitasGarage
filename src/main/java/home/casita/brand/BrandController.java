package home.casita.brand;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The family's words for the app ({@link BrandProperties}). Signed-in family only, like the rest of {@code /api}. */
@RestController
class BrandController {

    private final BrandProperties brand;

    BrandController(BrandProperties brand) {
        this.brand = brand;
    }

    @GetMapping("/api/brand")
    BrandProperties brand() {
        return brand;
    }
}
