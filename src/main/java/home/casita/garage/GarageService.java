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
 *       sent until Casita has checked the sensor, at {@code travelTime + sensorLag + verifyGrace} (or
 *       earlier for a close, once the sensor reports closed). A check that is overdue runs
 *       before any new command, so a failure is never skipped and never mixed up with a
 *       newer movement. The one exception is rule 9.</li>
 *   <li><b>Unknown outcomes are not failures.</b> If a command fails without a guarantee that
 *       nothing was sent, Casita assumes the door may be moving, blocks other commands, and
 *       checks after the travel time. It never says "nothing moved" unless the device
 *       guarantees it, and it never retries on its own.</li>
 *   <li><b>Let a "not closed" door settle.</b> A "not closed" reading may mean the door is
 *       moving because of the wall button or remote, or Casita may have just started and not
 *       know. Casita sends "close" only after it has watched the sensor report "not closed"
 *       for at least {@code travelTime + sensorLag} (the door's reading can trail the door by up to
 *       {@code sensorLag}). "Closed" is the only reading that proves the door's position.</li>
 *   <li><b>Say what happened.</b> Every command and check is recorded; failed or unconfirmed
 *       movements are pushed to the family.</li>
 *   <li><b>After a failure, wait for a person.</b> A movement that ended {@code STUCK} or {@code LOST}
 *       leaves the door's position and the command's fate uncertain (an obstruction, an auto-reverse,
 *       a late command). Every command is then refused ({@code HELD}) until someone has checked the
 *       door and calls {@link #resume}; a later sensor reading alone never lifts it.</li>
 *   <li>Commands for a door can be switched off ({@link DoorControl#OFF}): every command is then refused
 *       ({@code DISABLED}) whatever the door reads, and nothing is sent. Closing from the app can be switched off for every door.</li>
 *   <li><b>The opposite command after Casita's own movement waits for the door's travel, not the sensor's delay.</b>
 *       The waits in rules 3 and 5 have two parts: {@code travelTime}, how long the door itself can still be moving, and
 *       {@code sensorLag}, how long its reading can trail it. After Casita's own acknowledged open, a close (from the
 *       app or Siri, with or without "I've looked") is allowed from {@code travelTime} after the command, when the
 *       sensor is readable and already reports not closed, nothing is held, and Casita has seen no change since that
 *       open other than its own. After a door Casita saw change on its own (the wall button, the remote), a close is
 *       allowed {@code travelTime} after it saw the change. Never right after a start or a resume (no change seen: the
 *       full {@code travelTime + sensorLag}), never against a reading of "closed" (the opener could take the close as a
 *       button press), never before {@code travelTime}. Skipping {@code sensorLag} can't move a door that is still
 *       travelling from a command Casita knows of. What it can't rule out (a press Casita can't see, a reading stale at
 *       "closed" when the open was sent) makes a close stop or reverse the door instead of closing it: rule 10 detects
 *       and reports that. A person's "I've looked" ({@link #close(String, boolean)}) is recorded ({@code CHECKED})
 *       but changes no timing.</li>
 *   <li><b>A close succeeds only if the door stays closed while its reading can still catch up.</b> While its own
 *       acknowledged command is pending, Casita reads the sensor every {@link #WATCH_EVERY} until the check is due, even
 *       with nobody asking. A close that reads "closed" lets an open be sent at once, but it is recorded as
 *       {@code CLOSED_CONFIRMED}, and the family told, only if the reading still says "closed" at its check
 *       ({@code travelTime + sensorLag} after the command) or when that open is sent. A reading that goes back to
 *       "not closed" before then (an auto-reverse at touch-down, a press, a stale reading catching up) ends it
 *       {@code STUCK}: held, and the family told it didn't stay closed.</li>
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

    /** How often the sensor is read while Casita's own acknowledged command is pending (rule 10). */
    static final Duration WATCH_EVERY = Duration.ofSeconds(2);

    private static final Logger log = LoggerFactory.getLogger(GarageService.class);

    /** A command Casita sent (or may have sent) that hasn't been checked yet. */
    private record Movement(long id, Action action, String who, Instant startedAt, boolean acknowledged, long changesAtStart) {}

    private final String name;
    private final DoorDevice door;
    private final DoorControl control;
    private final ActivityLog activity;
    private final Notifier notifier;
    private final DelayedTasks tasks;
    private final Clock clock;
    private final GarageProperties properties;
    private final Duration travelTime;
    /** Travel time plus how long the reading can trail the door: what settling and checks wait for. */
    private final Duration settleTime;

    // All guarded by this.
    private Movement pending;
    private long movementIds;
    /** The sensor value Casita last saw, when it first saw it, and whether it saw it change. */
    private Boolean lastSensorOpen;
    private Instant lastSensorChange;
    private boolean sawChange;
    /** How many changes of the reading Casita has seen, ever: a movement compares it with its own start. */
    private long sensorChanges;
    /** When Casita first read "closed" during its pending acknowledged close (rule 10); null otherwise. */
    private Instant closedSeenAt;
    /** When the sensor was last read successfully, so the watch doesn't read again right after someone else did. */
    private Instant lastReadAt;
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
        this.settleTime = travelTime.plus(properties.sensorLag(name));
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

        m = pending; // the read may have ended it (rule 10: a close that didn't stay closed)
        if (m != null) {
            if (!m.acknowledged()) return new GarageStatus(DoorState.CHECKING, m.startedAt(), false, false, held());
            // "Not closed" proves nothing about an opening door: closable from travel time (rule 9), settled at the check.
            if (m.action() == Action.OPEN) {
                return new GarageStatus(DoorState.OPENING, m.startedAt(), false,
                        mayClose() && reading.open() && travelled(m) && onlyItsOwnChange(m) && !deviceSawARecentChange(reading), held());
            }
            if (reading.open()) return new GarageStatus(DoorState.CLOSING, m.startedAt(), false, false, held());
            // Reads "closed": an open may go now, but the close isn't confirmed until its check (rule 10): still closing.
            return new GarageStatus(DoorState.CLOSING, closedSeenAt, !held(), false, held());
        }

        if (!reading.open()) {
            return new GarageStatus(DoorState.CLOSED, since(reading), !held(), false, held());
        }
        if (!settled()) {
            return new GarageStatus(sawChange ? DoorState.OPENING : DoorState.CHECKING, lastSensorChange, false,
                    mayClose() && sawChange && travelledSinceSeenNotClosed() && !deviceSawARecentChange(reading), held());
        }
        return new GarageStatus(DoorState.NOT_CLOSED, since(reading), false, mayClose(), held());
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
        return move(Action.OPEN, who, false);
    }

    public synchronized CommandResult close(String who) {
        return close(who, false);
    }

    /**
     * Close, saying whether {@code who} has just looked at the door and seen it fully open and stopped. The look is
     * recorded ({@code CHECKED}) but changes no timing: every close follows rule 9.
     */
    public synchronized CommandResult close(String who, boolean checkedByPerson) {
        if (control == DoorControl.OFF) return switchedOff(Action.CLOSE, who);
        if (!properties.allowRemoteClose()) {
            record(who, Action.CLOSE, Outcome.DISABLED);
            return result(Outcome.DISABLED, "Closing from the app is turned off. Please use the wall button.");
        }
        return move(Action.CLOSE, who, checkedByPerson);
    }

    /** This door's commands are switched off: refused whatever the door reads, and nothing is sent. */
    private CommandResult switchedOff(Action action, String who) {
        record(who, action, Outcome.DISABLED);
        return result(Outcome.DISABLED, "Moving " + name + " from the app is turned off. Please use the wall button or remote.");
    }

    private CommandResult move(Action action, String who, boolean checkedByPerson) {
        boolean close = action == Action.CLOSE;
        // Casita's own acknowledged open, travelled but not yet checked: a close may end it (rule 9).
        Movement travelledOpen = null;
        // Casita's own close that reads "closed" but isn't checked yet: an open may follow it (rule 10).
        Movement closeToSupersede = null;
        DoorDevice.Reading reading = null;
        if (pending != null) {
            if (isDue(pending)) {
                check(pending, null); // overdue: settle the last movement before anything new
            } else if (close && pending.action() == Action.OPEN && pending.acknowledged() && !travelled(pending)) {
                return result(Outcome.BUSY, "The door may still be opening. Give it a few more seconds.");
            } else if (close && travelled(pending)) {
                travelledOpen = pending;
            } else if (!close && pending.action() == Action.CLOSE && pending.acknowledged()) {
                // Rule 10: an open may follow a close that reads "closed"; one that didn't stay closed ends STUCK.
                try {
                    reading = readSensor();
                } catch (DoorException e) {
                    log.warn("Won't open {}: can't read the door: {}", name, e.getMessage());
                    return result(Outcome.BUSY, "I can't see the door right now, so I won't touch it.");
                }
                if (pending != null && reading.open()) {
                    return result(Outcome.BUSY, "The door may still be closing. Give it a few more seconds.");
                }
                closeToSupersede = pending;
            } else {
                return result(Outcome.BUSY, "Give me a moment, I'm still checking on the door.");
            }
        }
        if (held()) {
            record(who, action, Outcome.HELD);
            return result(Outcome.HELD, "My last move didn't finish as expected, so I won't move the door until someone has "
                    + "checked it and tells me it's OK.");
        }

        if (reading == null) {
            try {
                reading = readSensor();
            } catch (DoorException e) {
                log.warn("Won't {} {}: can't read the door: {}", action, name, e.getMessage());
                record(who, action, Outcome.UNKNOWN);
                return result(Outcome.UNKNOWN, "I can't see the door right now, so I won't touch it.");
            }
        }

        boolean wantOpen = action.wantsOpen();
        if (reading.open() == wantOpen) {
            if (travelledOpen != null) {
                // The open is still pending (it isn't checked yet): the sensor may simply not have caught up.
                return result(Outcome.BUSY, "The sensor still says closed, so I can't send a close yet: it hasn't caught up "
                        + "with the door. Give it a few seconds.");
            }
            record(who, action, Outcome.ALREADY);
            return result(Outcome.ALREADY, wantOpen ? "It isn't closed, so there's nothing to open." : "It's already closed.");
        }
        if (travelledOpen != null) {
            // The sensor has left "closed" since the open and the door has had its full travel time: that open is done.
            // (A reading still at "closed" was refused just above, as BUSY: the sensor hasn't caught up.) But only if
            // that one change is all Casita has seen since the open: anything more (the wall button reversing it, then
            // opening it again) is a newer movement, which may still be under way.
            if (!onlyItsOwnChange(travelledOpen) || deviceSawARecentChange(reading)) {
                return result(Outcome.BUSY, "The door has moved again since I opened it. Give it a few more seconds.");
            }
            pending = null;
            if (lastSensorChange == null || lastSensorChange.isAfter(travelledOpen.startedAt())) lastSensorChange = travelledOpen.startedAt();
            record(CASITA, Action.OPEN, Outcome.OPEN_INFERRED);
        }
        if (reading.open() && !settled()) {
            if (!travelledSinceSeenNotClosed() || deviceSawARecentChange(reading)) {
                return result(Outcome.BUSY, "The door may still be moving. Give it a few more seconds.");
            }
            if (!sawChange && travelledOpen == null) {
                // Just started or resumed: Casita didn't see this door open, and a command from before (one whose fate was
                // unknown) could still land, so wait for the full settle, look or no look.
                return result(Outcome.BUSY, "I've only just started watching this door. Give it a few more seconds.");
            }
            // Rule 9: the door has had its travel time since the last movement Casita knows of.
            if (checkedByPerson) record(who, Action.CLOSE, Outcome.CHECKED);
        }

        long id = ++movementIds;
        try {
            door.command(wantOpen);
        } catch (DoorCommandRejectedException e) {
            // Nothing was sent: a close being superseded keeps its own check.
            log.warn("Couldn't {} {}, nothing was sent: {}", action, name, e.getMessage());
            record(who, action, Outcome.ERROR);
            return result(Outcome.ERROR, "I couldn't reach the door. Nothing was sent, so nothing moved.");
        } catch (DoorException e) {
            log.warn("{} command for {} may or may not have gone through: {}", action, name, e.getMessage());
            if (closeToSupersede != null) supersede(closeToSupersede);
            start(new Movement(id, action, who, clock.instant(), false, sensorChanges));
            record(who, action, Outcome.UNCONFIRMED);
            notifier.send("I'm not sure the " + name + " door got " + who + "'s " + verb(action)
                    + " command. I'll check in a few seconds.");
            return result(Outcome.UNCONFIRMED, "I'm not sure the door got that. I'll check in a few seconds and let you know.");
        }

        if (closeToSupersede != null) supersede(closeToSupersede);
        start(new Movement(id, action, who, clock.instant(), true, sensorChanges));
        record(who, action, Outcome.MOVING);
        notifier.send((wantOpen ? "Opening the " : "Closing the ") + name + " door for " + who);
        return result(Outcome.MOVING, wantOpen ? "Opening up for you." : "Closing up.");
    }

    private void start(Movement m) {
        pending = m;
        closedSeenAt = null;
        tasks.runAt(dueAt(m), () -> checkScheduled(m.id()));
        if (m.acknowledged()) watchLater(m);
    }

    private void watchLater(Movement m) {
        Instant next = clock.instant().plus(WATCH_EVERY);
        if (next.isBefore(dueAt(m))) tasks.runAt(next, () -> watch(m.id()));
    }

    /**
     * Rule 10: one read while this acknowledged movement is pending, so a change is seen when it happens even if nobody
     * is asking, until its check is due. For an open, a second change (the wall button reversing it) refuses a close; for
     * a close, a reading back at "not closed" after "closed" ends it STUCK. A failed read changes nothing (the scheduled
     * check decides).
     */
    synchronized void watch(long id) {
        Movement m = pending;
        if (m == null || m.id() != id || isDue(m)) return;
        if (lastReadAt != null && clock.instant().isBefore(lastReadAt.plus(WATCH_EVERY))) {
            watchLater(m); // read just now (the app polling, a command): no need for another
            return;
        }
        try {
            readSensor();
        } catch (DoorException | RuntimeException e) {
            // nothing changes: the scheduled check decides
        }
        if (pending == m) watchLater(m);
    }

    /**
     * Rule 10: the pending close read "closed" and an open has just been sent. The close ends without being confirmed
     * ({@code SUPERSEDED}, nobody told it closed): it never had the time to show it stayed closed. Called only once the
     * open has gone (or may have gone); an open that definitely wasn't sent leaves the close to its own check.
     */
    private void supersede(Movement m) {
        if (pending == m) pending = null;
        record(CASITA, Action.CLOSE, Outcome.SUPERSEDED);
    }

    /** Rule 10: the pending close read "closed", then "not closed" again, before its check. */
    private void didntStayClosed(Movement m) {
        pending = null;
        record(CASITA, Action.CLOSE, Outcome.STUCK);
        heldAfter = Action.CLOSE;
        notifier.send("The " + name + " door closed, then opened again (the opener may have reversed: something in the way?). "
                + "Please check it; I won't move it again until someone tells me it's OK.");
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
            } catch (DoorException | RuntimeException e) { // any failure to read is a lost sight, never a silent end
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
            // Only an acknowledged command's start stands for the door's: one of unknown fate may have reached the opener
            // late, and the door may have started then, so Casita keeps the time it actually saw the change.
            if (m.acknowledged() && onlyItsOwnChange(m) && (lastSensorChange == null || lastSensorChange.isAfter(m.startedAt()))) {
                lastSensorChange = m.startedAt();
            }
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
            notifier.send("The " + name + " door didn't close (something in the way, the opener reversing, or a button pressed). "
                    + "Please check it; I won't move it again until someone tells me it's OK.");
        }
    }

    /** Reads the sensor and notes when Casita first saw its current value. */
    private DoorDevice.Reading readSensor() throws DoorException {
        DoorDevice.Reading reading = door.read();
        lastReadAt = clock.instant();
        if (lastSensorOpen == null) {
            lastSensorOpen = reading.open();
            lastSensorChange = clock.instant(); // just started: history unknown
            sawChange = false;
        } else if (lastSensorOpen != reading.open()) {
            lastSensorOpen = reading.open();
            lastSensorChange = clock.instant();
            sawChange = true;
            sensorChanges++;
        }
        Movement m = pending;
        if (m != null && m.acknowledged() && m.action() == Action.CLOSE) {
            if (!reading.open() && closedSeenAt == null) closedSeenAt = clock.instant();
            else if (reading.open() && closedSeenAt != null) didntStayClosed(m);
        }
        return reading;
    }

    /** Casita's own acknowledged open, sent at least the travel time ago: the door has stopped moving, whatever the sensor says. */
    private boolean travelled(Movement m) {
        return m.acknowledged() && m.action() == Action.OPEN && !clock.instant().isBefore(m.startedAt().plus(travelTime));
    }

    /** Since {@code m} started, Casita has seen at most the one change it caused, so its start can stand for the door's. */
    private boolean onlyItsOwnChange(Movement m) {
        return sensorChanges - m.changesAtStart() <= 1;
    }

    /**
     * The opener says its reading changed less than the travel time ago (when it says at all; ours reports no change
     * time): a change Casita may have missed between two reads. Used only to refuse, never to allow.
     */
    private boolean deviceSawARecentChange(DoorDevice.Reading reading) {
        return reading.changedAt() != null && reading.changedAt().isAfter(clock.instant().minus(travelTime));
    }

    /** Casita has seen the current "not closed" for at least the travel time: any movement it could know of has ended. */
    private boolean travelledSinceSeenNotClosed() {
        return lastSensorChange != null && !clock.instant().isBefore(lastSensorChange.plus(travelTime));
    }

    /** Whether a close could go through at all (commands on, remote close allowed, nothing held), before any timing. */
    private boolean mayClose() {
        return control != DoorControl.OFF && properties.allowRemoteClose() && !held();
    }

    /** True once Casita has seen the sensor's current value for at least the travel time plus the sensor's lag. */
    private boolean settled() {
        return lastSensorChange != null && !clock.instant().isBefore(lastSensorChange.plus(settleTime));
    }

    private Instant dueAt(Movement m) {
        return m.startedAt().plus(settleTime).plus(properties.verifyGrace());
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
