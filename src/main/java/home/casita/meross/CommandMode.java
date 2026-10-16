package home.casita.meross;

/** Whether Casita may send open/close commands to the real door ({@code casita.meross.command-mode}). */
public enum CommandMode {
    /** Every command is rejected before anything is built or sent. The default. */
    OFF,
    /**
     * Every check runs and the signed command is built, but it is never published: the command is
     * then rejected as "nothing was sent". For trying the command path without moving a door.
     */
    DRY_RUN,
    /** Commands are sent, once each, to the channels in {@code casita.meross.command-channels} only. */
    LIVE
}
