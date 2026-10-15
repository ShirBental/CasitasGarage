package home.casita.garage;

/** The door couldn't be read or commanded (offline, refused, timed out...). */
public class DoorException extends Exception {

    private static final long serialVersionUID = 1L;

    public DoorException(String message) {
        super(message);
    }

    public DoorException(String message, Throwable cause) {
        super(message, cause);
    }
}
