package home.casita.garage;

/** What happened when someone asked the door to move, or when Casita checked on it afterwards. */
public enum Outcome {
    /** The command was sent and the door is moving. */
    MOVING,
    /** The door was already where it was asked to go; nothing was sent. */
    ALREADY,
    /** The door is still moving from an earlier command; nothing was sent. */
    BUSY,
    /** The door's position couldn't be read, so nothing was sent. */
    UNKNOWN,
    /** The command couldn't be delivered; nothing moved. */
    ERROR,
    /** Closing from the app is switched off in configuration. */
    DISABLED,
    /** After the travel time, the door wasn't where it was sent. */
    STUCK,
    /** After the travel time, the door couldn't be read. */
    LOST
}
