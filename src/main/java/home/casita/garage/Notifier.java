package home.casita.garage;

/** Pushes a message to the family's phones. The push service is still an open question (ntfy or SNS). */
public interface Notifier {

    void send(String message);
}
