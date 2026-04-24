package spn.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Read-only tab showing the log buffer. Auto-refreshes when new entries arrive.
 * Scrolled to bottom on open. Allows copy and navigation but not editing.
 *
 * <p>Recognizes {@code path.spn:line[:col]} patterns in the log and makes them
 * clickable — a left-click opens the file (or switches to its tab) and moves
 * the cursor to the line.
 */
public class LogTab extends ScrollableTab {

    // Matches absolute Windows paths, absolute POSIX paths, and relative paths
    // ending in .spn, followed by :line and an optional :col.
    private static final Pattern LINK = Pattern.compile(
            "(?<path>(?:[A-Za-z]:[\\\\/])?[^\\s:]+\\.spn):(?<line>\\d+)(?::(?<col>\\d+))?");

    private final LogBuffer logBuffer;
    private String lastSnapshot = "";
    private final List<LogLink> links = new ArrayList<>();

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
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS
                && mx >= textArea.getBoundsX()
                && mx < textArea.getBoundsX() + textArea.getBoundsW()
                && my >= textArea.getBoundsY()
                && my < textArea.getBoundsY() + textArea.getBoundsH()) {
            int[] pos = textArea.screenToDocPos(mx, my);
            LogLink hit = linkAt(pos[0], pos[1]);
            if (hit != null) {
                window.openFileAtLine(hit.path, hit.line, hit.col);
                return true;
            }
        }
        return super.onMouseButton(button, action, mods, mx, my);
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
        rescanLinks(lastSnapshot);
    }

    private void scrollToBottom() {
        int total = textArea.getContentRows();
        int visible = textArea.getVisibleRows();
        if (visible <= 0) visible = 20;
        textArea.setScrollRow(Math.max(0, total - visible));
        textArea.setCursorPosition(Math.max(0, total - 1), 0);
    }

    private void rescanLinks(String text) {
        links.clear();
        String[] lines = text.split("\n", -1);
        for (int row = 0; row < lines.length; row++) {
            String line = lines[row];
            Matcher m = LINK.matcher(line);
            while (m.find()) {
                String raw = m.group("path");
                int lineNum = Integer.parseInt(m.group("line"));
                int colNum = m.group("col") != null ? Integer.parseInt(m.group("col")) : 1;
                Path p = resolveLink(raw);
                if (p == null) continue;
                links.add(new LogLink(row, m.start(), m.end(), p, lineNum, colNum));
            }
        }
    }

    /** Resolve the matched path to an existing file, or return null. */
    private Path resolveLink(String raw) {
        try {
            Path p = Path.of(raw);
            if (p.isAbsolute() && Files.isRegularFile(p)) return p;
        } catch (Exception ignored) {
            // Invalid path syntax — skip
        }
        return null;
    }

    private LogLink linkAt(int row, int col) {
        for (LogLink l : links) {
            if (l.row == row && col >= l.colStart && col < l.colEnd) return l;
        }
        return null;
    }

    private record LogLink(int row, int colStart, int colEnd, Path path, int line, int col) {}
}
