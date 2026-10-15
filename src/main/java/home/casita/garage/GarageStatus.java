package home.casita.garage;

import java.time.Instant;

/**
 * @param door     where the door is (or is going)
 * @param since    when it got there or started moving; null if unknown
 * @param canClose whether a close command would be accepted right now
 */
public record GarageStatus(DoorState door, Instant since, boolean canClose) {}
