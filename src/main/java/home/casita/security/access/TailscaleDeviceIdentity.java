package home.casita.security.access;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The device behind a request through tailscale serve, verified end to end (docs/ACCESS.md):
 *
 * <ol>
 *   <li>tailscale serve deletes any client-sent X-Forwarded-* and Tailscale-User-* headers, then sets
 *       X-Forwarded-For to the connection's tailnet source address and Tailscale-User-Login from tailscaled
 *       (Tailscale 1.104 source, Go's ReverseProxy Rewrite).</li>
 *   <li>Tomcat accepts X-Forwarded-For only from 127.0.0.1 (server.tomcat.remoteip), and Casita listens only
 *       there, so the remote address seen here is the one tailscale serve set.</li>
 *   <li>That address must be a tailnet address; the request must carry Tailscale-User-Login and must not be
 *       a Funnel (public internet) request.</li>
 *   <li>The local tailscaled is asked who the address is; its user must match Tailscale-User-Login, and
 *       tagged nodes are refused.</li>
 * </ol>
 * Anything missing or inconsistent: empty, and the caller refuses.
 */
public class TailscaleDeviceIdentity implements DeviceIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(TailscaleDeviceIdentity.class);
    static final String USER_HEADER = "Tailscale-User-Login";
    static final String FUNNEL_HEADER = "Tailscale-Funnel-Request";

    private final TailscaleWhois whois;

    public TailscaleDeviceIdentity(TailscaleWhois whois) {
        this.whois = whois;
    }

    @Override
    public Optional<DeviceIdentity> resolve(HttpServletRequest request) {
        if (request.getHeader(FUNNEL_HEADER) != null) return refuse("a Funnel request");
        String address = request.getRemoteAddr();
        if (!isTailnetAddress(address)) return refuse("not from a tailnet address");
        String login = request.getHeader(USER_HEADER);
        if (login == null || login.isBlank()) return refuse("no tailnet user from tailscale serve");
        Optional<TailscaleWhois.Node> node = whois.whois(address);
        if (node.isEmpty()) return refuse("tailscaled doesn't know the address");
        if (node.get().tagged()) return refuse("a tagged node");
        if (!node.get().userLogin().equals(login)) return refuse("tailscaled and tailscale serve disagree on the user");
        return Optional.of(new DeviceIdentity(node.get().stableId(), node.get().name(), login));
    }

    private static Optional<DeviceIdentity> refuse(String why) {
        log.debug("Device not verified: {}", why);
        return Optional.empty();
    }

    /** Tailscale's ranges: 100.64.0.0/10 and fd7a:115c:a1e0::/48. */
    static boolean isTailnetAddress(String address) {
        if (address == null || address.isBlank()) return false;
        try {
            if (!address.matches("[0-9a-fA-F:.]+")) return false; // literal addresses only, never a DNS lookup
            byte[] b = InetAddress.getByName(address).getAddress();
            if (b.length == 4) return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
            return b.length == 16 && (b[0] & 0xff) == 0xfd && (b[1] & 0xff) == 0x7a && (b[2] & 0xff) == 0x11
                    && (b[3] & 0xff) == 0x5c && (b[4] & 0xff) == 0xa1 && (b[5] & 0xff) == 0xe0;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
