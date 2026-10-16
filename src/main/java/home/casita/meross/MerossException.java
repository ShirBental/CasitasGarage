package home.casita.meross;

import home.casita.garage.DoorException;

/**
 * A Meross failure, classified. As a plain {@link DoorException} it means "outcome unknown"
 * to {@code GarageService}: only {@link home.casita.garage.DoorCommandRejectedException}
 * says a command was definitely not sent. Messages never contain credentials.
 */
class MerossException extends DoorException {

    private static final long serialVersionUID = 1L;

    enum Kind {
        /** Sign-in refused: wrong credentials, two-factor required, or the broker rejected us. */
        AUTH,
        /** Meross or the network can't be reached, or Casita is backing off after failures. */
        UNAVAILABLE,
        /** No answer within the timeout. */
        TIMEOUT,
        /** An answer Casita can't make sense of. */
        PROTOCOL,
        /** The device answered with an error. */
        DEVICE_ERROR,
        /** No (single) garage door opener on the account. */
        NOT_FOUND,
        /** Casita's settings don't fit the opener: no channel chosen, or the chosen door is disabled. */
        CONFIG
    }

    private final Kind kind;

    MerossException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    MerossException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    Kind kind() {
        return kind;
    }
}
