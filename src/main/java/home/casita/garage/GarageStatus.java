package home.casita.garage;

import java.time.Instant;

/**
 * @param door     where the door is, or what Casita believes it's doing
 * @param since    when it got there or started moving; null if unknown (approximate)
 * @param canOpen  whether an open command would be sent right now
 * @param canClose whether a close command would be sent right now
 * @param held     a movement ended stuck or lost: no command is sent until someone checks the door
 *                 and resumes ({@code GarageService.resume})
 */
public record GarageStatus(DoorState door, Instant since, boolean canOpen, boolean canClose, boolean held) {}
