package home.casita.garage;

/**
 * A door command that was definitely <b>not</b> sent to the device, for example because the
 * connection failed before anything was published. Any other {@link DoorException} from
 * {@link DoorDevice#command(boolean)} means the outcome is unknown.
 */
public class DoorCommandRejectedException extends DoorException {

    private static final long serialVersionUID = 1L;

    public DoorCommandRejectedException(String message) {
        super(message);
    }

    public DoorCommandRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
