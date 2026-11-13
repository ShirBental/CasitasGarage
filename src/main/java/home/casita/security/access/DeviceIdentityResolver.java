package home.casita.security.access;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Which device a request comes from, or empty when that can't be verified: callers then fail closed. */
public interface DeviceIdentityResolver {

    Optional<DeviceIdentity> resolve(HttpServletRequest request);

    /**
     * For redeeming an invite only: the request's device, or, when it has none yet, a new one handed to it in
     * {@code response}. Empty when the request can't carry one (then nothing is redeemed).
     */
    default Optional<DeviceIdentity> resolveOrIssue(HttpServletRequest request, HttpServletResponse response) {
        return resolve(request);
    }

    /** After a successful passkey sign-in on {@code device}: keep it from expiring. */
    default void renew(HttpServletRequest request, HttpServletResponse response, DeviceIdentity device) {
    }
}
