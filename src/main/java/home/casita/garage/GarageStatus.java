package home.casita.garage;

import java.time.Instant;

/**
 * @param door     where the door is, or what Casita believes it's doing
 * @param since    when it got there or started moving; null if unknown (approximate)
 * @param canOpen  whether an open command would be sent right now
 * @param canClose whether a close command would be sent right now
 */
public record GarageStatus(DoorState door, Instant since, boolean canOpen, boolean canClose) {}
