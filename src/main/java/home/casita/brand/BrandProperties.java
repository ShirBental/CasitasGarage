package home.casita.brand;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The family's own words, kept out of the garage logic: the app greets and signs off with these, and
 * nothing about the door depends on them. Shown only to signed-in family ({@code /api/brand}); the sign-in
 * page stays generic. See docs/APP.md.
 *
 * @param household what Casita calls the family, e.g. "wild ones" ("For the wild ones." in the account
 *                  sheet). Empty to leave it out.
 * @param companion the dog who keeps an eye on things in Casita's copy ("Watson's keeping an eye on things.")
 * @param aboutLine one quiet line in the account sheet. Empty to leave it out.
 */
@ConfigurationProperties("casita.brand")
public record BrandProperties(
        @DefaultValue("") String household,
        @DefaultValue("Watson") String companion,
        @DefaultValue("") String aboutLine) {
}
