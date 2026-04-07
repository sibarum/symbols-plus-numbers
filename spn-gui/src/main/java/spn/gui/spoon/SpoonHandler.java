package spn.gui.spoon;

import spn.gui.EditorWindow;

import java.util.Map;

/**
 * Handler for a .spoon command submission.
 * Receives the parsed fields and the editor window context.
 * Throws any exception on failure — the exception is displayed in ErrorMode.
 */
@FunctionalInterface
public interface SpoonHandler {
    void handle(Map<String, String> fields, EditorWindow window) throws Exception;
}
