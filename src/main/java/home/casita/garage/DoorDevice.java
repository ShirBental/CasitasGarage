package home.casita.garage;

import java.time.Instant;

/**
 * A garage door opener as Casita sees it: one binary sensor and one command.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><b>{@link #read()}</b> reports the sensor. {@code open == false} means the sensor
 *       reports the door <i>fully closed</i>. {@code open == true} only means "not fully
 *       closed": the door may be opening, closing, stopped partway, or fully open. The
 *       sensor gives no position and no motion.</li>
 *   <li><b>{@link #command(boolean)}</b> asks the opener to move toward a position.
 *       Implementations may behave like a wall-button press (a toggle) whatever the target,
 *       so it is <b>not</b> idempotent: sending "open" to an open door, or any command to a
 *       moving door, may stop or reverse it. Callers send a command only toward the opposite
 *       of a settled sensor reading, never while a movement is unverified, and never retry
 *       automatically.</li>
 *   <li><b>Failures of {@link #command(boolean)}:</b>
 *       {@link DoorCommandRejectedException} means the command was definitely
 *       <i>not</i> sent to the device. Any other {@link DoorException} means the outcome is
 *       <i>unknown</i>: the device may have acted even though no acknowledgement arrived.
 *       Implementations throw {@code DoorCommandRejectedException} only when they can
 *       guarantee nothing was dispatched.</li>
 *   <li>{@link Reading#changedAt()} is informational only. Its meaning and clock are
 *       device-specific and unverified, so no safety decision depends on it.</li>
 * </ul>
 *
 * <h2>Safety limitations</h2>
 * Casita cannot see the door move, cannot see obstructions, and only learns about the wall
 * button or remote through sensor changes it happens to observe. Physical safety (stopping
 * for people, pets and cars) is the opener's own job: its photo-eye sensors and
 * auto-reverse. Nothing here replaces them.
 */
public interface DoorDevice {

    /**
     * What the door's sensor says.
     *
     * @param open      true if the sensor reports anything other than fully closed
     * @param changedAt when the device says the sensor last changed; null if it doesn't say.
     *                  Informational only.
     */
    record Reading(boolean open, Instant changedAt) {}

    Reading read() throws DoorException;

    /**
     * Ask the door to move toward the given position. Not idempotent; see the class contract.
     *
     * @throws DoorCommandRejectedException if the command was definitely not sent
     * @throws DoorException                if the outcome is unknown (it may have been executed)
     */
    void command(boolean open) throws DoorException;
}
