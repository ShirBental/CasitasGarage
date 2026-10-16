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
 * A Meross garage door opener (MSG100, or the multi-door MSG200), reached through Meross's cloud.
 * Both are read with {@code Appliance.GarageDoor.State}; see docs/MEROSS.md for what is confirmed
 * about the MSG200 and what is not.
 *
 * <p>Reads are always on. Commands are <b>off</b> unless {@code casita.meross.commands-enabled}
 * is true; while off, every command is rejected before anything is sent.
 */
class MerossDoor implements DoorDevice {

    static final String NS_GARAGE_DOOR = "Appliance.GarageDoor.State";
    /** Device types treated as garage door openers (the device list reports them in lower case). */
    static final Set<String> OPENER_TYPES = Set.of("msg100", "msg200");

    private final MerossTransport transport;
    private final int channel;
    private final boolean commandsEnabled;
    private volatile String deviceUuid;

    MerossDoor(MerossTransport transport, String configuredUuid, int channel, boolean commandsEnabled) {
        this.transport = transport;
        this.channel = channel;
        this.commandsEnabled = commandsEnabled;
        this.deviceUuid = configuredUuid == null || configuredUuid.isBlank() ? null : configuredUuid.trim();
    }

    @Override
    public Reading read() throws DoorException {
        Map<String, Object> payload = transport.get(uuid(), NS_GARAGE_DOOR);
        return readingFrom(payload.get("state"));
    }

    @Override
    public void command(boolean open) throws DoorException {
        if (!commandsEnabled) {
            throw new DoorCommandRejectedException("Door commands are turned off (casita.meross.commands-enabled); nothing was sent");
        }
        String uuid;
        try {
            uuid = uuid();
        } catch (DoorException e) {
            throw new DoorCommandRejectedException("Couldn't find the opener, so nothing was sent: " + e.getMessage(), e);
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("channel", channel);
        state.put("open", open ? 1 : 0);
        state.put("uuid", uuid);
        transport.setOnce(uuid, NS_GARAGE_DOOR, Map.of("state", state));
    }

    /** {@code state} is a list (one entry per channel) or a single object, depending on firmware. */
    Reading readingFrom(Object stateNode) throws MerossException {
        List<?> entries = stateNode instanceof List<?> list ? list : stateNode == null ? List.of() : List.of(stateNode);
        List<Object> reported = new ArrayList<>();
        for (Object entry : entries) {
            Map<String, Object> s = MerossCloudApi.map(entry);
            Object ch = s.get("channel");
            reported.add(ch);
            if (ch == null || MerossCloudApi.number(ch) == channel) {
                int open = MerossCloudApi.number(s.get("open"));
                if (open != 0 && open != 1) break;
                long changed = s.get("lmTime") == null ? 0 : MerossCloudApi.number(s.get("lmTime"));
                return new Reading(open == 1, changed > 0 ? Instant.ofEpochSecond(changed) : null);
            }
        }
        // The channel numbers help when casita.meross.channel is wrong (a multi-door MSG200 may number doors from 1).
        throw new MerossException(Kind.PROTOCOL, "The opener didn't report the state of door channel " + channel
                + "; it reported channels " + reported);
    }

    /** The opener's uuid: the configured one, or the only opener on the account. */
    String uuid() throws DoorException {
        String u = deviceUuid;
        if (u != null) return u;
        List<MerossDeviceInfo> openers = transport.devices().stream()
                .filter(d -> d.type() != null && OPENER_TYPES.contains(d.type().toLowerCase(Locale.ROOT)))
                .toList();
        if (openers.isEmpty()) {
            throw new MerossException(Kind.NOT_FOUND, "No Meross garage door opener (MSG100 or MSG200) on this account");
        }
        if (openers.size() > 1) {
            // Names and the end of each uuid only: this message gets logged, and uuids stay out of logs.
            throw new MerossException(Kind.NOT_FOUND, "Several garage door openers on this account; set casita.meross.device-uuid to one of "
                    + openers.stream().map(MerossDeviceInfo::toString).toList());
        }
        deviceUuid = openers.get(0).uuid();
        return deviceUuid;
    }
}
