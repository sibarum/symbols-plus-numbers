package spn.gui;

/**
 * A full-window interaction mode. The active mode receives all input and
 * controls what is rendered in the window area (above the HUD).
 *
 * <p>EditorWindow maintains a mode stack; the topmost mode is active.
 * {@link TabViewMode} is always at the bottom of the stack.
 */
public interface Mode {

    /**
     * Handle a GLFW key event.
     * @return true if the key was consumed
     */
    boolean onKey(int key, int scancode, int action, int mods);

    /**
     * Handle a GLFW character input event.
     * @return true if the codepoint was consumed
     */
    boolean onChar(int codepoint);

    /**
     * Handle a GLFW mouse button event.
     * @return true if the event was consumed
     */
    boolean onMouseButton(int button, int action, int mods, double mx, double my);

    /**
     * Handle a GLFW cursor position event.
     * @return true if the event was consumed
     */
    boolean onCursorPos(double mx, double my);

    /**
     * Handle a GLFW scroll event.
     * @return true if the event was consumed
     */
    boolean onScroll(double xoff, double yoff);

    /**
     * Handle the cursor entering or leaving the window.
     */
    default void onCursorEnter(boolean entered) {}

    /**
     * Render this mode's content. Called between font.beginText / font.endText.
     */
    void render(float width, float height);

    /**
     * Return the text to display in the HUD while this mode is active.
     */
    String hudText();
}
