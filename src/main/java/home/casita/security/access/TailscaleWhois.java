package home.casita.security.access;

import java.util.Optional;

/** Asks the local tailscaled who a tailnet address belongs to ({@code tailscale whois}). */
public interface TailscaleWhois {

    /** @param tagged whether the node is a tagged (non-user) node */
    record Node(String stableId, String name, String userLogin, boolean tagged) {}

    /** Empty when tailscaled doesn't know the address or can't be asked. */
    Optional<Node> whois(String address);
}
