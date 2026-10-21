package home.casita.meross;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import home.casita.garage.DoorDevice;

/**
 * Read-only: prints Ford's and Toyota's readings every few seconds, with the time, so a person at the garage can measure
 * each door's travel time and sensor lag while working the doors with the wall button or remote. Casita sends nothing:
 * both doors are {@link MerossDoor#readOnly}, which refuses any command before anything is built. Run on watson with
 * deploy/watson/door-watch.sh (the service's own settings: CASITA_MEROSS_* from the environment).
 *
 *   door-watch.sh [minutes, default 10] [seconds between readings, default 2]
 */
public final class DoorWatch {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private DoorWatch() {}

    public static void main(String[] args) throws Exception {
        long minutes = args.length > 0 ? Long.parseLong(args[0]) : 10;
        Duration every = Duration.ofSeconds(args.length > 1 ? Long.parseLong(args[1]) : 2);
        Map<String, String> env = System.getenv();
        String email = env.get("CASITA_MEROSS_EMAIL"), password = env.get("CASITA_MEROSS_PASSWORD");
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            System.err.println("CASITA_MEROSS_EMAIL and CASITA_MEROSS_PASSWORD are needed (door-watch.sh reads /etc/casita/meross.env)");
            System.exit(2);
        }
        String base = env.getOrDefault("CASITA_MEROSS_APIBASEURL", "https://iotx-us.meross.com");
        String uuid = env.get("CASITA_MEROSS_DEVICEUUID");
        String transportName = env.getOrDefault("CASITA_MEROSS_TRANSPORT", "cloud");
        Duration timeout = Duration.ofSeconds(8);
        Clock clock = Clock.systemUTC();
        MerossCloudApi api = new MerossCloudApi(base, timeout);

        MerossTransport transport;
        MerossSession session = null;
        if ("local".equals(transportName)) {
            transport = new MerossLocalTransport(api, email, password, URI.create(env.getOrDefault("CASITA_MEROSS_LOCALURL", "")), timeout, clock);
        } else {
            MerossProperties props = new MerossProperties(base, email, password, uuid, null, timeout, CommandMode.OFF,
                    Map.of(1, "Ford", 2, "Toyota"), Set.of());
            transport = session = new MerossSession(api, new HiveMqConnector(), props, clock);
        }
        Map<String, MerossDoor> doors = new LinkedHashMap<>();
        doors.put("Ford", MerossDoor.readOnly(transport, uuid, 1));
        doors.put("Toyota", MerossDoor.readOnly(transport, uuid, 2));

        System.out.println("Watching Ford (channel 1) and Toyota (channel 2) over " + transportName + ", every " + every.toSeconds()
                + " s for " + minutes + " min. Read-only: nothing is sent to the doors. Ctrl-C stops.");
        Map<String, String> last = new LinkedHashMap<>();
        long end = System.nanoTime() + Duration.ofMinutes(minutes).toNanos();
        try {
            while (System.nanoTime() < end) {
                long tick = System.nanoTime();
                StringBuilder line = new StringBuilder(LocalTime.now(ZoneId.systemDefault()).format(TIME));
                StringBuilder changes = new StringBuilder();
                for (Map.Entry<String, MerossDoor> d : doors.entrySet()) {
                    long t0 = System.nanoTime();
                    String state;
                    try {
                        DoorDevice.Reading r = d.getValue().read();
                        state = r.open() ? "NOT_CLOSED" : "CLOSED";
                    } catch (Exception e) {
                        state = "ERROR(" + e.getMessage() + ")";
                    }
                    long ms = Duration.ofNanos(System.nanoTime() - t0).toMillis();
                    line.append(String.format("  %-6s %-10s (%4d ms)", d.getKey(), state, ms));
                    String before = last.put(d.getKey(), state);
                    if (before != null && !before.equals(state)) changes.append("  <- ").append(d.getKey()).append(" changed");
                }
                System.out.println(line.append(changes));
                long sleep = every.toMillis() - Duration.ofNanos(System.nanoTime() - tick).toMillis();
                if (sleep > 0) Thread.sleep(sleep);
            }
        } finally {
            if (session != null) session.destroy();
        }
    }
}
