package home.casita.garage;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.DisposableBean;

/**
 * A pretend garage door for local runs and until the Meross client lands
 * ({@code casita.door=simulated}, the default for now). Like a real MSG100, the sensor
 * reads "open" almost as soon as the door starts rising, but only reads "closed" once
 * the door is all the way down.
 */
@Component
@ConditionalOnProperty(name = "casita.door", havingValue = "simulated", matchIfMissing = true)
public class SimulatedDoor implements DoorDevice, DisposableBean {

    private final Clock clock;
    private final long travelMillis;
    private final ScheduledExecutorService motor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "casita-simulated-door");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean open;
    private volatile Instant changedAt;

    public SimulatedDoor(Clock clock, GarageProperties properties) {
        this.clock = clock;
        this.travelMillis = properties.travelTime().toMillis();
        this.changedAt = clock.instant();
    }

    @Override
    public Reading read() {
        return new Reading(open, changedAt);
    }

    @Override
    public void command(boolean wantOpen) {
        if (wantOpen == open) return;
        long delay = wantOpen ? Math.min(500, travelMillis / 4) : Math.max(0, travelMillis - 500);
        motor.schedule(() -> {
            open = wantOpen;
            changedAt = clock.instant();
        }, delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        motor.shutdownNow();
    }
}
