package home.casita.garage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Writes notifications to the log. Placeholder until the push service is chosen. */
@Component
public class LoggingNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotifier.class);

    @Override
    public void send(String message) {
        log.info("Notify: {}", message);
    }
}
