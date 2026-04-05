package spn.gui;

/**
 * A named, executable command that can appear in the action menu.
 *
 * @param name     display name (e.g. "Save File")
 * @param category grouping label (e.g. "File", "Edit", "Template")
 * @param shortcut keyboard shortcut hint (e.g. "Ctrl+S"), or empty string
 * @param execute  the action to perform
 */
public record Action(String name, String category, String shortcut, Runnable execute) {}
