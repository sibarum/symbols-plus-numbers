package spn.gui;

import spn.stdui.widget.ScrollbarTheme;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Read-only view of the log buffer (Ctrl+G). Scrolled to bottom on open.
 * Escape pops it. The log updates live if new messages arrive.
 */
class LogViewMode implements Mode {

    private static final float SCROLLBAR_SIZE = 12f;

    private final EditorWindow window;
    private final LogBuffer logBuffer;
    private final TextArea textArea;
    private final Scrollbar vScroll;
    private String lastSnapshot = "";

    LogViewMode(EditorWindow window) {
        this.window = window;
        this.logBuffer = window.getLogBuffer();

        this.textArea = new TextArea(window.getFont());
        this.textArea.setClipboard(new TextArea.ClipboardHandler() {
            @Override public void set(String text) {
                glfwSetClipboardString(window.getHandle(), text);
            }
            @Override public String get() {
                return glfwGetClipboardString(window.getHandle());
            }
        });

        this.vScroll = new Scrollbar(window.getFont(), Scrollbar.Orientation.VERTICAL);
        vScroll.setTheme(ScrollbarTheme.dark());
        vScroll.setOnChange(v -> textArea.setScrollRow(v));

        refreshContent();
        scrollToBottom();
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return true;

        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

        if (key == GLFW_KEY_ESCAPE) {
            window.popMode();
            return true;
        }
        // Allow copy
        if (ctrl && key == GLFW_KEY_C) {
            textArea.onKey(key, mods);
            return true;
        }
        // Allow navigation
        if (key == GLFW_KEY_UP || key == GLFW_KEY_DOWN
                || key == GLFW_KEY_PAGE_UP || key == GLFW_KEY_PAGE_DOWN
                || key == GLFW_KEY_HOME || key == GLFW_KEY_END) {
            textArea.onKey(key, mods);
            return true;
        }
        return true; // swallow editing keys
    }

    @Override
    public boolean onChar(int codepoint) { return true; }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        vScroll.onMouseButton(button, action, mods, mx, my);
        if (!vScroll.isDragging()) {
            textArea.onMouseButton(button, action, mods, mx, my);
        }
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        vScroll.onCursorPos(mx, my);
        if (!vScroll.isDragging()) {
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

    @Override
    public void render(float width, float height) {
        // Refresh if log has new content
        String current = logBuffer.getText();
        if (!current.equals(lastSnapshot)) {
            refreshContent();
        }

        float hudH = window.getHudHeight();
        float bottomBar = hudH + SCROLLBAR_SIZE;

        textArea.setBounds(0, 0, width - SCROLLBAR_SIZE, height - bottomBar);
        vScroll.setBounds(width - SCROLLBAR_SIZE, 0, SCROLLBAR_SIZE, height - bottomBar);

        textArea.render();

        vScroll.setContent(textArea.getContentRows(), textArea.getVisibleRows());
        vScroll.setValue(textArea.getScrollRow());
        vScroll.render();
    }

    @Override
    public String hudText() {
        return "Logs (" + logBuffer.lineCount() + " entries) | Esc Close";
    }

    private void refreshContent() {
        lastSnapshot = logBuffer.getText();
        int prevScroll = textArea.getScrollRow();
        textArea.setText(lastSnapshot);
        textArea.setScrollRow(prevScroll);
    }

    private void scrollToBottom() {
        int total = textArea.getContentRows();
        int visible = textArea.getVisibleRows();
        if (visible <= 0) visible = 20; // estimate before first render
        textArea.setScrollRow(Math.max(0, total - visible));
        textArea.setCursorPosition(Math.max(0, total - 1), 0);
    }
}
