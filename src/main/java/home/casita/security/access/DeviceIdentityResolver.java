package home.casita.security.access;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

/** Which device a request comes from, or empty when that can't be verified: callers then fail closed. */
public interface DeviceIdentityResolver {

    Optional<DeviceIdentity> resolve(HttpServletRequest request);
}
