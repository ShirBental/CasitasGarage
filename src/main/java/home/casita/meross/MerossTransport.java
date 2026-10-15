package home.casita.meross;

import java.util.List;
import java.util.Map;

import home.casita.garage.DoorException;

/** How {@link MerossDoor} reaches the opener. The real one goes through Meross's cloud. */
interface MerossTransport {

    /** Devices on the Meross account. */
    List<MerossDeviceInfo> devices() throws DoorException;

    /**
     * Sends one signed message to a device and returns the reply's payload.
     *
     * @throws DoorException if it can't be sent, the device doesn't answer in time, or answers with an error
     */
    Map<String, Object> request(String deviceUuid, String method, String namespace, Map<String, Object> payload)
            throws DoorException;
}
