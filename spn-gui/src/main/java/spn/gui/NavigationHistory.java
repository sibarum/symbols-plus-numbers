package spn.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Per-window navigation history (back / forward), like IntelliJ's
 * Ctrl+Alt+Left / Ctrl+Alt+Right. Records "stable positions" — points where
 * the user paused or made a deliberate action (edit, copy/cut/paste, tab
 * change, mode push/pop). Rapid actions in sequence collapse into the most
 * recent one via a time-based coalesce window.
 */
class NavigationHistory {

    /** Same-tab events arriving within this window replace the previous entry. */
    private static final double COALESCE_WINDOW = 0.4; // seconds
    /** Same-tab events that barely moved on the same line also collapse. */
    private static final int CLOSE_COL_EPSILON = 3;
    /** Cap to keep memory bounded; oldest entries fall off the front. */
    private static final int MAX_ENTRIES = 100;

    record Entry(Tab tab, int row, int col, int scrollRow, int scrollCol, double timestamp) {}

    private final List<Entry> entries = new ArrayList<>();
    /** Index of the "current" entry; -1 when empty. */
    private int cursor = -1;
    /** Suppresses recording while we're applying a back/forward jump. */
    private boolean navigating;

    void record(Tab tab, int row, int col, int scrollRow, int scrollCol, double now) {
        if (navigating || tab == null) return;

        // Typing/clicking after a back jump branches: drop the forward tail.
        while (entries.size() > cursor + 1) entries.remove(entries.size() - 1);

        Entry next = new Entry(tab, row, col, scrollRow, scrollCol, now);
        if (!entries.isEmpty()) {
            Entry last = entries.get(entries.size() - 1);
            boolean sameTab = last.tab == tab;
            boolean closeInTime = (now - last.timestamp) < COALESCE_WINDOW;
            boolean closePos = sameTab && last.row == row
                    && Math.abs(last.col - col) <= CLOSE_COL_EPSILON;
            if (sameTab && (closeInTime || closePos)) {
                entries.set(entries.size() - 1, next);
                cursor = entries.size() - 1;
                return;
            }
        }
        entries.add(next);
        if (entries.size() > MAX_ENTRIES) entries.remove(0);
        cursor = entries.size() - 1;
    }

    /** Move cursor back, skipping entries whose tab is no longer alive. */
    Entry back(Predicate<Tab> alive) {
        int probe = cursor;
        while (probe > 0) {
            probe--;
            Entry e = entries.get(probe);
            if (alive.test(e.tab)) {
                cursor = probe;
                return e;
            }
        }
        return null;
    }

    /** Move cursor forward, skipping entries whose tab is no longer alive. */
    Entry forward(Predicate<Tab> alive) {
        int probe = cursor;
        while (probe < entries.size() - 1) {
            probe++;
            Entry e = entries.get(probe);
            if (alive.test(e.tab)) {
                cursor = probe;
                return e;
            }
        }
        return null;
    }

    void setNavigating(boolean v) { navigating = v; }
}
