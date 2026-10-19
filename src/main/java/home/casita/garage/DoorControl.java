package home.casita.garage;

import java.util.Locale;

/**
 * How one door's commands are handled, decided per door from configuration. {@link GarageService}'s
 * rules apply in every mode; this only says where a command that passes them ends up.
 */
public enum DoorControl {
    /** The practice door ({@code casita.door=simulated}): commands move a pretend door, never a real one. */
    SIMULATED,
    /** Commands for this door are switched off: refused ({@link Outcome#DISABLED}); nothing is sent. */
    OFF,
    /** The real door's command is checked and built, never sent. Nothing moves. */
    DRY_RUN,
    /** Commands are sent to the real door. */
    LIVE;

    /** Whether a command that passes the rules moves the real door. Only then may the app say so. */
    public boolean live() {
        return this == LIVE;
    }

    /** As the API reports it: {@code simulated}, {@code off}, {@code dry-run} or {@code live}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
