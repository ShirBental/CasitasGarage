package home.casita.garage;

/** What happened when someone asked the door to move, or when Casita checked on it afterwards. */
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
    /** Check after an unconfirmed command: the door did reach the requested position. */
    CONFIRMED,
    /** Check after a command: the door isn't where it was sent. */
    STUCK,
    /** Check after a command: the sensor couldn't be read. */
    LOST
}
