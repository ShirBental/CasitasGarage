package home.casita.garage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.DisposableBean;

@Component
public class ExecutorDelayedTasks implements DelayedTasks, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ExecutorDelayedTasks.class);

    private final Clock clock;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "casita-garage-tasks");
        t.setDaemon(true);
        return t;
    });

    public ExecutorDelayedTasks(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void runAt(Instant when, Runnable task) {
        long delayMillis = Math.max(0, Duration.between(clock.instant(), when).toMillis());
        executor.schedule(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Delayed garage task failed", e);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
