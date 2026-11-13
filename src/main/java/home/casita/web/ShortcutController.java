package home.casita.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import home.casita.garage.Action;
import home.casita.garage.CommandResult;
import home.casita.garage.DoorState;
import home.casita.garage.Garage;
import home.casita.garage.GarageProperties;
import home.casita.garage.GarageService;
import home.casita.garage.GarageStatus;
import home.casita.garage.Outcome;
import home.casita.security.store.SecurityStore.ShortcutToken;
import jakarta.servlet.http.HttpServletRequest;

/**
 * What a Shortcut token may do (docs/SHORTCUTS.md): the one action (open or close) on the one door it was created for,
 * through the same {@link GarageService} as the app, so every safety rule, hold and command mode applies unchanged. It
 * may also read that door's status, so a Shortcut can say what actually happened.
 *
 * <p>A close is an ordinary close ({@link GarageService#close(String)}): a Shortcut can never say "I've looked at the
 * door" ({@code checked}), because a voice request proves nothing about who is at the garage. A request that tries is
 * refused and nothing is sent.
 *
 * <p>An accepted command is never reported as an open door: {@code result} says what happened to the command,
 * {@code doorState} what Casita knows about the door, and {@code confirmed} whether the sensor has settled on it.
 * A door that isn't {@code live} (simulated, dry-run) is never described as the real door moving.
 */
@RestController
@RequestMapping("/shortcut/v1/doors")
class ShortcutController {

    private static final Logger log = LoggerFactory.getLogger(ShortcutController.class);

    /**
     * @param result            {@code accepted} (sent and acknowledged), {@code unconfirmed} (sent, no acknowledgement),
     *                          {@code already} (the sensor reports not closed; nothing sent), {@code refused} (nothing sent:
     *                          busy, held, unreadable or switched off) or {@code failed} (definitely not sent)
     * @param outcome           Casita's own outcome ({@link Outcome})
     * @param control           {@code live}, {@code simulated}, {@code dry-run} or {@code off}
     * @param live              true only when a command reaches the real door
     * @param doorState         what Casita knows about the door right after the command ({@link DoorState})
     * @param confirmed         true only when the sensor has settled: {@code CLOSED}, or {@code NOT_CLOSED} (open inferred)
     * @param checkAfterSeconds for an accepted or unconfirmed command, when the status will show the sensor's check
     * @param speak             one line for Siri to say
     */
    record CommandAnswer(String result, Outcome outcome, String door, String control, boolean live, DoorState doorState,
                         boolean confirmed, Long checkAfterSeconds, String message, String speak) {}

    record StatusAnswer(String door, String control, boolean live, DoorState doorState, boolean confirmed, Instant since,
                        boolean held, String speak) {}

    record Refusal(String result, String error, String speak) {}

    private final Garage garage;
    private final GarageProperties properties;

    ShortcutController(Garage garage, GarageProperties properties) {
        this.garage = garage;
        this.properties = properties;
    }

    @PostMapping("/{door}/{action}")
    ResponseEntity<?> command(@PathVariable String door, @PathVariable String action,
                              @AuthenticationPrincipal ShortcutToken token, HttpServletRequest request) {
        if (request.getParameter("checked") != null) {
            log.warn("Shortcut command refused: token {} ({}) sent 'checked', which a Shortcut can't (client {})", token.tokenId(),
                    token.memberId(), request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).header("Cache-Control", "no-store").body(new Refusal("refused", "checked-not-allowed",
                    "Siri can't tell Casita you've looked at the door, so I didn't send anything."));
        }
        Optional<GarageService> scoped = scoped(token, door);
        String asked = action.toLowerCase(java.util.Locale.ROOT);
        Action wanted = "open".equals(asked) ? Action.OPEN : "close".equals(asked) ? Action.CLOSE : null;
        if (scoped.isEmpty() || wanted == null || !wanted.name().equals(token.action())) {
            log.warn("Shortcut command refused: token {} ({}) may {} {} only, asked to {} {} (client {})", token.tokenId(), token.memberId(),
                    token.action(), token.door(), action, door, request.getRemoteAddr());
            return refusal();
        }
        GarageService service = scoped.get();
        String who = token.memberId() + " via Shortcut";
        CommandResult result = wanted == Action.OPEN ? service.open(who) : service.close(who);
        log.info("Shortcut command: token {} ({}, {}) {} {} -> {} (control {}, client {})", token.tokenId(), token.memberId(), token.label(),
                token.action(), service.name(), result.outcome(), service.control().id(), request.getRemoteAddr());
        return answer(service, wanted, result);
    }

    @GetMapping("/{door}")
    ResponseEntity<?> status(@PathVariable String door, @AuthenticationPrincipal ShortcutToken token, HttpServletRequest request) {
        Optional<GarageService> scoped = scoped(token, door);
        if (scoped.isEmpty()) {
            log.warn("Shortcut status refused: token {} ({}) is for {}, asked for {} (client {})", token.tokenId(), token.memberId(), token.door(),
                    door, request.getRemoteAddr());
            return refusal();
        }
        GarageService service = scoped.get();
        GarageStatus status = service.status();
        boolean live = service.control().live();
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new StatusAnswer(service.name(), service.control().id(), live,
                status.door(), confirmed(status.door()), status.since(), status.held(), describe(service.name(), status, live)));
    }

    /** The token's own door, and only it; anything else (another door, a door that doesn't exist) is the same refusal. */
    private Optional<GarageService> scoped(ShortcutToken token, String door) {
        return garage.door(door).filter(service -> service.key().equals(token.door()));
    }

    private static ResponseEntity<Refusal> refusal() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).header("Cache-Control", "no-store")
                .body(new Refusal("refused", "forbidden", "This Shortcut isn't allowed to do that."));
    }

    private ResponseEntity<CommandAnswer> answer(GarageService service, Action action, CommandResult r) {
        boolean open = action == Action.OPEN;
        String name = service.name();
        boolean live = service.control().live();
        String practice = practice(service);
        String result;
        HttpStatus status;
        String speak;
        Long checkAfter = null;
        switch (r.outcome()) {
            case MOVING -> {
                result = "accepted";
                status = HttpStatus.ACCEPTED;
                checkAfter = checkAfter(name);
                speak = practice + (open ? "Opening " + name + ". It isn't confirmed yet: I'll know once the sensor checks."
                        : "Closing " + name + ". It isn't confirmed yet: the sensor will say once it's closed.");
            }
            case UNCONFIRMED -> {
                result = "unconfirmed";
                status = HttpStatus.ACCEPTED;
                checkAfter = checkAfter(name);
                speak = practice + "I'm not sure " + name + " got that. Check the app in a few seconds.";
            }
            case ALREADY -> {
                result = "already";
                status = HttpStatus.OK;
                speak = practice + (open ? name + " isn't closed, so I didn't send anything."
                        : name + "'s sensor says it's closed, so I didn't send anything. If you can see it open, the sensor hasn't "
                        + "caught up yet: ask again in a little while, or use the wall button.");
            }
            case ERROR -> {
                result = "failed";
                status = HttpStatus.BAD_GATEWAY;
                speak = practice + r.message();
            }
            default -> {
                result = "refused";
                status = HttpStatus.CONFLICT;
                speak = practice + name + ": " + r.message() + " I didn't send anything.";
            }
        }
        DoorState state = r.status() == null ? null : r.status().door();
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(new CommandAnswer(result, r.outcome(), name,
                service.control().id(), live, state, confirmed(state), checkAfter, r.message(), speak));
    }

    /** Said first whenever the real door isn't the one being driven. */
    private static String practice(GarageService service) {
        return switch (service.control()) {
            case LIVE -> "";
            case SIMULATED -> "Practice door, not the real one. ";
            case DRY_RUN -> "Dry run, nothing goes to the real door. ";
            case OFF -> "";
        };
    }

    /** When Casita checks the sensor after a command: travel time + sensor lag + grace, as GarageService schedules it. */
    private long checkAfter(String door) {
        Duration due = properties.travelTime(door).plus(properties.sensorLag(door)).plus(properties.verifyGrace());
        return Math.max(1, (due.toMillis() + 999) / 1000);
    }

    private static boolean confirmed(DoorState state) {
        return state == DoorState.CLOSED || state == DoorState.NOT_CLOSED;
    }

    private static String describe(String name, GarageStatus status, boolean live) {
        String prefix = live ? "" : "Practice door: ";
        String held = status.held() ? " I'm holding it until someone checks it in the app." : "";
        return prefix + switch (status.door()) {
            case CLOSED -> name + " is closed.";
            case NOT_CLOSED -> name + " is open.";
            case OPENING -> name + " is opening; it isn't confirmed yet.";
            case CLOSING -> name + " is closing; it isn't confirmed yet.";
            case CHECKING -> "I'm still checking on " + name + ".";
            case UNKNOWN -> "I can't see " + name + " right now.";
        } + held;
    }
}
