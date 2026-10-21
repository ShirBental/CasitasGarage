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
 */
public record GarageStatus(DoorState door, Instant since, boolean canOpen, boolean canClose, boolean held) {}
