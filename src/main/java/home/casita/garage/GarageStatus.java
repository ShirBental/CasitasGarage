package home.casita.garage;

import java.time.Instant;

/**
 * @param door     where the door is, or what Casita believes it's doing
 * @param since    when it got there or started moving; null if unknown (approximate)
 * @param canOpen  whether the safety rules would let an open command through right now. Whether it then
 *                 reaches the real door is the door's {@link DoorControl}: a door whose control is off
 *                 refuses every command.
 * @param canClose the same for a close command
 * @param held     a movement ended stuck or lost: no command is sent until someone checks the door
 *                 and resumes ({@code GarageService.resume})
 * @param canCloseIfChecked a close would go through now if the person asking has looked at the door and seen it fully
 *                 open and stopped ({@code GarageService.close(who, true)}): the door has had its travel time and only the
 *                 sensor's delay is left. Never true when {@code canClose} is.
 */
public record GarageStatus(DoorState door, Instant since, boolean canOpen, boolean canClose, boolean held, boolean canCloseIfChecked) {

    public GarageStatus(DoorState door, Instant since, boolean canOpen, boolean canClose, boolean held) {
        this(door, since, canOpen, canClose, held, false);
    }
}
