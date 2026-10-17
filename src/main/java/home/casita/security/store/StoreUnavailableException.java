package home.casita.security.store;

/** The store couldn't be read or written. Whoever asked refuses: Casita fails closed. */
public class StoreUnavailableException extends RuntimeException {

    public StoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
