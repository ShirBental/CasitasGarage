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
 * @param appName      the name in the home screen's header, e.g. "Bental's Casita". Defaults to "Casita".
 * @param illustration where the approved illustration of the companion is served from, e.g.
 *                     {@code /brand/watson-home.png} (a file in {@code static/brand/}). Empty until the
 *                     approved artwork exists: the home screen then shows no illustration and no placeholder.
 *                     Only a path on Casita itself, never another site.
 * @param illustrationExcited the same character, excited, shown while a door is opening or closing; same canvas,
 *                     size and alignment as {@code illustration}, so the screen doesn't move. Empty: Watson
 *                     doesn't change. Only used together with {@code illustration}; same path rule.
 */
@ConfigurationProperties("casita.brand")
public record BrandProperties(
        @DefaultValue("") String household,
        @DefaultValue("Watson") String companion,
        @DefaultValue("") String aboutLine,
        @DefaultValue("Casita") String appName,
        @DefaultValue("") String illustration,
        @DefaultValue("") String illustrationExcited) {

    private static final Pattern LOCAL_PATH = Pattern.compile("/brand/[A-Za-z0-9._-]+\\.(png|webp|svg|jpg)");

    public BrandProperties {
        checkLocal("casita.brand.illustration", illustration);
        checkLocal("casita.brand.illustration-excited", illustrationExcited);
        if (appName == null || appName.isBlank()) {
            throw new IllegalArgumentException("casita.brand.app-name can't be blank");
        }
    }

    private static void checkLocal(String setting, String path) {
        if (!path.isEmpty() && !LOCAL_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException(setting + " must be a file in static/brand/, e.g. /brand/watson-idle.webp: " + path);
        }
    }
}
