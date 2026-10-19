package home.casita.garage;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The doors Casita drives, in the order the app shows them (Ford first). Each gets its own
 * {@link GarageService}: its own movement, hold and activity. How commands reach each door is decided per
 * door ({@link DoorControl}), so Toyota's live commands can stay off while Ford's are on.
 */
public record DoorRoster(List<Door> doors) {

    /**
     * @param name    the door's name, as people say it (Ford, Toyota)
     * @param device  the door itself
     * @param control how its commands are handled
     */
    public record Door(String name, DoorDevice device, DoorControl control) {

        /** The door in API paths: its name in lower case ({@code /api/doors/toyota/open}). */
        public String key() {
            return key(name);
        }

        static String key(String name) {
            return name.trim().toLowerCase(Locale.ROOT);
        }
    }

    public DoorRoster {
        doors = List.copyOf(doors);
        if (doors.isEmpty()) throw new IllegalArgumentException("Casita needs at least one door");
        Set<String> keys = new HashSet<>();
        for (Door door : doors) {
            if (door.name() == null || door.name().isBlank() || !keys.add(door.key())) {
                throw new IllegalArgumentException("Every door needs its own name, but got " + doors.stream().map(Door::name).toList());
            }
        }
    }
}
