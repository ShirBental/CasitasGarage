package home.casita.web;

import java.security.Principal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import home.casita.garage.ActivityEvent;
import home.casita.garage.ActivityLog;
import home.casita.garage.CommandResult;
import home.casita.garage.GarageService;
import home.casita.garage.GarageStatus;

/**
 * The API from the design doc (section 8), a thin layer over {@link GarageService}: every safety rule
 * lives there, none here. All of it needs a signed-in user ({@code SecurityConfig}); until sign-in
 * exists (build step 6) every call is refused with 401. POSTs also need Spring Security's CSRF token.
 * Whether a command reaches the real door is still decided by {@code casita.meross.command-mode}.
 */
@RestController
@RequestMapping("/api")
class GarageController {

    static final int ACTIVITY_LIMIT = 20;

    private final GarageService garage;
    private final ActivityLog activity;

    GarageController(GarageService garage, ActivityLog activity) {
        this.garage = garage;
        this.activity = activity;
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

    @GetMapping("/activity")
    List<ActivityEvent> activity() {
        return activity.recent(ACTIVITY_LIMIT);
    }
}
