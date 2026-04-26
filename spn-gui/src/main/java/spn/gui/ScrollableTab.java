package spn.gui;

import spn.stdui.widget.ScrollbarTheme;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Base class for tabs that display a TextArea with scrollbars.
 * Eliminates duplicated scrollbar setup, clipboard wiring,
 * render layout, and mouse/scroll delegation.
 */
abstract class ScrollableTab implements Tab {

    protected static final float SCROLLBAR_SIZE = 12f;

    protected final EditorWindow window;
    protected final TextArea textArea;
    protected final Scrollbar vScroll;
    protected final Scrollbar hScroll;

    protected ScrollableTab(EditorWindow window) {
        this.window = window;
        this.textArea = new TextArea(window.getFont());
        textArea.setClipboard(new TextArea.ClipboardHandler() {
            @Override public void set(String text) {
                glfwSetClipboardString(window.getHandle(), text);
            }
            @Override public String get() {
                return glfwGetClipboardString(window.getHandle());
            }
        });

        ScrollbarTheme sbTheme = ScrollbarTheme.dark();
        vScroll = new Scrollbar(window.getFont(), Scrollbar.Orientation.VERTICAL);
        vScroll.setTheme(sbTheme);
        vScroll.setOnChange(v -> textArea.setScrollRow(v));

        hScroll = new Scrollbar(window.getFont(), Scrollbar.Orientation.HORIZONTAL);
        hScroll.setTheme(sbTheme);
        hScroll.setOnChange(v -> textArea.setScrollCol(v));
    }

    /** Standard layout: TextArea fills content area, scrollbars on right and bottom. */
    protected void layoutAndRender(float x, float y, float width, float height) {
        renderWithScrollbars(textArea, vScroll, hScroll,
                x, y, width, height, window.getHudHeight());
    }

    /**
     * Lay out and render a {@link TextArea} flanked by a vertical and horizontal
     * scrollbar. The text area occupies the box minus the right edge (vScroll)
     * and the bottom edge (hScroll). {@code bottomReserved} is extra space kept
     * between the bottom of the text area and the horizontal scrollbar — used
     * by {@link ScrollableTab} to leave room for a per-window HUD strip.
     */
    static void renderWithScrollbars(TextArea ta, Scrollbar v, Scrollbar h,
                                     float x, float y, float width, float height,
                                     float bottomReserved) {
        float bottomBar = bottomReserved + SCROLLBAR_SIZE;
        ta.setBounds(x, y, width - SCROLLBAR_SIZE, height - bottomBar);
        v.setBounds(x + width - SCROLLBAR_SIZE, y, SCROLLBAR_SIZE, height - bottomBar);
        h.setBounds(x, y + height - SCROLLBAR_SIZE, width - SCROLLBAR_SIZE, SCROLLBAR_SIZE);

        ta.render();

        v.setContent(ta.getContentRows(), ta.getVisibleRows());
        v.setValue(ta.getScrollRow());
        v.render();

        h.setContent(ta.getContentCols(), ta.getVisibleCols());
        h.setValue(ta.getScrollCol());
        h.render();
    }

    /**
     * Forward a left-button event to the scrollbars; if neither is dragging,
     * also forward to the text area. Returns {@code true} when the event
     * resulted in scrollbar drag activation.
     */
    static boolean dispatchMousePressToScrolled(TextArea ta, Scrollbar v, Scrollbar h,
                                                int button, int action, int mods,
                                                double mx, double my) {
        v.onMouseButton(button, action, mods, mx, my);
        h.onMouseButton(button, action, mods, mx, my);
        if (!v.isDragging() && !h.isDragging()) {
            ta.onMouseButton(button, action, mods, mx, my);
            return false;
        }
        return true;
    }

    static void dispatchCursorToScrolled(TextArea ta, Scrollbar v, Scrollbar h,
                                         double mx, double my) {
        v.onCursorPos(mx, my);
        h.onCursorPos(mx, my);
        if (!v.isDragging() && !h.isDragging()) {
            ta.onCursorPos(mx, my);
        }
    }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        dispatchMousePressToScrolled(textArea, vScroll, hScroll, button, action, mods, mx, my);
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        dispatchCursorToScrolled(textArea, vScroll, hScroll, mx, my);
        return true;
    }

    @Override
    public boolean onScroll(double xoff, double yoff) {
        textArea.onScroll(xoff, yoff);
        return true;
    }

    @Override
    public void onCursorEnter(boolean entered) {
        textArea.onCursorEnter(entered);
    }
}
