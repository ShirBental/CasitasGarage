package home.casita.meross;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
 * {@code SET Appliance.GarageDoor.State} with {@code {"state": {"channel": c, "open": 0|1, "uuid": u}}},
 * as MerossIot sends it over the cloud (meross_lan sends the same without {@code uuid} over local
 * HTTP). Not yet tried on our MSG200; see docs/MEROSS.md. Before anything is built:
 * <ol>
 *   <li>the command mode must be {@link CommandMode#DRY_RUN} or {@link CommandMode#LIVE} ({@code off} by default);</li>
 *   <li>the channel must be configured and listed in {@code command-channels};</li>
 *   <li>the door is read again: it must be enabled and report the opposite of the target. Any doubt
 *       and nothing is sent.</li>
 * </ol>
 * In dry-run mode the signed command is built and the command is then rejected: nothing is published.
 * In live mode it is sent once (never retried). The acknowledgement is not proof of movement:
 * {@code GarageService} checks the sensor after the travel time. An acknowledgement that says the
 * opener didn't execute ({@code execute: 0}), or that is about another channel, is an unknown outcome.
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
    private final CommandMode commandMode;
    private final Set<Integer> commandChannels;
    private volatile Opener opener;

    /**
     * @param channel         the door's channel, or null if none was configured (allowed only for an MSG100,
     *                        which then uses channel 0 to read; commands always need it set)
     * @param commandMode     off, dry-run or live
     * @param commandChannels the only channels a command may go to
     */
    MerossDoor(MerossTransport transport, String configuredUuid, Integer channel, CommandMode commandMode,
               Set<Integer> commandChannels) {
        this.transport = transport;
        this.configuredUuid = configuredUuid == null || configuredUuid.isBlank() ? null : configuredUuid.trim();
        this.channel = channel;
        this.commandMode = commandMode == null ? CommandMode.OFF : commandMode;
        this.commandChannels = commandChannels == null ? Set.of() : Set.copyOf(commandChannels);
    }

    /** A door that can only be read: commands are off. */
    static MerossDoor readOnly(MerossTransport transport, String configuredUuid, Integer channel) {
        return new MerossDoor(transport, configuredUuid, channel, CommandMode.OFF, Set.of());
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
        String target = open ? "open" : "close";
        if (commandMode == CommandMode.OFF) {
            throw new DoorCommandRejectedException("Door commands are turned off (casita.meross.command-mode); nothing was sent");
        }
        Opener o;
        try {
            o = opener();
        } catch (DoorException e) {
            throw new DoorCommandRejectedException("Couldn't find the opener, so nothing was sent: " + e.getMessage(), e);
        }
        if (channel == null) {
            throw new DoorCommandRejectedException("No door channel is configured (casita.meross.channel); nothing was sent");
        }
        if (!commandChannels.contains(channel)) {
            throw new DoorCommandRejectedException("Commands to door channel " + channel + " aren't allowed (casita.meross.command-channels "
                    + new TreeSet<>(commandChannels) + "); nothing was sent");
        }

        // Read the door again, here, just before building the command: it must be enabled and report the
        // opposite of the target. GarageService has read it too; this guards every other caller as well.
        Reading now;
        try {
            now = read();
        } catch (DoorException e) {
            throw new DoorCommandRejectedException("Couldn't read door channel " + channel + " just before the " + target
                    + ", so nothing was sent: " + e.getMessage(), e);
        }
        if (now.open() == open) {
            throw new DoorCommandRejectedException("Door channel " + channel + " already reports " + (open ? "not closed" : "closed")
                    + "; a " + target + " could act like a button press, so nothing was sent");
        }

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("channel", channel);
        state.put("open", open ? 1 : 0);
        state.put("uuid", o.uuid());
        Map<String, Object> payload = Map.of("state", state);

        if (commandMode == CommandMode.DRY_RUN) {
            String built = transport.dryRunSet(o.uuid(), NS_GARAGE_DOOR, payload);
            throw new DoorCommandRejectedException("Dry run: the " + target + " for door channel " + channel + " was checked and built ("
                    + built + ") but not sent; nothing moved");
        }
        Map<String, Object> ack = transport.setOnce(o.uuid(), NS_GARAGE_DOOR, payload);
        checkAcknowledgement(ack, open);
    }

    /**
     * The opener said SETACK. That says the message arrived, not that the door moved; GarageService
     * checks the sensor afterwards. Here only an acknowledgement that contradicts the command is caught.
     * Its {@code state} (an object, or a one-item list) may carry {@code execute}: 1 when the opener acted,
     * 0 when it didn't. Its {@code open} is the state before moving, so it isn't checked.
     */
    void checkAcknowledgement(Map<String, Object> ack, boolean open) throws DoorException {
        Object stateNode = ack.get("state");
        List<?> entries = stateNode instanceof List<?> list ? list : stateNode == null ? List.of() : List.of(stateNode);
        if (entries.isEmpty()) return; // a bare SETACK: nothing to contradict
        Map<String, Object> s = MerossCloudApi.map(entries.get(0));
        Object ch = s.get("channel");
        if (entries.size() > 1 || (ch != null && MerossCloudApi.number(ch) != channel)) {
            throw new MerossException(Kind.PROTOCOL, "The opener acknowledged a command for another door channel than " + channel
                    + "; whether door " + channel + " acted is unknown");
        }
        Object execute = s.get("execute");
        if (execute != null && MerossCloudApi.number(execute) != 1) {
            throw new MerossException(Kind.DEVICE_ERROR, "The opener acknowledged the " + (open ? "open" : "close") + " for door channel "
                    + channel + " but said it didn't carry it out (execute " + execute + ")");
        }
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
