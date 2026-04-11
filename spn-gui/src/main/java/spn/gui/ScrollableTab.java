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
        float hudH = window.getHudHeight();
        float bottomBar = hudH + SCROLLBAR_SIZE;

        textArea.setBounds(x, y, width - SCROLLBAR_SIZE, height - bottomBar);
        vScroll.setBounds(x + width - SCROLLBAR_SIZE, y, SCROLLBAR_SIZE, height - bottomBar);
        hScroll.setBounds(x, y + height - SCROLLBAR_SIZE, width - SCROLLBAR_SIZE, SCROLLBAR_SIZE);

        textArea.render();

        vScroll.setContent(textArea.getContentRows(), textArea.getVisibleRows());
        vScroll.setValue(textArea.getScrollRow());
        vScroll.render();

        hScroll.setContent(textArea.getContentCols(), textArea.getVisibleCols());
        hScroll.setValue(textArea.getScrollCol());
        hScroll.render();
    }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        vScroll.onMouseButton(button, action, mods, mx, my);
        hScroll.onMouseButton(button, action, mods, mx, my);
        if (!vScroll.isDragging() && !hScroll.isDragging()) {
            textArea.onMouseButton(button, action, mods, mx, my);
        }
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        vScroll.onCursorPos(mx, my);
        hScroll.onCursorPos(mx, my);
        if (!vScroll.isDragging() && !hScroll.isDragging()) {
            textArea.onCursorPos(mx, my);
        }
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
