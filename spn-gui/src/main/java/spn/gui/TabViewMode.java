package spn.gui;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Bridge between the TabView and the legacy Mode stack.
 * This sits at the bottom of the mode stack (where EditorMode used to be).
 * It delegates all input and rendering to the TabView, which in turn
 * delegates to the active Tab.
 *
 * <p>Escape handling: if the active tab is the last editor tab, prompt
 * for save. Otherwise close the tab and switch to the next one.
 */
class TabViewMode implements Mode {

    private final EditorWindow window;
    private final TabView tabView;

    TabViewMode(EditorWindow window, TabView tabView) {
        this.window = window;
        this.tabView = tabView;
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        // Let the active tab handle Escape first (e.g., to unpin a trace view)
        // Only close the tab if the tab didn't consume it
        if (tabView.onKey(key, scancode, action, mods)) {
            return true;
        }
        if (action == GLFW_PRESS && key == GLFW_KEY_ESCAPE) {
            window.handleTabClose();
            return true;
        }
        return false;
    }

    @Override
    public boolean onChar(int codepoint) {
        return tabView.onChar(codepoint);
    }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        return tabView.onMouseButton(button, action, mods, mx, my);
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        return tabView.onCursorPos(mx, my);
    }

    @Override
    public boolean onScroll(double xoff, double yoff) {
        return tabView.onScroll(xoff, yoff);
    }

    @Override
    public void onCursorEnter(boolean entered) {
        tabView.onCursorEnter(entered);
    }

    @Override
    public void render(float width, float height) {
        tabView.render(width, height);
    }

    @Override
    public String hudText() {
        String base = tabView.hudText();
        if (tabView.tabCount() > 1) {
            return "Ctrl+Tab Switch | " + base;
        }
        return base;
    }
}
