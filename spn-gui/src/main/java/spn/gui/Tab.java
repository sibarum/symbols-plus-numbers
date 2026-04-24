package spn.gui;

/**
 * A tab in the TabView. Each tab has its own content area, label, and dirty state.
 * Tabs receive input and render when active.
 */
public interface Tab {

    /** Short label for the tab bar (e.g., filename or "Logs"). */
    String label();

    /** Whether this tab has unsaved changes. */
    boolean isDirty();

    /** Render this tab's content. Called between font.beginFrame/endFrame. */
    void render(float x, float y, float width, float height);

    /** Handle a GLFW key event. Return true if consumed. */
    boolean onKey(int key, int scancode, int action, int mods);

    /** Handle a GLFW character input event. Return true if consumed. */
    boolean onChar(int codepoint);

    /** Handle a GLFW mouse button event. Return true if consumed. */
    boolean onMouseButton(int button, int action, int mods, double mx, double my);

    /** Handle a GLFW cursor position event. Return true if consumed. */
    boolean onCursorPos(double mx, double my);

    /** Handle a GLFW scroll event. Return true if consumed. */
    boolean onScroll(double xoff, double yoff);

    /** Handle the cursor entering or leaving the window. */
    default void onCursorEnter(boolean entered) {}

    /** Text to display in the HUD while this tab is active. */
    String hudText();

    /** Called when this tab becomes the active tab. */
    default void onActivated() {}

    /** Called when this tab stops being the active tab (switch away or close). */
    default void onDeactivated() {}

    /** Called when this tab is about to be closed. Return false to cancel. */
    default boolean onClose() { return true; }
}
