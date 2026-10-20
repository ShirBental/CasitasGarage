package home.casita.garage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Casita's rules for one garage door. There is one of these per door ({@link Garage}), so each door has its
 * own movement, settle watch, hold and resume, and its own activity entries: Ford and Toyota never block,
 * hold or check each other. See {@link DoorDevice} for what the device does and
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
 *   <li><b>After a failure, wait for a person.</b> A movement that ended {@code STUCK} or {@code LOST}
 *       leaves the door's position and the command's fate uncertain (an obstruction, an auto-reverse,
 *       a late command). Every command is then refused ({@code HELD}) until someone has checked the
 *       door and calls {@link #resume}; a later sensor reading alone never lifts it.</li>
 *   <li>Commands for a door can be switched off ({@link DoorControl#OFF}): every command is then refused
 *       ({@code DISABLED}) whatever the door reads, and nothing is sent. Closing from the app can be switched off for every door.</li>
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
public class GarageService {

    static final String CASITA = "Casita";

    private static final Logger log = LoggerFactory.getLogger(GarageService.class);

    /** A command Casita sent (or may have sent) that hasn't been checked yet. */
    private record Movement(long id, Action action, String who, Instant startedAt, boolean acknowledged) {}

    private final String name;
    private final DoorDevice door;
    private final DoorControl control;
    private final ActivityLog activity;
    private final Notifier notifier;
    private final DelayedTasks tasks;
    private final Clock clock;
    private final GarageProperties properties;
    private final Duration travelTime;

    // All guarded by this.
    private Movement pending;
    private long movementIds;
    /** The sensor value Casita last saw, when it first saw it, and whether it saw it change. */
    private Boolean lastSensorOpen;
    private Instant lastSensorChange;
    private boolean sawChange;
    /** The action of the movement that ended stuck or lost, while commands are held; null when not held. */
    private Action heldAfter;

    /**
     * @param name    the door's name (Ford, Toyota), in its activity entries and notifications
     * @param control how this door's commands are handled; {@link DoorControl#OFF} refuses them all
     */
    public GarageService(String name, DoorDevice door, DoorControl control, ActivityLog activity, Notifier notifier,
                         DelayedTasks tasks, Clock clock, GarageProperties properties) {
        this.name = name;
        this.door = door;
        this.control = control;
        this.activity = activity;
        this.notifier = notifier;
        this.tasks = tasks;
        this.clock = clock;
        this.properties = properties;
        this.travelTime = properties.travelTime(name);
    }

    public String name() {
        return name;
    }

    /** The door in API paths: {@code ford}, {@code toyota}. */
    public String key() {
        return DoorRoster.Door.key(name);
    }

    public DoorControl control() {
        return control;
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
            log.warn("Can't read the {} door: {}", name, e.getMessage());
            return new GarageStatus(DoorState.UNKNOWN, null, false, false, held());
        }

        if (m != null) {
            if (!m.acknowledged()) return new GarageStatus(DoorState.CHECKING, m.startedAt(), false, false, held());
            // "Not closed" proves nothing about an opening door, so wait for the check.
            if (m.action() == Action.OPEN) return new GarageStatus(DoorState.OPENING, m.startedAt(), false, false, held());
            if (reading.open()) return new GarageStatus(DoorState.CLOSING, m.startedAt(), false, false, held());
            // "Closed" proves the close finished: check it now.
            check(m, reading);
        }

        if (!reading.open()) {
            return new GarageStatus(DoorState.CLOSED, since(reading), !held(), false, held());
        }
        if (!settled()) {
            return new GarageStatus(sawChange ? DoorState.OPENING : DoorState.CHECKING, lastSensorChange, false, false, held());
        }
        return new GarageStatus(DoorState.NOT_CLOSED, since(reading), false, properties.allowRemoteClose() && !held(), held());
    }

    /**
     * Someone has checked the door after a stuck or lost movement: allow commands again. The sensor must
     * be readable, or the hold stays. The settle watch restarts, so an open door is watched for the
     * travel time again before it may be closed. Nothing is sent. Does nothing when not held.
     */
    public synchronized GarageStatus resume(String who) {
        if (heldAfter != null) {
            try {
                readSensor();
            } catch (DoorException e) {
                log.warn("Won't resume {}: can't read the door: {}", name, e.getMessage());
                return status();
            }
            record(who, heldAfter, Outcome.RESUMED);
            heldAfter = null;
            lastSensorChange = clock.instant();
            sawChange = false;
        }
        return status();
    }

    private boolean held() {
        return heldAfter != null;
    }

    public synchronized CommandResult open(String who) {
        if (control == DoorControl.OFF) return switchedOff(Action.OPEN, who);
        return move(Action.OPEN, who);
    }

    public synchronized CommandResult close(String who) {
        if (control == DoorControl.OFF) return switchedOff(Action.CLOSE, who);
        if (!properties.allowRemoteClose()) {
            record(who, Action.CLOSE, Outcome.DISABLED);
            return result(Outcome.DISABLED, "Closing from the app is turned off. Please use the wall button.");
        }
        return move(Action.CLOSE, who);
    }

    /** This door's commands are switched off: refused whatever the door reads, and nothing is sent. */
    private CommandResult switchedOff(Action action, String who) {
        record(who, action, Outcome.DISABLED);
        return result(Outcome.DISABLED, "Moving " + name + " from the app is turned off. Please use the wall button or remote.");
    }

    private CommandResult move(Action action, String who) {
        if (pending != null) {
            if (!isDue(pending)) {
                return result(Outcome.BUSY, "Give me a moment, I'm still checking on the door.");
            }
            check(pending, null); // overdue: settle the last movement before anything new
        }
        if (held()) {
            record(who, action, Outcome.HELD);
            return result(Outcome.HELD, "My last move didn't finish as expected, so I won't move the door until someone has "
                    + "checked it and tells me it's OK.");
        }

        DoorDevice.Reading reading;
        try {
            reading = readSensor();
        } catch (DoorException e) {
            log.warn("Won't {} {}: can't read the door: {}", action, name, e.getMessage());
            record(who, action, Outcome.UNKNOWN);
            return result(Outcome.UNKNOWN, "I can't see the door right now, so I won't touch it.");
        }

        boolean wantOpen = action.wantsOpen();
        if (reading.open() == wantOpen) {
            record(who, action, Outcome.ALREADY);
            return result(Outcome.ALREADY, wantOpen ? "It isn't closed, so there's nothing to open." : "It's already closed.");
        }
        if (reading.open() && !settled()) {
            return result(Outcome.BUSY,
                    "The door may still be moving, maybe from the wall button or remote. Give it a few seconds.");
        }

        long id = ++movementIds;
        try {
            door.command(wantOpen);
        } catch (DoorCommandRejectedException e) {
            log.warn("Couldn't {} {}, nothing was sent: {}", action, name, e.getMessage());
            record(who, action, Outcome.ERROR);
            return result(Outcome.ERROR, "I couldn't reach the door. Nothing was sent, so nothing moved.");
        } catch (DoorException e) {
            log.warn("{} command for {} may or may not have gone through: {}", action, name, e.getMessage());
            start(new Movement(id, action, who, clock.instant(), false));
            record(who, action, Outcome.UNCONFIRMED);
            notifier.send("I'm not sure the " + name + " door got " + who + "'s " + verb(action)
                    + " command. I'll check in a few seconds.");
            return result(Outcome.UNCONFIRMED, "I'm not sure the door got that. I'll check in a few seconds and let you know.");
        }

        start(new Movement(id, action, who, clock.instant(), true));
        record(who, action, Outcome.MOVING);
        notifier.send((wantOpen ? "Opening the " : "Closing the ") + name + " door for " + who);
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
                heldAfter = m.action();
                notifier.send("I lost sight of the " + name + " door after "
                        + (m.acknowledged() ? verbIng(m.action()) : "trying to " + verb(m.action()))
                        + " it. Please check it; I won't move it again until someone tells me it's OK.");
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
                notifier.send("It went through after all: the " + name + " door is " + (reading.open() ? "not closed." : "closed."));
            } else if (m.action() == Action.CLOSE) {
                notifier.send("The " + name + " door is closed. All tucked in.");
            }
            return;
        }

        record(CASITA, m.action(), Outcome.STUCK);
        heldAfter = m.action();
        if (!m.acknowledged()) {
            notifier.send("The " + name + " door is still " + (reading.open() ? "not closed" : "closed")
                    + ". My " + verb(m.action()) + " command may not have reached it. Please check it; I won't move it again until "
                    + "someone tells me it's OK.");
        } else if (m.action() == Action.OPEN) {
            notifier.send("The " + name + " door didn't open. Please check it; I won't move it again until someone tells me it's OK.");
        } else {
            notifier.send("The " + name + " door didn't close. Something may be in the way. Please check it; I won't move it again "
                    + "until someone tells me it's OK.");
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
        return lastSensorChange != null && !clock.instant().isBefore(lastSensorChange.plus(travelTime));
    }

    private Instant dueAt(Movement m) {
        return m.startedAt().plus(travelTime).plus(properties.verifyGrace());
    }

    private boolean isDue(Movement m) {
        return !clock.instant().isBefore(dueAt(m));
    }

    private Instant since(DoorDevice.Reading reading) {
        return reading.changedAt() != null ? reading.changedAt() : lastSensorChange;
    }

    private void record(String who, Action action, Outcome outcome) {
        activity.record(new ActivityEvent(clock.instant(), name, who, action, outcome));
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
