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
 *   <li><b>A person at the door may stand in for the sensor's delay, never for the door's travel.</b> The waits in rules 3
 *       and 5 have two parts: {@code travelTime}, how long the door itself can still be moving, and {@code sensorLag},
 *       how long its reading can trail it. A close that comes with "I've checked the door: it's fully open and stopped"
 *       ({@link #close(String, boolean)}) skips only the lag part, and only when the sensor is readable and already
 *       reports not closed, nothing is held, no command of unknown outcome is pending, and Casita has seen "not closed"
 *       (or, after its own acknowledged open with no other change seen since, sent that open) at least
 *       {@code travelTime} ago, so any movement it could know about has finished; not right after a start or a resume,
 *       when a command of unknown fate could still land. The person's word alone never moves a door: a reading of "closed" (a sensor that
 *       hasn't caught up with an open) still refuses, because the opener could take the close as a button press. The
 *       check is recorded ({@code CHECKED}) with who made it, before the close.</li>
 *   <li><b>Seen moving, then stopped: only for a door it is switched on for.</b> For a door listed in
 *       {@code casita.garage.settle-by-sight} (none by default), Casita reads the sensor every {@link #WATCH_EVERY}
 *       while its own acknowledged open is pending, until the check is due (the app or Siri may not be asking). An open
 *       whose reading it saw leave "closed", with no other change since, counts as settled one {@code travelTime} after
 *       that sighting instead of after {@code travelTime + sensorLag}. Other doors aren't watched and behave exactly as
 *       rules 1–9 say. Off by default because a binary sensor can't prove it: a reading that was stale at
 *       "closed" when the open was sent (the door really up) makes the open a press that closes it, and the reading's
 *       late catch-up then looks exactly like the open's own change. Only a supervised test per door can rule that
 *       out.</li>
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
    /** Rule 10's settle by sight, for this door ({@code casita.garage.settle-by-sight}); off by default. */
    private final boolean settleBySight;

    // All guarded by this.
    private Movement pending;
    private long movementIds;
    /** The sensor value Casita last saw, when it first saw it, and whether it saw it change. */
    private Boolean lastSensorOpen;
    private Instant lastSensorChange;
    private boolean sawChange;
    /** How many changes of the reading Casita has seen, ever: a movement compares it with its own start. */
    private long sensorChanges;
    /**
     * When Casita read the sensor leaving "closed" during its own acknowledged open, that change being the only one since
     * the open (rule 10); null otherwise, and cleared by any later change, a new movement or a resume.
     */
    private Instant seenOpenAt;
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
        this.settleBySight = properties.settlesBySight(name);
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
            // "Not closed" proves nothing about an opening door, so wait for the check, unless someone checks the door.
            if (m.action() == Action.OPEN && seenStopped(m, reading)) {
                endSeenStopped(m);
                m = null;
            } else if (m.action() == Action.OPEN) {
                return new GarageStatus(DoorState.OPENING, m.startedAt(), false, false, held(),
                        mayCloseIfChecked() && reading.open() && travelled(m) && onlyItsOwnChange(m) && !deviceSawARecentChange(reading));
            }
            if (m != null && reading.open()) return new GarageStatus(DoorState.CLOSING, m.startedAt(), false, false, held());
            // "Closed" proves the close finished: check it now.
            if (m != null) check(m, reading);
        }

        if (!reading.open()) {
            return new GarageStatus(DoorState.CLOSED, since(reading), !held(), false, held());
        }
        if (!settled()) {
            return new GarageStatus(sawChange ? DoorState.OPENING : DoorState.CHECKING, lastSensorChange, false, false, held(),
                    mayCloseIfChecked() && sawChange && travelledSinceSeenNotClosed() && !deviceSawARecentChange(reading));
        }
        return new GarageStatus(DoorState.NOT_CLOSED, since(reading), false, mayCloseIfChecked(), held());
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
            seenOpenAt = null;
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
     * Close, saying whether {@code who} has just looked at the door and seen it fully open and stopped (rule 9). That
     * check lets the close skip the wait for the sensor's delay, never the door's travel time or any other rule; it
     * changes nothing when the door has already settled.
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
        boolean checkedClose = checkedByPerson && action == Action.CLOSE;
        // Casita's own acknowledged open, travelled but not yet checked: a checked close may end it (rule 9).
        Movement travelledOpen = null;
        DoorDevice.Reading reading = null;
        if (pending != null) {
            if (isDue(pending)) {
                check(pending, null); // overdue: settle the last movement before anything new
            } else if (action == Action.CLOSE && pending.action() == Action.OPEN && pending.acknowledged() && !travelled(pending)) {
                return result(Outcome.BUSY, "The door may still be opening. Give it a few more seconds.");
            } else if (!checkedClose && action == Action.CLOSE && pending.action() == Action.OPEN && pending.acknowledged()) {
                // Rule 10: Casita may have seen its open stop. Otherwise say why it waits.
                try {
                    reading = readSensor();
                } catch (DoorException e) {
                    log.warn("Won't close {}: can't read the door: {}", name, e.getMessage());
                    return result(Outcome.BUSY, "I can't see the door right now, so I won't touch it.");
                }
                if (seenStopped(pending, reading)) {
                    endSeenStopped(pending);
                } else if (!reading.open()) {
                    return result(Outcome.BUSY, "The sensor still says closed, so I can't send a close yet: it hasn't caught up "
                            + "with the door. Give it a few seconds.");
                } else {
                    return result(Outcome.BUSY, "I opened it a moment ago and the sensor hasn't settled yet. Give it a few seconds.");
                }
            } else if (checkedClose && travelled(pending)) {
                travelledOpen = pending;
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
            if (!checkedClose) {
                return result(Outcome.BUSY,
                        "The door may still be moving, maybe from the wall button or remote. Give it a few seconds.");
            }
            if (!travelledSinceSeenNotClosed() || deviceSawARecentChange(reading)) {
                return result(Outcome.BUSY, "The door may still be moving. Give it a few more seconds.");
            }
            if (!sawChange && travelledOpen == null) {
                // Just started or resumed: Casita didn't see this door open, and a command from before (one whose fate was
                // unknown) could still land. The person's look covers the door, not that, so wait for the full settle.
                return result(Outcome.BUSY, "I've only just started watching this door. Give it a few more seconds.");
            }
            // Rule 9: the person has seen it stopped; that stands in for the sensor's delay, and only for that.
            record(who, Action.CLOSE, Outcome.CHECKED);
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
            start(new Movement(id, action, who, clock.instant(), false, sensorChanges));
            record(who, action, Outcome.UNCONFIRMED);
            notifier.send("I'm not sure the " + name + " door got " + who + "'s " + verb(action)
                    + " command. I'll check in a few seconds.");
            return result(Outcome.UNCONFIRMED, "I'm not sure the door got that. I'll check in a few seconds and let you know.");
        }

        start(new Movement(id, action, who, clock.instant(), true, sensorChanges));
        record(who, action, Outcome.MOVING);
        notifier.send((wantOpen ? "Opening the " : "Closing the ") + name + " door for " + who);
        return result(Outcome.MOVING, wantOpen ? "Opening up for you." : "Closing up.");
    }

    private void start(Movement m) {
        pending = m;
        seenOpenAt = null;
        tasks.runAt(dueAt(m), () -> checkScheduled(m.id()));
        if (settleBySight && m.acknowledged() && m.action() == Action.OPEN) watchLater(m);
    }

    private void watchLater(Movement m) {
        Instant next = clock.instant().plus(WATCH_EVERY);
        if (next.isBefore(dueAt(m))) tasks.runAt(next, () -> watch(m.id()));
    }

    /**
     * Rule 10 (doors with settle by sight only): one read while this acknowledged open is pending, so a change is seen
     * when it happens even if nobody is asking, until its check is due. It keeps reading after the change is seen, so a
     * second change (the wall button reversing it) is seen too. A failed read changes nothing (the scheduled check
     * decides). It never checks a movement early: a close is still judged at its check, so a touch-down auto-reverse is
     * caught as STUCK.
     */
    synchronized void watch(long id) {
        Movement m = pending;
        if (m == null || m.id() != id || isDue(m)) return;
        if (lastReadAt != null && clock.instant().isBefore(lastReadAt.plus(WATCH_EVERY))) {
            watchLater(m); // read just now (the app polling, a command): no need for another
            return;
        }
        DoorDevice.Reading reading;
        try {
            reading = readSensor();
        } catch (DoorException | RuntimeException e) {
            watchLater(m);
            return;
        }
        watchLater(m); // after its change is seen too: a second change (a reversal) must not be missed
    }

    /**
     * Rule 10 for a pending open: Casita saw the reading leave "closed" during it (the only change since), at least the
     * travel time ago, and the reading, just read, still says not closed.
     */
    private boolean seenStopped(Movement m, DoorDevice.Reading reading) {
        return settleBySight && m.acknowledged() && m.action() == Action.OPEN && reading.open() && seenOpenAt != null
                && seenOpenAt.isAfter(m.startedAt()) && onlyItsOwnChange(m) && settledBySight() && !deviceSawARecentChange(reading);
    }

    /** Ends a pending open that Casita saw stop (rule 10). */
    private void endSeenStopped(Movement m) {
        pending = null;
        record(CASITA, m.action(), Outcome.OPEN_INFERRED);
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
            notifier.send("The " + name + " door didn't close. Something may be in the way. Please check it; I won't move it again "
                    + "until someone tells me it's OK.");
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
            Movement m = pending;
            seenOpenAt = reading.open() && m != null && m.acknowledged() && m.action() == Action.OPEN
                    && sensorChanges - m.changesAtStart() == 1 ? lastSensorChange : null;
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

    /** Whether a close could go through at all (remote close allowed, nothing held), before any timing. */
    private boolean mayCloseIfChecked() {
        return control != DoorControl.OFF && properties.allowRemoteClose() && !held();
    }

    /**
     * True once Casita has seen the sensor's current value for at least the travel time plus the sensor's lag, or (rule 10)
     * saw its own open move the reading at least the travel time ago.
     */
    private boolean settled() {
        return lastSensorChange != null && !clock.instant().isBefore(lastSensorChange.plus(settleTime)) || settledBySight();
    }

    /** Rule 10: the reading left "closed" during Casita's own open, was seen doing so at least the travel time ago, and hasn't changed since. */
    private boolean settledBySight() {
        return settleBySight && seenOpenAt != null && Boolean.TRUE.equals(lastSensorOpen) && !clock.instant().isBefore(seenOpenAt.plus(travelTime));
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
