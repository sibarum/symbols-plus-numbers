package spn.stdui.action;

/**
 * A named, executable command that can appear in the action palette and help index.
 *
 * @param name        display name (e.g. "Save File")
 * @param category    grouping label (e.g. "File")
 * @param shortcut    keyboard shortcut hint (e.g. "Ctrl+S")
 * @param description longer help text shown in the help detail view
 * @param execute     the action logic
 */
public record Action(String name, String category, String shortcut, String description, Runnable execute) {

    /** Convenience constructor without description. */
    public Action(String name, String category, String shortcut, Runnable execute) {
        this(name, category, shortcut, "", execute);
    }
}
