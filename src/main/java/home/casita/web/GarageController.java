package home.casita.web;

import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import home.casita.garage.ActivityEvent;
import home.casita.garage.ActivityLog;
import home.casita.garage.CommandResult;
import home.casita.garage.DoorDevice;
import home.casita.garage.DoorException;
import home.casita.garage.DoorRoster;
import home.casita.garage.DoorState;
import home.casita.garage.GarageProperties;
import home.casita.garage.GarageService;
import home.casita.garage.GarageStatus;
import home.casita.security.FilePasskeyStore;

/**
 * The API from the design doc (section 8), a thin layer over {@link GarageService}: every safety rule
 * lives there, none here. All of it needs a signed-in user ({@code SecurityConfig}); until sign-in
 * exists (build step 6) every call is refused with 401. POSTs also need Spring Security's CSRF token.
 * Whether a command reaches the real door is still decided by {@code casita.meross.command-mode}; only the
 * controlled door (Ford) can be sent one. Watched doors (Toyota) are read-only here.
 */
@RestController
@RequestMapping("/api")
class GarageController {

    static final int ACTIVITY_LIMIT = 20;

    private final GarageService garage;
    private final ActivityLog activity;
    private final DoorRoster roster;
    private final FilePasskeyStore passkeys;
    private final GarageProperties garageProperties;

    GarageController(GarageService garage, ActivityLog activity, DoorRoster roster, FilePasskeyStore passkeys,
                     GarageProperties garageProperties) {
        this.garage = garage;
        this.activity = activity;
        this.roster = roster;
        this.passkeys = passkeys;
        this.garageProperties = garageProperties;
    }

    /** One door as the app shows it. {@code status} for the controlled door; {@code door} and {@code since} for every door. */
    record DoorView(String name, boolean controllable, DoorState door, Instant since, GarageStatus status) {}

    /**
     * @param control           how commands reach the controlled door: simulated, off, dry-run or live
     * @param travelTimeSeconds how long the controlled door is configured to take to move: an estimate from
     *                          configuration, not a measurement (the sensor only knows closed / not closed)
     */
    record Doors(String control, long travelTimeSeconds, List<DoorView> doors) {}

    record Me(String name, int passkeys) {}

    /** Every door the app shows. Watched doors (Toyota) are only read: closed, not closed, or unknown. */
    @GetMapping("/doors")
    Doors doors() {
        GarageStatus status = garage.status();
        List<DoorView> doors = new ArrayList<>();
        doors.add(new DoorView(roster.controlled(), true, status.door(), status.since(), status));
        for (DoorRoster.Watched watched : roster.watched()) {
            DoorView view;
            try {
                DoorDevice.Reading reading = watched.door().read();
                view = new DoorView(watched.name(), false, reading.open() ? DoorState.NOT_CLOSED : DoorState.CLOSED, reading.changedAt(), null);
            } catch (DoorException e) {
                view = new DoorView(watched.name(), false, DoorState.UNKNOWN, null, null);
            }
            doors.add(view);
        }
        return new Doors(roster.control(), garageProperties.travelTime().toSeconds(), doors);
    }

    @GetMapping("/me")
    Me me(Principal user) {
        return new Me(user.getName(), passkeys.countFor(user.getName()));
    }

    @GetMapping("/status")
    GarageStatus status() {
        return garage.status();
    }

    @PostMapping("/open")
    CommandResult open(Principal user) {
        return garage.open(user.getName());
    }

    @PostMapping("/close")
    CommandResult close(Principal user) {
        return garage.close(user.getName());
    }

    /** After a stuck or lost movement: the signed-in user has checked the door. Sends nothing to it. */
    @PostMapping("/resume")
    GarageStatus resume(Principal user) {
        return garage.resume(user.getName());
    }

    @GetMapping("/activity")
    List<ActivityEvent> activity() {
        return activity.recent(ACTIVITY_LIMIT);
    }
}
