package spn.gui;

import spn.claude.ActivityLog;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Read-only tab showing the Claude activity log. Refreshes from the
 * underlying {@link ActivityLog} every render frame, so new entries
 * appear without user action.
 */
final class ClaudeLogTab extends ScrollableTab {

    private final ActivityLog log;
    private int lastSize;

    ClaudeLogTab(EditorWindow window, ActivityLog log) {
        super(window);
        this.log = log;
        refresh(true);
    }

    @Override public String label() { return "Claude Log"; }

    @Override public boolean isDirty() { return false; }

    @Override
    public void render(float x, float y, float width, float height) {
        refresh(false);
        layoutAndRender(x, y, width, height);
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return true;
        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;
        if (ctrl && (key == GLFW_KEY_C || key == GLFW_KEY_A)) {
            textArea.onKey(key, mods);
            return true;
        }
        if (key == GLFW_KEY_UP || key == GLFW_KEY_DOWN
                || key == GLFW_KEY_PAGE_UP || key == GLFW_KEY_PAGE_DOWN
                || key == GLFW_KEY_HOME || key == GLFW_KEY_END
                || key == GLFW_KEY_LEFT || key == GLFW_KEY_RIGHT) {
            textArea.onKey(key, mods);
            return true;
        }
        if (key == GLFW_KEY_ESCAPE) return false;
        return true;
    }

    @Override public boolean onChar(int codepoint) { return true; }

    @Override
    public String hudText() {
        return "Claude Log (" + lastSize + " entries) | Esc Close Tab";
    }

    private void refresh(boolean force) {
        var entries = log.snapshotTail();
        if (!force && entries.size() == lastSize) return;
        lastSize = entries.size();
        StringBuilder sb = new StringBuilder();
        for (String e : entries) sb.append(e).append('\n');
        int prevScroll = textArea.getScrollRow();
        boolean atBottom = prevScroll + textArea.getVisibleRows() >= textArea.getContentRows() - 1;
        textArea.setText(sb.toString());
        if (atBottom) {
            int total = textArea.getContentRows();
            int visible = textArea.getVisibleRows();
            if (visible <= 0) visible = 20;
            textArea.setScrollRow(Math.max(0, total - visible));
        } else {
            textArea.setScrollRow(prevScroll);
        }
    }
}
