package home.casita.garage;

/**
 * What Casita can say about the door right now. Only {@link #CLOSED} is proven by the sensor; every
 * other state is "not closed" plus what Casita knows about recent movement.
 */
public enum DoorState {
    /** The sensor reports fully closed. */
    CLOSED,
    /**
     * The sensor reports not closed, and has done so long enough that the door shouldn't still be
     * moving. "Open" is inferred: the sensor only knows closed / not closed, so a door stopped partway
     * reads the same as a fully open one.
     */
    NOT_CLOSED,
    /**
     * Casita sent "open", or the sensor just changed from closed to open (wall button or remote), less than the door's
     * travel time ago. Never shown longer than that.
     */
    OPENING,
    /** Casita sent "close", less than the door's travel time ago, and the sensor doesn't report closed yet. */
    CLOSING,
    /**
     * Casita's command has had the door's full travel time, but the sensor doesn't show its result yet: the reading
     * trails the door (Toyota's can, by tens of seconds), or the door didn't move. The door is probably not moving;
     * where it is isn't known until the reading changes or the command's check. Nothing that the reading could make a
     * button press is sent meanwhile.
     */
    WAITING_FOR_SENSOR,
    /**
     * Casita can't tell whether the door is moving: a command's outcome is unknown, or Casita
     * just started and hasn't watched an open door long enough.
     */
    CHECKING,
    /** The sensor can't be read. */
    UNKNOWN
}
