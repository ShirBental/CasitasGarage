package home.casita.meross;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How Casita reaches the opener ({@code casita.meross.transport}):
 * <ul>
 *   <li>{@code cloud} (the default): Meross's cloud, over TLS (HTTPS sign-in, MQTT on 443). Nothing is needed at home;
 *       the opener keeps its own outbound connection to Meross.</li>
 *   <li>{@code local}: straight to the opener on the home network, {@code POST <local-url>/config} with messages
 *       signed by the account key, as meross_lan does. It needs a private network path from the server to that address
 *       (deploy/watson/LIVE_DOORS.md); watson, without Tailscale since public HTTPS, has none and uses cloud. The account
 *       is still signed in once, for the key and the device list.</li>
 * </ul>
 *
 * @param transport {@code cloud} or {@code local}
 * @param localUrl  for {@code local}: {@code http://<the opener's private IPv4 address>}, e.g. http://192.168.1.50. Only
 *                  plain http to a private (RFC 1918) address: the opener speaks nothing else, and only a private
 *                  route may reach it.
 */
@ConfigurationProperties("casita.meross")
public record MerossLinkProperties(@DefaultValue("cloud") Transport transport, URI localUrl) {

    public enum Transport { CLOUD, LOCAL }

    public MerossLinkProperties {
        transport = transport == null ? Transport.CLOUD : transport;
        if (transport == Transport.LOCAL) checkLocalUrl(localUrl);
    }

    public static void checkLocalUrl(URI url) {
        if (url == null) {
            throw new IllegalArgumentException("casita.meross.transport local needs casita.meross.local-url (http://<the opener's address>)");
        }
        if (!"http".equals(url.getScheme()) || url.getHost() == null || url.getRawUserInfo() != null
                || (url.getRawPath() != null && !url.getRawPath().isEmpty() && !"/".equals(url.getRawPath()))
                || url.getRawQuery() != null || url.getRawFragment() != null) {
            throw new IllegalArgumentException("casita.meross.local-url must be http://<address>[:port], nothing more: " + url);
        }
        if (!privateIpv4(url.getHost())) {
            throw new IllegalArgumentException("casita.meross.local-url must be the opener's private IPv4 address "
                    + "(10/8, 172.16/12 or 192.168/16), not " + url.getHost());
        }
    }

    /** A literal RFC 1918 IPv4 address (never a name: no DNS decides where commands go). */
    static boolean privateIpv4(String host) {
        if (!host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        try {
            byte[] a = InetAddress.getByName(host).getAddress();
            int b0 = a[0] & 0xff, b1 = a[1] & 0xff;
            return b0 == 10 || (b0 == 172 && b1 >= 16 && b1 <= 31) || (b0 == 192 && b1 == 168);
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
