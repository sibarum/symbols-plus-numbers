package spn.stdui.action;

/**
 * A named, executable command that can appear in the action palette.
 *
 * @param name     display name (e.g. "Save File")
 * @param category grouping label (e.g. "File")
 * @param shortcut keyboard shortcut hint (e.g. "Ctrl+S")
 * @param execute  the action logic
 */
public record Action(String name, String category, String shortcut, Runnable execute) {}
