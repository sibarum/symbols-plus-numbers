package spn.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Registry of all available actions (commands). Populated during window init
 * and queried by the action menu for filtering and execution.
 */
public class ActionRegistry {

    private final List<Action> actions = new ArrayList<>();

    /** Register a new action. */
    public void register(Action action) {
        actions.add(action);
    }

    /** Register a new action from individual fields. */
    public void register(String name, String category, String shortcut, Runnable execute) {
        actions.add(new Action(name, category, shortcut, execute));
    }

    /** Return all registered actions. */
    public List<Action> all() {
        return Collections.unmodifiableList(actions);
    }

    /**
     * Return actions whose name contains the query (case-insensitive).
     * Returns all actions if the query is empty.
     */
    public List<Action> filter(String query) {
        if (query == null || query.isEmpty()) return all();
        String lower = query.toLowerCase();
        List<Action> result = new ArrayList<>();
        for (Action a : actions) {
            if (a.name().toLowerCase().contains(lower)
                    || a.category().toLowerCase().contains(lower)) {
                result.add(a);
            }
        }
        return result;
    }
}
