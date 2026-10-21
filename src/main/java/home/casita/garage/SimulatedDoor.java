package home.casita.garage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A pretend garage door for local runs ({@code casita.door=simulated}, the default); one per door, made by
 * {@code SimulatedRosterConfig}. Like a real opener, the sensor reads "open" almost as soon as the door
 * starts rising, but only reads "closed" once the door is all the way down.
 */
public class SimulatedDoor implements DoorDevice {

    private final Clock clock;
    private final long travelMillis;
    private final ScheduledExecutorService motor;

    private volatile boolean open;
    private volatile Instant changedAt;

    public SimulatedDoor(String name, Clock clock, Duration travelTime) {
        this.clock = clock;
        this.travelMillis = travelTime.toMillis();
        this.changedAt = clock.instant();
        this.motor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "casita-simulated-" + name);
            t.setDaemon(true);
            return t;
        });
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

    public void destroy() {
        motor.shutdownNow();
    }
}
