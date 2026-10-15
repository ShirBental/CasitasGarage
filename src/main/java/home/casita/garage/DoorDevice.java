package home.casita.garage;

import java.time.Instant;

/**
 * Something that can report and move a garage door. The Meross cloud client will be the
 * real one; {@link SimulatedDoor} stands in until then.
 */
public interface DoorDevice {

    /** What the door's sensor says. {@code changedAt} is null when the device doesn't say. */
    record Reading(boolean open, Instant changedAt) {}

    Reading read() throws DoorException;

    /** Ask the door to move to the given position. */
    void command(boolean open) throws DoorException;
}
