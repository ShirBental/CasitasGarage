package home.casita.garage;

/**
 * What happened when someone asked the door to move, or when Casita checked on it afterwards.
 * Every {@link #MOVING} or {@link #UNCONFIRMED} is followed by exactly one check outcome:
 * {@link #CLOSED_CONFIRMED}, {@link #OPEN_INFERRED}, {@link #STUCK} or {@link #LOST}. After a
 * stuck or lost movement every command is {@link #HELD} until someone {@link #RESUMED}.
 */
public enum Outcome {
    /** The command was acknowledged and the door should be moving. */
    MOVING,
    /** The command was sent but not acknowledged: the door may or may not be moving. */
    UNCONFIRMED,
    /** The sensor already reports the requested position; nothing was sent. */
    ALREADY,
    /** A movement is unverified or the door may be moving; nothing was sent. */
    BUSY,
    /** The sensor couldn't be read, so nothing was sent. */
    UNKNOWN,
    /** The command was definitely not sent; nothing moved. */
    ERROR,
    /** Closing from the app is switched off in configuration. */
    DISABLED,
    /** Check after a close: the sensor reports closed. Proven by the sensor. */
    CLOSED_CONFIRMED,
    /**
     * Check after an open: the sensor reports not closed. The door left the closed position; that it
     * is fully open is inferred, as the sensor can't tell.
     */
    OPEN_INFERRED,
    /** Check after a command, at travel time + grace: the door isn't where it was sent. */
    STUCK,
    /** Check after a command: the sensor couldn't be read, so the outcome is unknown. */
    LOST,
    /** A movement ended {@link #STUCK} or {@link #LOST} and nobody has resumed yet; nothing was sent. */
    HELD,
    /** Someone checked the door after a stuck or lost movement, and commands are allowed again. */
    RESUMED
}
