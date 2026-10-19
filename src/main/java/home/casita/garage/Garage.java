package home.casita.garage;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Every door Casita drives, each with its own {@link GarageService}: its own movement, hold and resume.
 * They share the activity log (each entry names its door), the notifier and the scheduler, and nothing
 * else, so one door's movement, check or hold never blocks or lifts another's.
 */
@Component
public class Garage {

    private final Map<String, GarageService> doors = new LinkedHashMap<>();

    public Garage(DoorRoster roster, ActivityLog activity, Notifier notifier, DelayedTasks tasks, Clock clock,
                  GarageProperties properties) {
        for (DoorRoster.Door door : roster.doors()) {
            doors.put(door.key(), new GarageService(door.name(), door.device(), door.control(), activity, notifier, tasks, clock,
                    properties));
        }
    }

    /** In the roster's order, Ford first. */
    public List<GarageService> doors() {
        return List.copyOf(doors.values());
    }

    /** The door named by an API path ({@code ford}, {@code toyota}; any case), if there is one. */
    public Optional<GarageService> door(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(doors.get(DoorRoster.Door.key(key)));
    }

    /** The first door (Ford): the one the older single-door endpoints ({@code /api/open} and so on) drive. */
    public GarageService first() {
        return doors.values().iterator().next();
    }
}
