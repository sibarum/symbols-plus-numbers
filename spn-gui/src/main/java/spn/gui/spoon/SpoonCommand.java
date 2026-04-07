package spn.gui.spoon;

import java.util.List;

/**
 * Definition of a .spoon command: a named action backed by a template file
 * and a handler that processes the submitted fields.
 *
 * @param name             display name (e.g. "Create Module")
 * @param category         action menu category (e.g. "File")
 * @param shortcut         keyboard shortcut hint (e.g. "Ctrl+M")
 * @param templateResource classpath resource path (e.g. "/spoon/create-module.spoon")
 * @param handler          called with parsed fields on submit
 * @param contextActions   command-specific key bindings available during editing
 */
public record SpoonCommand(
        String name,
        String category,
        String shortcut,
        String templateResource,
        SpoonHandler handler,
        List<ContextAction> contextActions
) {
    /** Convenience constructor for commands with no context actions. */
    public SpoonCommand(String name, String category, String shortcut,
                        String templateResource, SpoonHandler handler) {
        this(name, category, shortcut, templateResource, handler, List.of());
    }
}
