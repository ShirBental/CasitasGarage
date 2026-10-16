package home.casita.garage;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Casita's rules for the garage door. See {@link DoorDevice} for what the device does and
 * doesn't promise. In short: one binary sensor (closed / not closed) and a command that may
 * act like a button press, so the rules live here.
 *
 * <ol>
 *   <li><b>Read first.</b> If the sensor can't be read, send nothing.</li>
 *   <li><b>Only toward the opposite.</b> Send "open" only when the sensor reports closed and
 *       "close" only when it reports not closed. Never send a command toward the state the
 *       sensor already reports, because the device may treat it as a button press.</li>
 *   <li><b>One movement at a time, until checked.</b> After a command, no other command is
 *       sent until Casita has checked the sensor, at {@code travelTime + verifyGrace} (or
 *       earlier for a close, once the sensor reports closed). A check that is overdue runs
 *       before any new command, so a failure is never skipped and never mixed up with a
 *       newer movement.</li>
 *   <li><b>Unknown outcomes are not failures.</b> If a command fails without a guarantee that
 *       nothing was sent, Casita assumes the door may be moving, blocks other commands, and
 *       checks after the travel time. It never says "nothing moved" unless the device
 *       guarantees it, and it never retries on its own.</li>
 *   <li><b>Let a "not closed" door settle.</b> A "not closed" reading may mean the door is
 *       moving because of the wall button or remote, or Casita may have just started and not
 *       know. Casita sends "close" only after it has watched the sensor report "not closed"
 *       for at least {@code travelTime}. "Closed" is the only reading that proves the door's
 *       position.</li>
 *   <li><b>Say what happened.</b> Every command and check is recorded; failed or unconfirmed
 *       movements are pushed to the family.</li>
 *   <li>Closing from the app can be switched off.</li>
 * </ol>
 *
 * <h2>What Casita reports</h2>
 * The sensor proves only "closed". So the status ({@link DoorState}) is {@code CLOSED} (proven),
 * {@code NOT_CLOSED} (settled; open is inferred, never verified), {@code OPENING} / {@code CLOSING}
 * (a command is being checked), {@code CHECKING} (moving or not is unknown) or {@code UNKNOWN} (the
 * sensor can't be read). In the activity log, every {@code MOVING} or {@code UNCONFIRMED} command is
 * followed by exactly one check: {@code CLOSED_CONFIRMED}, {@code OPEN_INFERRED}, {@code STUCK} (not
 * where it was sent after travel time + grace) or {@code LOST} (sensor unreadable). The check is
 * scheduled, and an overdue one also runs before any status or command, so no movement stays
 * "moving".
 *
 * <p>All state is in memory. A restart forgets any unverified movement, so rule 5 applies
 * after startup. These rules are tested against a fake door only; how they hold up with the
 * real opener is listed as hardware prerequisites in the design doc.
 */
@Service
public class GarageService {

    static final String CASITA = "Casita";

    private static final Logger log = LoggerFactory.getLogger(GarageService.class);

    /** A command Casita sent (or may have sent) that hasn't been checked yet. */
    private record Movement(long id, Action action, String who, Instant startedAt, boolean acknowledged) {}

    private final DoorDevice door;
    private final ActivityLog activity;
    private final Notifier notifier;
    private final DelayedTasks tasks;
    private final Clock clock;
    private final GarageProperties properties;

    // All guarded by this.
    private Movement pending;
    private long movementIds;
    /** The sensor value Casita last saw, when it first saw it, and whether it saw it change. */
    private Boolean lastSensorOpen;
    private Instant lastSensorChange;
    private boolean sawChange;

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
        Movement m = pending;
        if (m != null && isDue(m)) {
            check(m, null);
            m = null;
        }

        DoorDevice.Reading reading;
        try {
            reading = readSensor();
        } catch (DoorException e) {
            log.warn("Can't read the garage door: {}", e.getMessage());
            return new GarageStatus(DoorState.UNKNOWN, null, false, false);
        }

        if (m != null) {
            if (!m.acknowledged()) return new GarageStatus(DoorState.CHECKING, m.startedAt(), false, false);
            // "Not closed" proves nothing about an opening door, so wait for the check.
            if (m.action() == Action.OPEN) return new GarageStatus(DoorState.OPENING, m.startedAt(), false, false);
            if (reading.open()) return new GarageStatus(DoorState.CLOSING, m.startedAt(), false, false);
            // "Closed" proves the close finished: check it now.
            check(m, reading);
        }

        if (!reading.open()) {
            return new GarageStatus(DoorState.CLOSED, since(reading), true, false);
        }
        if (!settled()) {
            return new GarageStatus(sawChange ? DoorState.OPENING : DoorState.CHECKING, lastSensorChange, false, false);
        }
        return new GarageStatus(DoorState.NOT_CLOSED, since(reading), false, properties.allowRemoteClose());
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
        if (pending != null) {
            if (!isDue(pending)) {
                return result(Outcome.BUSY, "Give me a moment, I'm still checking on the door.");
            }
            check(pending, null); // overdue: settle the last movement before anything new
        }

        DoorDevice.Reading reading;
        try {
            reading = readSensor();
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
        if (reading.open() && !settled()) {
            return result(Outcome.BUSY,
                    "The door may still be moving, maybe from the wall button or remote. Give it a few seconds.");
        }

        long id = ++movementIds;
        try {
            door.command(wantOpen);
        } catch (DoorCommandRejectedException e) {
            log.warn("Couldn't {} the garage, nothing was sent: {}", action, e.getMessage());
            record(who, action, Outcome.ERROR);
            return result(Outcome.ERROR, "I couldn't reach the door. Nothing was sent, so nothing moved.");
        } catch (DoorException e) {
            log.warn("{} command for the garage may or may not have gone through: {}", action, e.getMessage());
            start(new Movement(id, action, who, clock.instant(), false));
            record(who, action, Outcome.UNCONFIRMED);
            notifier.send("I'm not sure the garage door got " + who + "'s " + verb(action)
                    + " command. I'll check in a few seconds.");
            return result(Outcome.UNCONFIRMED, "I'm not sure the door got that. I'll check in a few seconds and let you know.");
        }

        start(new Movement(id, action, who, clock.instant(), true));
        record(who, action, Outcome.MOVING);
        notifier.send((wantOpen ? "Opening the garage for " : "Closing the garage for ") + who);
        return result(Outcome.MOVING, wantOpen ? "Opening up for you." : "Closing up.");
    }

    private void start(Movement m) {
        pending = m;
        tasks.runAt(dueAt(m), () -> checkScheduled(m.id()));
    }

    /** The scheduled check. Does nothing if that movement was already checked. */
    synchronized void checkScheduled(long id) {
        if (pending != null && pending.id() == id) check(pending, null);
    }

    /**
     * Checks one movement against the sensor and clears it. Uses {@code known} if the sensor
     * was just read, otherwise reads it.
     */
    private void check(Movement m, DoorDevice.Reading known) {
        pending = null;
        DoorDevice.Reading reading = known;
        if (reading == null) {
            try {
                reading = readSensor();
            } catch (DoorException e) {
                record(CASITA, m.action(), Outcome.LOST);
                notifier.send("I lost sight of the garage door after "
                        + (m.acknowledged() ? verbIng(m.action()) : "trying to " + verb(m.action()))
                        + " it. Please check it.");
                return;
            }
        }

        if (reading.open() == m.action().wantsOpen()) {
            // The door has had the full travel time (or the sensor reports closed), so it's settled.
            if (lastSensorChange == null || lastSensorChange.isAfter(m.startedAt())) lastSensorChange = m.startedAt();
            // Always a final record, so the history never ends on MOVING. "Closed" is proven by the sensor;
            // after an open, "not closed" is all it can say.
            record(CASITA, m.action(), m.action() == Action.CLOSE ? Outcome.CLOSED_CONFIRMED : Outcome.OPEN_INFERRED);
            if (!m.acknowledged()) {
                notifier.send("It went through after all: the garage is " + (reading.open() ? "not closed." : "closed."));
            } else if (m.action() == Action.CLOSE) {
                notifier.send("The garage is closed. All tucked in.");
            }
            return;
        }

        record(CASITA, m.action(), Outcome.STUCK);
        if (!m.acknowledged()) {
            notifier.send("The garage door is still " + (reading.open() ? "not closed" : "closed")
                    + ". My " + verb(m.action()) + " command may not have reached it. Please check before trying again.");
        } else if (m.action() == Action.OPEN) {
            notifier.send("The garage door didn't open. Please check it.");
        } else {
            notifier.send("The garage door didn't close. Something may be in the way. Please check it.");
        }
    }

    /** Reads the sensor and notes when Casita first saw its current value. */
    private DoorDevice.Reading readSensor() throws DoorException {
        DoorDevice.Reading reading = door.read();
        if (lastSensorOpen == null) {
            lastSensorOpen = reading.open();
            lastSensorChange = clock.instant(); // just started: history unknown
            sawChange = false;
        } else if (lastSensorOpen != reading.open()) {
            lastSensorOpen = reading.open();
            lastSensorChange = clock.instant();
            sawChange = true;
        }
        return reading;
    }

    /** True once Casita has seen the sensor's current value for at least the travel time. */
    private boolean settled() {
        return lastSensorChange != null && !clock.instant().isBefore(lastSensorChange.plus(properties.travelTime()));
    }

    private Instant dueAt(Movement m) {
        return m.startedAt().plus(properties.travelTime()).plus(properties.verifyGrace());
    }

    private boolean isDue(Movement m) {
        return !clock.instant().isBefore(dueAt(m));
    }

    private Instant since(DoorDevice.Reading reading) {
        return reading.changedAt() != null ? reading.changedAt() : lastSensorChange;
    }

    private void record(String who, Action action, Outcome outcome) {
        activity.record(new ActivityEvent(clock.instant(), who, action, outcome));
    }

    private CommandResult result(Outcome outcome, String message) {
        return new CommandResult(outcome, message, status());
    }

    private static String verb(Action action) {
        return action == Action.OPEN ? "open" : "close";
    }

    private static String verbIng(Action action) {
        return action == Action.OPEN ? "opening" : "closing";
    }
}
