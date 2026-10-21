package home.casita.garage;

/**
 * @param outcome what happened
 * @param message a short line in Casita's voice, ready to show the person
 * @param status  the door's status right after the command
 */
public record CommandResult(Outcome outcome, String message, GarageStatus status) {}
