package spn.gui;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Read-only tab showing the log buffer. Auto-refreshes when new entries arrive.
 * Scrolled to bottom on open. Allows copy and navigation but not editing.
 */
public class LogTab extends ScrollableTab {

    private final LogBuffer logBuffer;
    private String lastSnapshot = "";

    LogTab(EditorWindow window) {
        super(window);
        this.logBuffer = window.getLogBuffer();
        refreshContent();
        scrollToBottom();
    }

    @Override
    public String label() { return "Logs"; }

    @Override
    public boolean isDirty() { return false; }

    @Override
    public void render(float x, float y, float width, float height) {
        // Refresh if log has new content
        String current = logBuffer.getText();
        if (!current.equals(lastSnapshot)) {
            refreshContent();
        }
        layoutAndRender(x, y, width, height);
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return true;
        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

        // Allow copy and select-all
        if (ctrl && (key == GLFW_KEY_C || key == GLFW_KEY_A)) {
            textArea.onKey(key, mods);
            return true;
        }
        // Allow navigation
        if (key == GLFW_KEY_UP || key == GLFW_KEY_DOWN
                || key == GLFW_KEY_PAGE_UP || key == GLFW_KEY_PAGE_DOWN
                || key == GLFW_KEY_HOME || key == GLFW_KEY_END
                || key == GLFW_KEY_LEFT || key == GLFW_KEY_RIGHT) {
            textArea.onKey(key, mods);
            return true;
        }
        if (key == GLFW_KEY_ESCAPE) return false; // let TabViewMode close
        return true; // swallow editing keys
    }

    @Override
    public boolean onChar(int codepoint) { return true; }

    @Override
    public String hudText() {
        return "Logs (" + logBuffer.lineCount() + " entries) | Esc Close Tab";
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
        if (visible <= 0) visible = 20;
        textArea.setScrollRow(Math.max(0, total - visible));
        textArea.setCursorPosition(Math.max(0, total - 1), 0);
    }
}
