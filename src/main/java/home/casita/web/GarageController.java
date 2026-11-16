package home.casita.web;

import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import home.casita.garage.ActivityEvent;
import home.casita.garage.ActivityLog;
import home.casita.garage.CommandResult;
import home.casita.garage.DoorControl;
import home.casita.garage.DoorState;
import home.casita.garage.Garage;
import home.casita.garage.GarageProperties;
import home.casita.garage.GarageService;
import home.casita.garage.GarageStatus;
import home.casita.security.access.AccessSession;
import home.casita.security.store.SecurityStore;
import jakarta.servlet.http.HttpSession;

/**
 * The API from the design doc (section 8), a thin layer over {@link Garage}: every safety rule lives in each
 * door's {@link GarageService}, none here. All of it needs a signed-in family member ({@code SecurityConfig});
 * POSTs also need Spring Security's CSRF token.
 *
 * <p>Each door has its own explicit commands, {@code POST /api/doors/{door}/open}, {@code /close} and
 * {@code /resume} ({@code door} is {@code ford} or {@code toyota}). There is no toggle. Whether a command reaches
 * the real door is decided per door ({@code control} in {@code /api/doors}); only a door reported
 * {@code live: true} moves for real. The older {@code /api/status}, {@code /open}, {@code /close} and
 * {@code /resume} still drive the first door (Ford) only.
 */
@RestController
@RequestMapping("/api")
class GarageController {

    static final int ACTIVITY_LIMIT = 20;
    /** How far back a per-door activity request looks (the in-memory log keeps 500). */
    static final int ACTIVITY_SCAN = 500;
    /**
     * How long {@code /api/doors} waits for the doors' readings, read side by side. A door that hasn't answered by then
     * is shown {@code UNKNOWN} (nothing can be done to it from that answer), so one slow opener never holds up the
     * other door or the app, whose polls give up after 10 s. Its read goes on, bounded by Meross's own time-out.
     */
    static final Duration STATUS_WAIT = Duration.ofSeconds(6);

    private static final AtomicInteger READERS = new AtomicInteger();
    private static final ExecutorService READS = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "casita-status-" + READERS.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private final Garage garage;
    private final ActivityLog activity;
    private final GarageProperties garageProperties;
    private final SecurityStore store;
    private final Duration statusWait;
    /** A door's status read on the pool, and when it started (System.nanoTime). */
    private record Read(CompletableFuture<GarageStatus> status, long startedAt) {}

    /**
     * At most one status read per door at a time: a request while one is out waits for that same read, but never past
     * {@code statusWait} after it started, so a door that has stopped answering costs the first request the wait and
     * every later one nothing (it's UNKNOWN at once), and the other door's status stays as fresh as its own reads.
     */
    private final ConcurrentHashMap<String, Read> reading = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    GarageController(Garage garage, ActivityLog activity, GarageProperties garageProperties, SecurityStore store) {
        this(garage, activity, garageProperties, store, STATUS_WAIT);
    }

    /** For tests: a shorter wait for the doors' readings. */
    GarageController(Garage garage, ActivityLog activity, GarageProperties garageProperties, SecurityStore store, Duration statusWait) {
        this.garage = garage;
        this.activity = activity;
        this.garageProperties = garageProperties;
        this.store = store;
        this.statusWait = statusWait;
    }

    /**
     * One door as the app shows it.
     *
     * @param key               the door in API paths: {@code ford}, {@code toyota}
     * @param control           how its commands are handled: {@code simulated}, {@code off}, {@code dry-run} or
     *                          {@code live}
     * @param live              true only when a command moves the real door. The app must never suggest real
     *                          control of a door when this is false.
     * @param controllable      whether the door takes commands at all (any control but {@code off})
     * @param travelTimeSeconds how long this door is configured to take to move: an estimate from
     *                          configuration, not a measurement
     */
    record DoorView(String key, String name, String control, boolean live, boolean controllable, DoorState door,
                    Instant since, long travelTimeSeconds, GarageStatus status) {}

    /**
     * @param control           the first door's (Ford's) control, as before; each door has its own in {@code doors}
     * @param travelTimeSeconds the first door's travel time, as before; each door has its own in {@code doors}
     */
    record Doors(String control, long travelTimeSeconds, List<DoorView> doors) {}

    /** @param device the label this device was invited as: for showing, not proof of anything */
    record Me(String name, int passkeys, String device) {}

    /** Every door, each with its own status, control and whether it is live. */
    @GetMapping("/doors")
    Doors doors() {
        List<GarageService> all = garage.doors();
        List<Read> reads = all.stream().map(this::read).toList();
        List<DoorView> doors = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) doors.add(view(all.get(i), awaitStatus(all.get(i), reads.get(i))));
        return new Doors(doors.get(0).control(), doors.get(0).travelTimeSeconds(), doors);
    }

    @GetMapping("/doors/{door}")
    DoorView door(@PathVariable String door) {
        GarageService d = find(door);
        return view(d, awaitStatus(d, read(d)));
    }

    /**
     * The door's status read, on the pool: the one already out for this door if there is one, so a slow opener never
     * piles reads up (they would hold the pool and the other door) and never gets a burst of them when it answers again.
     */
    private Read read(GarageService door) {
        return reading.compute(door.key(), (key, out) -> {
            if (out != null && !out.status().isDone()) return out;
            return new Read(CompletableFuture.supplyAsync(door::status, READS), System.nanoTime());
        });
    }

    /** The status, or UNKNOWN (no command possible, held as it is) if the read hasn't answered within the wait. */
    private GarageStatus awaitStatus(GarageService door, Read read) {
        try {
            long left = read.startedAt() + statusWait.toNanos() - System.nanoTime();
            return read.status().get(Math.max(0, left), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return new GarageStatus(DoorState.UNKNOWN, null, false, false, door.isHeld());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GarageStatus(DoorState.UNKNOWN, null, false, false, door.isHeld());
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new IllegalStateException(e.getCause());
        }
    }

    @PostMapping("/doors/{door}/open")
    CommandResult open(@PathVariable String door, Principal user) {
        return find(door).open(user.getName());
    }

    /**
     * {@code ?checked=true}: the signed-in user says they looked at the door. Recorded with the close; it changes no
     * timing ({@link GarageService#close(String, boolean)}).
     */
    @PostMapping("/doors/{door}/close")
    CommandResult close(@PathVariable String door, @RequestParam(defaultValue = "false") boolean checked, Principal user) {
        return find(door).close(user.getName(), checked);
    }

    /** After a stuck or lost movement of this door: the signed-in user has checked it. Sends nothing to it. */
    @PostMapping("/doors/{door}/resume")
    GarageStatus resume(@PathVariable String door, Principal user) {
        return find(door).resume(user.getName());
    }

    @GetMapping("/me")
    Me me(Principal user, HttpSession session) {
        String device = session.getAttribute(AccessSession.BINDING) instanceof AccessSession.Binding binding
                ? store.device(binding.nodeId()).filter(d -> d.activeFor(user.getName())).map(SecurityStore.Device::label).orElse(null) : null;
        return new Me(user.getName(), store.passkeysOf(user.getName()).size(), device);
    }

    /** The first door (Ford) only. Use {@code /api/doors}. */
    @GetMapping("/status")
    GarageStatus status() {
        return garage.first().status();
    }

    /** The first door (Ford) only. Use {@code /api/doors/{door}/open}. */
    @PostMapping("/open")
    CommandResult open(Principal user) {
        return garage.first().open(user.getName());
    }

    /** The first door (Ford) only. Use {@code /api/doors/{door}/close}. */
    @PostMapping("/close")
    CommandResult close(@RequestParam(defaultValue = "false") boolean checked, Principal user) {
        return garage.first().close(user.getName(), checked);
    }

    /** The first door (Ford) only. Use {@code /api/doors/{door}/resume}. */
    @PostMapping("/resume")
    GarageStatus resume(Principal user) {
        return garage.first().resume(user.getName());
    }

    /** Newest first, every door; {@code ?door=toyota} for one door's. */
    @GetMapping("/activity")
    List<ActivityEvent> activity(@RequestParam(required = false) String door) {
        if (door == null) return activity.recent(ACTIVITY_LIMIT);
        String name = find(door).name();
        return activity.recent(ACTIVITY_SCAN).stream().filter(e -> name.equals(e.door())).limit(ACTIVITY_LIMIT).toList();
    }

    private DoorView view(GarageService door, GarageStatus status) {
        return new DoorView(door.key(), door.name(), door.control().id(), door.control().live(),
                door.control() != DoorControl.OFF, status.door(), status.since(),
                garageProperties.travelTime(door.name()).toSeconds(), status);
    }

    /** An unknown door is 404, and nothing is read, sent or recorded. */
    private GarageService find(String key) {
        return garage.door(key).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such door"));
    }
}
