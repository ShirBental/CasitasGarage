package home.casita.garage;

import java.time.Instant;

/** Runs a task later. Its own small interface so tests can control time exactly. */
public interface DelayedTasks {

    void runAt(Instant when, Runnable task);
}
