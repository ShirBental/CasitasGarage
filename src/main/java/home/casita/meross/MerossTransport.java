package home.casita.meross;

import java.util.List;
import java.util.Map;

import home.casita.garage.DoorCommandRejectedException;
import home.casita.garage.DoorException;

/** How {@link MerossDoor} reaches the opener. The real one is {@link MerossSession}. */
interface MerossTransport {

    /** Devices on the Meross account. */
    List<MerossDeviceInfo> devices() throws DoorException;

    /** Reads from a device (method {@code GET}). Safe to repeat. Returns the reply payload. */
    Map<String, Object> get(String deviceUuid, String namespace) throws DoorException;

    /**
     * Sends one {@code SET} to a device, at most once, never retried. A returned payload only
     * means the device answered; it doesn't prove anything physical happened.
     *
     * @throws DoorCommandRejectedException if nothing was sent
     * @throws DoorException                if the outcome is unknown
     */
    Map<String, Object> setOnce(String deviceUuid, String namespace, Map<String, Object> payload) throws DoorException;
}
