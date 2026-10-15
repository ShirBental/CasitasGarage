package home.casita.garage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Keeps the latest events in memory. Placeholder until we decide where activity lives
 * (local file, DynamoDB or CloudWatch); it is lost on restart.
 */
@Component
public class InMemoryActivityLog implements ActivityLog {

    private static final int MAX_EVENTS = 500;

    private final Deque<ActivityEvent> events = new ArrayDeque<>();

    @Override
    public synchronized void record(ActivityEvent event) {
        events.addFirst(event);
        while (events.size() > MAX_EVENTS) {
            events.removeLast();
        }
    }

    @Override
    public synchronized List<ActivityEvent> recent(int limit) {
        List<ActivityEvent> out = new ArrayList<>(Math.min(limit, events.size()));
        for (ActivityEvent e : events) {
            if (out.size() == limit) break;
            out.add(e);
        }
        return out;
    }
}
