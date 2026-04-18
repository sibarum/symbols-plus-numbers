package spn.gui;

/**
 * Bridge between the TabView and the legacy Mode stack.
 * This sits at the bottom of the mode stack (where EditorMode used to be).
 * It delegates all input and rendering to the TabView, which in turn
 * delegates to the active Tab.
 *
 * <p>Escape handling: forwarded to the active tab only. The base view
 * itself never treats Escape as a close signal — Escape is scoped to the
 * current tab (to cancel an in-progress action like search, unpin a trace
 * view, etc.) and never propagates to the window, module context, or
 * Save Changes dialog. Window close is solely the X button's job.
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
        // Escape is scoped to the active tab. If the tab doesn't consume it
        // (no in-progress action to cancel), Escape is a no-op at this level —
        // it must not close the tab, open a dirty prompt, or close the window.
        return tabView.onKey(key, scancode, action, mods);
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
