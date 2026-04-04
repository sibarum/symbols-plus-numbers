package cott.pem.traction;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple line-based text buffer for the editor.
 */
public class TextBuffer {

    @FunctionalInterface
    public interface ChangeListener {
        int LINE_CHANGED  = 0;
        int LINE_INSERTED = 1;
        int LINE_REMOVED  = 2;
        int BULK_CHANGE   = 3;
        void onChanged(int type, int row);
    }

    private final List<StringBuilder> lines = new ArrayList<>();
    private ChangeListener listener;

    public TextBuffer() {
        lines.add(new StringBuilder());
    }

    public void setChangeListener(ChangeListener listener) {
        this.listener = listener;
    }

    private void fire(int type, int row) {
        if (listener != null) listener.onChanged(type, row);
    }

    public int lineCount() {
        return lines.size();
    }

    public String getLine(int row) {
        if (row < 0 || row >= lines.size()) return "";
        return lines.get(row).toString();
    }

    public int lineLength(int row) {
        if (row < 0 || row >= lines.size()) return 0;
        return lines.get(row).length();
    }

    public int maxLineLength() {
        int max = 0;
        for (StringBuilder line : lines) max = Math.max(max, line.length());
        return max;
    }

    public void insertChar(int row, int col, char ch) {
        lines.get(row).insert(col, ch);
        fire(ChangeListener.LINE_CHANGED, row);
    }

    public void deleteChar(int row, int col) {
        lines.get(row).deleteCharAt(col);
        fire(ChangeListener.LINE_CHANGED, row);
    }

    public void splitLine(int row, int col) {
        StringBuilder line = lines.get(row);
        StringBuilder rest = new StringBuilder(line.substring(col));
        line.delete(col, line.length());
        lines.add(row + 1, rest);
        fire(ChangeListener.LINE_INSERTED, row + 1);
        fire(ChangeListener.LINE_CHANGED, row);
    }

    public void joinWithNext(int row) {
        if (row + 1 < lines.size()) {
            lines.get(row).append(lines.get(row + 1));
            lines.remove(row + 1);
            fire(ChangeListener.LINE_REMOVED, row + 1);
            fire(ChangeListener.LINE_CHANGED, row);
        }
    }

    public void joinWithPrevious(int row) {
        if (row > 0) {
            lines.get(row - 1).append(lines.get(row));
            lines.remove(row);
            fire(ChangeListener.LINE_REMOVED, row);
            fire(ChangeListener.LINE_CHANGED, row - 1);
        }
    }

    /** Get text in the range [r1,c1) to [r2,c2). Assumes r1,c1 <= r2,c2. */
    public String getTextRange(int r1, int c1, int r2, int c2) {
        if (r1 == r2) return lines.get(r1).substring(c1, c2);
        StringBuilder sb = new StringBuilder();
        sb.append(lines.get(r1).substring(c1));
        for (int r = r1 + 1; r < r2; r++) {
            sb.append('\n').append(lines.get(r));
        }
        sb.append('\n').append(lines.get(r2), 0, c2);
        return sb.toString();
    }

    /** Delete text in the range [r1,c1) to [r2,c2). Assumes r1,c1 <= r2,c2. */
    public void deleteRange(int r1, int c1, int r2, int c2) {
        if (r1 == r2) {
            lines.get(r1).delete(c1, c2);
            fire(ChangeListener.LINE_CHANGED, r1);
            return;
        }
        StringBuilder first = lines.get(r1);
        first.delete(c1, first.length());
        first.append(lines.get(r2).substring(c2));
        for (int i = r2; i > r1; i--) lines.remove(i);
        fire(ChangeListener.BULK_CHANGE, r1);
    }

    /** Insert (possibly multi-line) text. Returns {endRow, endCol} after insertion. */
    public int[] insertText(int row, int col, String text) {
        text = text.replace("\r\n", "\n").replace("\r", "\n");
        String[] parts = text.split("\n", -1);
        if (parts.length == 1) {
            lines.get(row).insert(col, parts[0]);
            fire(ChangeListener.LINE_CHANGED, row);
            return new int[]{row, col + parts[0].length()};
        }
        StringBuilder line = lines.get(row);
        String after = line.substring(col);
        line.delete(col, line.length());
        line.append(parts[0]);
        for (int i = 1; i < parts.length - 1; i++) {
            lines.add(row + i, new StringBuilder(parts[i]));
        }
        int lastRow = row + parts.length - 1;
        String lastPart = parts[parts.length - 1];
        lines.add(lastRow, new StringBuilder(lastPart).append(after));
        fire(ChangeListener.BULK_CHANGE, row);
        return new int[]{lastRow, lastPart.length()};
    }
}
