package home.casita.brand;

import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The family's own words and pictures, kept out of the garage logic: nothing about the door depends on
 * them. Shown only to signed-in family ({@code /api/brand}); the sign-in page stays generic. See docs/APP.md.
 *
 * @param household    what Casita calls the family, e.g. "wild ones" ("For the wild ones." in the account
 *                     sheet). Empty to leave it out.
 * @param companion    the dog's name: the illustration's description for screen readers
 * @param aboutLine    one quiet line in the account sheet. Empty to leave it out.
 * @param illustration where the approved illustration of the companion is served from, e.g.
 *                     {@code /brand/watson-home.png} (a file in {@code static/brand/}). Empty until the
 *                     approved artwork exists: the home screen then shows no illustration and no placeholder.
 *                     Only a path on Casita itself, never another site.
 */
@ConfigurationProperties("casita.brand")
public record BrandProperties(
        @DefaultValue("") String household,
        @DefaultValue("Watson") String companion,
        @DefaultValue("") String aboutLine,
        @DefaultValue("") String illustration) {

    private static final Pattern LOCAL_PATH = Pattern.compile("/brand/[A-Za-z0-9._-]+\\.(png|webp|svg|jpg)");

    public BrandProperties {
        if (!illustration.isEmpty() && !LOCAL_PATH.matcher(illustration).matches()) {
            throw new IllegalArgumentException("casita.brand.illustration must be a file in static/brand/, "
                    + "e.g. /brand/watson-home.png: " + illustration);
        }
    }
}
