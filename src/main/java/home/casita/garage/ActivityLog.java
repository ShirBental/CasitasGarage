package home.casita.garage;

import java.util.List;

/** Where activity is kept. Storage is still an open question in the design doc. */
public interface ActivityLog {

    void record(ActivityEvent event);

    /** Newest first. */
    List<ActivityEvent> recent(int limit);
}
