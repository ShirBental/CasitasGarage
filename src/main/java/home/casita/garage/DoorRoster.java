package home.casita.garage;

import java.util.List;

/**
 * The doors the app shows: the one {@link GarageService} controls, and others it only watches (Toyota,
 * until its commands are tested). Watched doors are read, never sent a command.
 *
 * @param controlled the name of the door GarageService drives
 * @param control    how commands reach that door: {@code simulated}, or the Meross command mode
 *                   ({@code off}, {@code dry-run}, {@code live})
 * @param watched    doors shown read-only
 */
public record DoorRoster(String controlled, String control, List<Watched> watched) {

    /** A door the app reads and never commands. */
    public record Watched(String name, DoorDevice door) {}

    public DoorRoster {
        watched = List.copyOf(watched);
    }
}
