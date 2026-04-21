package spn.canvasgui.spn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects event tags returned by widget handler CallTargets.
 * The runtime drains this queue once per frame and passes the collected
 * tags to the user's {@code frame(state, events)} function.
 */
public final class EventQueue {

    private final List<Object> pending = new ArrayList<>();

    public synchronized void post(Object tag) {
        if (tag != null) pending.add(tag);
    }

    /** Atomically snapshot and clear. */
    public synchronized List<Object> drain() {
        if (pending.isEmpty()) return Collections.emptyList();
        List<Object> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }

    public synchronized void clear() {
        pending.clear();
    }
}
