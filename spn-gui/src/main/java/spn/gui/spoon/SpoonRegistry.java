package spn.gui.spoon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Central registry of all .spoon commands.
 * Populated during {@link spn.gui.EditorWindow#initComponents} and
 * queried by the action menu and keyboard shortcuts.
 */
public class SpoonRegistry {

    private final List<SpoonCommand> commands = new ArrayList<>();

    public void register(SpoonCommand cmd) {
        commands.add(cmd);
    }

    public List<SpoonCommand> all() {
        return Collections.unmodifiableList(commands);
    }

    public SpoonCommand byName(String name) {
        for (SpoonCommand cmd : commands) {
            if (cmd.name().equals(name)) return cmd;
        }
        return null;
    }
}
