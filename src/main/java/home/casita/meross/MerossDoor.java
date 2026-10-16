package home.casita.meross;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import home.casita.garage.DoorCommandRejectedException;
import home.casita.garage.DoorDevice;
import home.casita.garage.DoorException;
import home.casita.meross.MerossException.Kind;

/**
 * One door of a Meross garage door opener (MSG100, or the multi-door MSG200), reached through
 * Meross's cloud. See docs/MEROSS.md for what is confirmed on our devices.
 *
 * <h2>Reading</h2>
 * <ul>
 *   <li><b>MSG200:</b> {@code GET Appliance.System.All}, door list in {@code digest.garageDoor}. Our
 *       MSG200 never answers {@code GET Appliance.GarageDoor.State} (with or without a payload).
 *       It has several doors, so the channel must be configured; there is no default.</li>
 *   <li><b>MSG100:</b> {@code GET Appliance.GarageDoor.State}, as before. Channel 0 when none is set.</li>
 *   <li>A door the opener reports as disabled ({@code doorEnable: 0}) is refused: its {@code open}
 *       value means nothing (a disabled MSG200 channel reports {@code open: 1}).</li>
 * </ul>
 *
 * <h2>Commands</h2>
 * Off unless {@code casita.meross.commands-enabled} is true; while off, every command is rejected
 * before anything is sent. Commands to an MSG200 are refused even then: its write path is unverified.
 */
class MerossDoor implements DoorDevice {

    static final String NS_GARAGE_DOOR = "Appliance.GarageDoor.State";
    static final String NS_SYSTEM_ALL = "Appliance.System.All";
    static final String MSG100 = "msg100";
    static final String MSG200 = "msg200";
    /** Device types treated as garage door openers (the device list reports them in lower case). */
    static final Set<String> OPENER_TYPES = Set.of(MSG100, MSG200);

    /** The opener on the account this door belongs to. */
    record Opener(String uuid, String type) {
        @Override
        public String toString() {
            return "Opener[type=" + type + ", uuid=" + MerossDeviceInfo.masked(uuid) + "]";
        }
    }

    private final MerossTransport transport;
    private final String configuredUuid;
    private final Integer channel;
    private final boolean commandsEnabled;
    private volatile Opener opener;

    /**
     * @param channel the door's channel, or null if none was configured (allowed only for an MSG100,
     *                which then uses channel 0)
     */
    MerossDoor(MerossTransport transport, String configuredUuid, Integer channel, boolean commandsEnabled) {
        this.transport = transport;
        this.configuredUuid = configuredUuid == null || configuredUuid.isBlank() ? null : configuredUuid.trim();
        this.channel = channel;
        this.commandsEnabled = commandsEnabled;
    }

    @Override
    public Reading read() throws DoorException {
        Opener o = opener();
        if (MSG200.equals(o.type())) {
            Map<String, Object> all = MerossCloudApi.map(transport.get(o.uuid(), NS_SYSTEM_ALL).get("all"));
            Object doors = MerossCloudApi.map(all.get("digest")).get("garageDoor");
            if (!(doors instanceof List<?>)) {
                throw new MerossException(Kind.PROTOCOL, "The opener's System.All reply has no digest.garageDoor list");
            }
            return readingFrom(doors, true);
        }
        return readingFrom(transport.get(o.uuid(), NS_GARAGE_DOOR).get("state"), false);
    }

    @Override
    public void command(boolean open) throws DoorException {
        if (!commandsEnabled) {
            throw new DoorCommandRejectedException("Door commands are turned off (casita.meross.commands-enabled); nothing was sent");
        }
        Opener o;
        try {
            o = opener();
        } catch (DoorException e) {
            throw new DoorCommandRejectedException("Couldn't find the opener, so nothing was sent: " + e.getMessage(), e);
        }
        if (MSG200.equals(o.type())) {
            throw new DoorCommandRejectedException("Commands to an MSG200 aren't supported yet (its write path is unverified); nothing was sent");
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("channel", channel == null ? 0 : channel);
        state.put("open", open ? 1 : 0);
        state.put("uuid", o.uuid());
        transport.setOnce(o.uuid(), NS_GARAGE_DOOR, Map.of("state", state));
    }

    /**
     * Finds this door's entry in a door list ({@code state} or {@code digest.garageDoor}): a list with
     * one entry per channel, or a single object, depending on device and firmware.
     *
     * @param multiDoor true for a multi-door opener: the channel must be configured, and every entry
     *                  must say which channel it is
     */
    Reading readingFrom(Object doorsNode, boolean multiDoor) throws MerossException {
        List<?> entries = doorsNode instanceof List<?> list ? list : doorsNode == null ? List.of() : List.of(doorsNode);
        List<Object> reported = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?>)) {
                throw new MerossException(Kind.PROTOCOL, "The opener's door list holds something other than door entries");
            }
            reported.add(MerossCloudApi.map(entry).get("channel"));
        }
        if (channel == null && multiDoor) {
            throw new MerossException(Kind.CONFIG, "This opener has several doors; set casita.meross.channel to one of the channels it reported "
                    + reported);
        }
        int wanted = channel == null ? 0 : channel;
        for (Object entry : entries) {
            Map<String, Object> s = MerossCloudApi.map(entry);
            Object ch = s.get("channel");
            if (ch == null ? multiDoor : MerossCloudApi.number(ch) != wanted) continue;
            Object enabled = s.get("doorEnable");
            if (enabled != null && MerossCloudApi.number(enabled) == 0) {
                throw new MerossException(Kind.CONFIG, "Door channel " + wanted + " is disabled on the opener (doorEnable 0), so its state "
                        + "means nothing; choose an enabled channel");
            }
            if (enabled != null && MerossCloudApi.number(enabled) != 1) {
                throw new MerossException(Kind.PROTOCOL, "The opener reported an unexpected doorEnable for door channel " + wanted);
            }
            int open = MerossCloudApi.number(s.get("open"));
            if (open != 0 && open != 1) {
                throw new MerossException(Kind.PROTOCOL, "The opener reported an unexpected state for door channel " + wanted);
            }
            long changed = s.get("lmTime") == null ? 0 : MerossCloudApi.number(s.get("lmTime"));
            return new Reading(open == 1, changed > 0 ? Instant.ofEpochSecond(changed) : null);
        }
        // The channel numbers help when casita.meross.channel is wrong (an MSG200 numbers its doors from 1).
        throw new MerossException(Kind.PROTOCOL, "The opener didn't report the state of door channel " + wanted
                + "; it reported channels " + reported);
    }

    /** The opener's uuid: the configured one, or the only opener on the account. */
    String uuid() throws DoorException {
        return opener().uuid();
    }

    /**
     * The opener and its type, looked up once in the account's device list. A configured uuid must
     * be on the account and be an opener; otherwise there must be exactly one opener.
     */
    Opener opener() throws DoorException {
        Opener o = opener;
        if (o != null) return o;
        List<MerossDeviceInfo> openers = transport.devices().stream()
                .filter(d -> d.type() != null && OPENER_TYPES.contains(d.type().toLowerCase(Locale.ROOT)))
                .toList();
        MerossDeviceInfo found;
        if (configuredUuid != null) {
            found = openers.stream().filter(d -> configuredUuid.equalsIgnoreCase(d.uuid())).findFirst()
                    .orElseThrow(() -> new MerossException(Kind.NOT_FOUND, "The configured casita.meross.device-uuid ("
                            + MerossDeviceInfo.masked(configuredUuid) + ") isn't a garage door opener on this account"));
        } else if (openers.isEmpty()) {
            throw new MerossException(Kind.NOT_FOUND, "No Meross garage door opener (MSG100 or MSG200) on this account");
        } else if (openers.size() > 1) {
            // Names and the end of each uuid only: this message gets logged, and uuids stay out of logs.
            throw new MerossException(Kind.NOT_FOUND, "Several garage door openers on this account; set casita.meross.device-uuid to one of "
                    + openers.stream().map(MerossDeviceInfo::toString).toList());
        } else {
            found = openers.get(0);
        }
        o = new Opener(found.uuid(), found.type().toLowerCase(Locale.ROOT));
        opener = o;
        return o;
    }
}
