package home.casita.security.access;

/**
 * The device a request comes from, as far as Casita can verify it: an enrolled browser key ({@link DeviceKeys}).
 * Stored device authorizations keep the field name {@code nodeId} (from when a device was a tailnet node), so
 * existing records, Firestore documents and the admin API stay readable.
 *
 * @param nodeId   the device id Casita authorizes: {@code b-} and 128 random bits for a browser key
 * @param nodeName a short name for administration and logs only, never proof of anything
 */
public record DeviceIdentity(String nodeId, String nodeName) {

    @Override
    public String toString() {
        return "DeviceIdentity[" + nodeName + "]";
    }
}
