package spn.gui.spoon;

import spn.gui.TextArea;

import java.util.function.Consumer;

/**
 * A command-specific key binding available while editing a .spoon file.
 * Shown in the help overlay and intercepted by CommandMode.
 *
 * @param label        display label (e.g. "Add constraint")
 * @param shortcutHint display shortcut (e.g. "Ctrl+1")
 * @param glfwKey      GLFW key code to match
 * @param requiredMods GLFW modifier mask (e.g. GLFW_MOD_CONTROL)
 * @param action       callback receiving the CommandMode's TextArea
 */
public record ContextAction(
        String label,
        String shortcutHint,
        int glfwKey,
        int requiredMods,
        Consumer<TextArea> action
) {}
