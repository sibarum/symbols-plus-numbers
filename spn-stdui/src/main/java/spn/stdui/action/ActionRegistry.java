package spn.stdui.action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Registry of named executable actions. Supports fuzzy filtering
 * for command palette search.
 */
public class ActionRegistry {

    private final List<Action> actions = new ArrayList<>();

    public void register(Action action) {
        actions.add(action);
    }

    public void register(String name, String category, String shortcut, Runnable execute) {
        actions.add(new Action(name, category, shortcut, execute));
    }

    public void register(String name, String category, String shortcut, String description, Runnable execute) {
        actions.add(new Action(name, category, shortcut, description, execute));
    }

    public List<Action> all() {
        return Collections.unmodifiableList(actions);
    }

    /** Filter actions by case-insensitive substring match on name, category, shortcut, or description. */
    public List<Action> filter(String query) {
        if (query == null || query.isEmpty()) return all();
        String lower = query.toLowerCase();
        List<Action> result = new ArrayList<>();
        for (Action a : actions) {
            if (a.name().toLowerCase().contains(lower)
                    || a.category().toLowerCase().contains(lower)
                    || a.shortcut().toLowerCase().contains(lower)
                    || a.description().toLowerCase().contains(lower)) {
                result.add(a);
            }
        }
        return result;
    }
}
