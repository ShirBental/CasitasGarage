package home.casita.garage;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Casita's rules for the garage door. The opener only "presses the button", so the safety
 * lives here:
 * <ol>
 *   <li>Read the sensor first. If the door's position is unknown, change nothing.</li>
 *   <li>Open and close are separate: "open" never closes, "close" never opens.</li>
 *   <li>One move at a time: ignore commands while the door is moving.</li>
 *   <li>Check afterwards that the door got where it was going, and say so if it didn't.</li>
 *   <li>Closing from the app can be switched off.</li>
 * </ol>
 * Any error means the door doesn't move.
 */
@Service
public class GarageService {

    static final String CASITA = "Casita";

    private static final Logger log = LoggerFactory.getLogger(GarageService.class);

    private final DoorDevice door;
    private final ActivityLog activity;
    private final Notifier notifier;
    private final DelayedTasks tasks;
    private final Clock clock;
    private final GarageProperties properties;

    // Guarded by this.
    private Instant movingSince;
    private Action movingAction;
    private long movement;

    public GarageService(DoorDevice door, ActivityLog activity, Notifier notifier, DelayedTasks tasks,
                         Clock clock, GarageProperties properties) {
        this.door = door;
        this.activity = activity;
        this.notifier = notifier;
        this.tasks = tasks;
        this.clock = clock;
        this.properties = properties;
    }

    public synchronized GarageStatus status() {
        DoorDevice.Reading reading;
        try {
            reading = door.read();
        } catch (DoorException e) {
            log.warn("Can't read the garage door: {}", e.getMessage());
            return new GarageStatus(DoorState.UNKNOWN, null, false);
        }
        if (isMoving()) {
            // The sensor reads "open" as soon as the door starts rising, so trust the clock for opening.
            if (movingAction == Action.OPEN) return new GarageStatus(DoorState.OPENING, movingSince, false);
            // The sensor reads "closed" only once the door is all the way down.
            if (reading.open()) return new GarageStatus(DoorState.CLOSING, movingSince, false);
            movingSince = null; // closed early: done moving
        }
        return new GarageStatus(reading.open() ? DoorState.OPEN : DoorState.CLOSED, reading.changedAt(),
                reading.open() && properties.allowRemoteClose());
    }

    public synchronized CommandResult open(String who) {
        return move(Action.OPEN, who);
    }

    public synchronized CommandResult close(String who) {
        if (!properties.allowRemoteClose()) {
            record(who, Action.CLOSE, Outcome.DISABLED);
            return result(Outcome.DISABLED, "Closing from the app is turned off. Please use the wall button.");
        }
        return move(Action.CLOSE, who);
    }

    private CommandResult move(Action action, String who) {
        if (isMoving()) {
            return result(Outcome.BUSY, "Give me a moment, the door is still moving.");
        }

        DoorDevice.Reading reading;
        try {
            reading = door.read();
        } catch (DoorException e) {
            log.warn("Won't {} the garage: can't read the door: {}", action, e.getMessage());
            record(who, action, Outcome.UNKNOWN);
            return result(Outcome.UNKNOWN, "I can't see the door right now, so I won't touch it.");
        }

        boolean wantOpen = action.wantsOpen();
        if (reading.open() == wantOpen) {
            record(who, action, Outcome.ALREADY);
            return result(Outcome.ALREADY, wantOpen ? "It's already open." : "It's already closed.");
        }

        try {
            door.command(wantOpen);
        } catch (DoorException e) {
            log.warn("Couldn't {} the garage: {}", action, e.getMessage());
            record(who, action, Outcome.ERROR);
            return result(Outcome.ERROR, "I couldn't reach the door. Nothing moved.");
        }

        movingSince = clock.instant();
        movingAction = action;
        long thisMovement = ++movement;
        record(who, action, Outcome.MOVING);
        notifier.send((wantOpen ? "Opening the garage for " : "Closing the garage for ") + who);
        tasks.runAt(movingSince.plus(properties.travelTime()).plus(properties.verifyGrace()),
                () -> verify(thisMovement));
        return result(Outcome.MOVING, wantOpen ? "Opening up for you." : "Closing up.");
    }

    /** Runs once the door should have finished moving. */
    synchronized void verify(long which) {
        if (which != movement) return; // a newer command took over
        Action action = movingAction;
        DoorDevice.Reading reading;
        try {
            reading = door.read();
        } catch (DoorException e) {
            record(CASITA, action, Outcome.LOST);
            notifier.send("I lost sight of the garage door after moving it. Please check it.");
            return;
        }
        if (reading.open() != action.wantsOpen()) {
            record(CASITA, action, Outcome.STUCK);
            notifier.send(action.wantsOpen()
                    ? "The garage door didn't open. Please check it."
                    : "The garage door didn't close. Something may be in the way. Please check it.");
        } else if (action == Action.CLOSE) {
            notifier.send("The garage is closed. All tucked in.");
        }
    }

    private boolean isMoving() {
        return movingSince != null && clock.instant().isBefore(movingSince.plus(properties.travelTime()));
    }

    private void record(String who, Action action, Outcome outcome) {
        activity.record(new ActivityEvent(clock.instant(), who, action, outcome));
    }

    private CommandResult result(Outcome outcome, String message) {
        return new CommandResult(outcome, message, status());
    }
}
