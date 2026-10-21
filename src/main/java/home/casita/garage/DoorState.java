package home.casita.garage;

public enum DoorState {
    /** The sensor reports fully closed. */
    CLOSED,
    /** The sensor reports not closed, and has done so long enough that the door shouldn't still be moving. */
    OPEN,
    /** Casita sent "open", or the sensor just changed from closed to open (wall button or remote). */
    OPENING,
    /** Casita sent "close" and the sensor doesn't report closed yet. */
    CLOSING,
    /**
     * Casita can't tell whether the door is moving: a command's outcome is unknown, or Casita
     * just started and hasn't watched an open door long enough.
     */
    CHECKING,
    /** The sensor can't be read. */
    UNKNOWN
}
