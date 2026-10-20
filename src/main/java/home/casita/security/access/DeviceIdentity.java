package home.casita.security.access;

/**
 * A device as Tailscale knows it: one tailnet node, with its own WireGuard key (never iCloud-synced).
 *
 * @param nodeId    the node's stable id, as the local tailscaled reports it: the device id Casita authorizes
 * @param nodeName  its name in the tailnet, for administration only
 * @param userLogin the tailnet user the node belongs to
 */
public record DeviceIdentity(String nodeId, String nodeName, String userLogin) {

    @Override
    public String toString() {
        return "DeviceIdentity[node=" + nodeName + "]";
    }
}
