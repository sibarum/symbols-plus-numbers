package spn.gui;

import spn.fonts.ColorSpan;
import spn.fonts.SdfFontRenderer;
import spn.gui.lang.HighlightCache;
import spn.lang.Token;
import spn.lang.TokenType;

import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Reusable GPU-accelerated monospace text area component.
 *
 * Owns its own {@link TextBuffer}, cursor, selection, scroll, and blink state.
 * Renders via a shared {@link SdfFontRenderer} — call {@link #render()} between
 * {@code font.beginText()} and {@code font.endText()}.
 *
 * Input is forwarded from the host window through the {@code on*()} methods.
 */
public class TextArea {

    private final SdfFontRenderer font;
    private final TextBuffer buffer;

    // Bounds in screen pixels
    private float boundsX, boundsY, boundsW, boundsH;

    // Font / grid metrics
    private static final float FONT_SCALE_DEFAULT = 0.35f;
    private float fontScale = FONT_SCALE_DEFAULT;
    private static final float FONT_SCALE_MIN = 0.10f;
    private static final float FONT_SCALE_MAX = 2.0f;
    private static final float FONT_SCALE_STEP = 0.025f;
    private float cellWidth;
    private float cellHeight;
    private static final float PAD = 10f;
    private static final float GUTTER_PAD = 8f;  // gap between line numbers and text
    // Vertical offset for highlight rects so they cover descenders (g, y, p)
    // rather than sitting too high above the text baseline.
    private static final float HIGHLIGHT_OFFSET_Y = 9f;

    // Cursor position in the document
    private int cursorRow;
    private int cursorCol;

    // Viewport scroll offset (in rows/cols)
    private int scrollRow;
    private int scrollCol;

    // Selection anchor (-1 = no selection)
    private int selAnchorRow = -1;
    private int selAnchorCol = -1;

    // Mouse state
    private int phantomRow = -1;
    private int phantomCol = -1;
    private boolean mouseDragging;
    private boolean mouseInBounds;

    // Double-click / word-drag state
    private static final double DOUBLE_CLICK_TIME = 0.4;
    private double lastClickTime;
    private int lastClickRow, lastClickCol;
    private boolean wordDragging;
    private int wordDragRow, wordDragStartCol, wordDragEndCol;

    // When true, render() will scroll the viewport to keep the cursor visible.
    // Set on keyboard input; cleared after applying.
    private boolean scrollToCursor;

    // Cursor blink
    private double lastBlinkTime;
    private boolean cursorVisible = true;
    private static final double BLINK_RATE = 0.53;

    // Clipboard bridge — set by the host so this component stays window-agnostic
    private ClipboardHandler clipboard = ClipboardHandler.NOOP;

    // Syntax highlighting
    private final HighlightCache highlightCache = new HighlightCache();

    // Undo/redo
    private final UndoManager undo = new UndoManager();
    private boolean undoRedoInProgress;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    @FunctionalInterface
    public interface ClipboardHandler {
        ClipboardHandler NOOP = new ClipboardHandler() {
            @Override public void set(String text) {}
            @Override public String get() { return ""; }
        };
        void set(String text);
        default String get() { return ""; }
    }

    public TextArea(SdfFontRenderer font) {
        this.font = font;
        this.buffer = new TextBuffer();
        buffer.setChangeListener((type, row) -> {
            switch (type) {
                case TextBuffer.ChangeListener.LINE_CHANGED  -> highlightCache.invalidateLine(row);
                case TextBuffer.ChangeListener.LINE_INSERTED -> highlightCache.insertLine(row);
                case TextBuffer.ChangeListener.LINE_REMOVED  -> highlightCache.removeLine(row);
                case TextBuffer.ChangeListener.BULK_CHANGE   -> highlightCache.invalidateAll();
            }
        });
        recomputeMetrics();
    }

    public void setBounds(float x, float y, float w, float h) {
        boundsX = x; boundsY = y; boundsW = w; boundsH = h;
    }

    public void setClipboard(ClipboardHandler clipboard) {
        this.clipboard = clipboard;
    }

    public void setFontScale(float scale) {
        this.fontScale = scale;
        recomputeMetrics();
    }

    public TextBuffer getBuffer() { return buffer; }

    public UndoManager.Info getUndoInfo() { return undo.getInfo(); }

    UndoManager getUndoManager() { return undo; }

    public int getCursorRow() { return cursorRow; }
    public int getCursorCol() { return cursorCol; }

    void setCursorPosition(int row, int col) {
        cursorRow = Math.max(0, Math.min(row, buffer.lineCount() - 1));
        cursorCol = Math.max(0, Math.min(col, buffer.lineLength(cursorRow)));
        clearSelection();
    }

    public boolean isCurrentLineBlank() {
        return buffer.lineLength(cursorRow) == 0;
    }

    public HighlightCache getHighlightCache() { return highlightCache; }

    /**
     * Inserts a code snippet at the cursor, recording an undo entry.
     * The cursor is placed at the end of the inserted text.
     */
    public void insertSnippet(String snippet) {
        int rB = cursorRow, cB = cursorCol;
        String selRem = removeSelection();
        int eRow = cursorRow, eCol = cursorCol;
        int[] end = buffer.insertText(cursorRow, cursorCol, snippet);
        cursorRow = end[0];
        cursorCol = end[1];
        undo.record(eRow, eCol, selRem, snippet, rB, cB, cursorRow, cursorCol);
        scrollToCursor = true;
        resetBlink();
    }

    public void switchUndoBranch(int direction) { undo.switchBranch(direction); }

    public String getText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < buffer.lineCount(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(buffer.getLine(i));
        }
        return sb.toString();
    }

    public void setText(String text) {
        // Clear buffer and repopulate
        while (buffer.lineCount() > 1) buffer.deleteRange(0, 0, buffer.lineCount() - 1, buffer.lineLength(buffer.lineCount() - 1));
        if (buffer.lineLength(0) > 0) buffer.deleteRange(0, 0, 0, buffer.lineLength(0));
        if (text != null && !text.isEmpty()) {
            buffer.insertText(0, 0, text);
        }
        cursorRow = 0; cursorCol = 0;
        scrollRow = 0; scrollCol = 0;
        clearSelection();
        highlightCache.invalidateAll();
        undo.clear();
    }

    // ------------------------------------------------------------------
    // Scroll state — for external scrollbar integration
    // ------------------------------------------------------------------

    public int getScrollRow() { return scrollRow; }
    public int getScrollCol() { return scrollCol; }

    public void setScrollRow(int row) { scrollRow = clampScrollRow(row); }
    public void setScrollCol(int col) { scrollCol = clampScrollCol(col); }

    public int getContentRows() { return buffer.lineCount(); }
    public int getContentCols() { return buffer.maxLineLength() + 1; }

    public int getVisibleRows() { return cellHeight > 0 ? (int) (boundsH / cellHeight) : 1; }
    public int getVisibleCols() { return cellWidth > 0 ? (int) ((boundsW - gutterWidth() - PAD) / cellWidth) : 1; }

    // ------------------------------------------------------------------
    // Rendering — call between font.beginText / font.endText
    // ------------------------------------------------------------------

    public void render() {
        // Update blink
        double now = glfwGetTime();
        if (now - lastBlinkTime >= BLINK_RATE) {
            cursorVisible = !cursorVisible;
            lastBlinkTime = now;
        }

        int visibleRows = (int) (boundsH / cellHeight);
        int visibleCols = (int) (boundsW / cellWidth);

        // Clamp scroll to valid range (handles resize / content changes)
        scrollRow = clampScrollRow(scrollRow);
        scrollCol = clampScrollCol(scrollCol);

        // Only jump viewport to cursor on keyboard-driven changes
        if (scrollToCursor) {
            if (cursorRow < scrollRow) scrollRow = cursorRow;
            if (cursorRow >= scrollRow + visibleRows) scrollRow = cursorRow - visibleRows + 1;
            if (cursorCol < scrollCol) scrollCol = cursorCol;
            if (cursorCol >= scrollCol + visibleCols) scrollCol = cursorCol - visibleCols + 1;
            scrollToCursor = false;
        }

        float gutter = gutterWidth();
        float gutterX = boundsX + PAD;
        float textX = gutterX + gutter;
        float textY = boundsY + PAD;

        // Line numbers
        for (int i = 0; i < visibleRows && (scrollRow + i) < buffer.lineCount(); i++) {
            int row = scrollRow + i;
            String num = Integer.toString(row + 1);
            // Right-align: position so last digit ends at (gutterX + gutter - GUTTER_PAD)
            float numWidth = font.getTextWidth(num, fontScale);
            float nx = gutterX + gutter - GUTTER_PAD - numWidth;
            float ny = textY + (i + 1) * cellHeight;
            float bright = (row == cursorRow) ? 0.7f : 0.4f;
            font.drawText(num, nx, ny, fontScale, bright, bright, bright);
        }

        // Term highlights (word under cursor)
        if (!hasSelection()) {
            String term = wordAtCursor();
            if (term != null) {
                List<TermHighlighter.Match> matches = TermHighlighter.findMatches(
                        buffer, term, scrollRow, scrollRow + visibleRows);
                for (TermHighlighter.Match m : matches) {
                    // Skip the occurrence directly under the cursor
                    if (m.row() == cursorRow && m.startCol() <= cursorCol && cursorCol <= m.endCol())
                        continue;
                    int drawStart = Math.max(m.startCol() - scrollCol, 0);
                    int drawEnd = Math.min(m.endCol() - scrollCol, visibleCols);
                    if (drawEnd <= 0 || drawStart >= visibleCols) continue;
                    int vRow = m.row() - scrollRow;
                    float rx = textX + drawStart * cellWidth;
                    float ry = textY + vRow * cellHeight + HIGHLIGHT_OFFSET_Y;
                    float rw = (drawEnd - drawStart) * cellWidth;
                    font.drawRect(rx, ry, rw, cellHeight, 0.25f, 0.25f, 0.18f);
                }
            }
        }

        // Selection highlights
        if (hasSelection()) {
            int[] s = selectionBounds();
            for (int i = 0; i < visibleRows; i++) {
                int row = scrollRow + i;
                if (row < s[0] || row > s[2]) continue;

                int lineLen = buffer.lineLength(row);
                int c1 = (row == s[0]) ? s[1] : 0;
                int c2 = (row == s[2]) ? s[3] : lineLen;
                if (row != s[2]) c2 = Math.max(c2, lineLen) + 1;

                int drawStart = Math.max(c1 - scrollCol, 0);
                int drawEnd = Math.min(c2 - scrollCol, visibleCols);
                if (drawEnd <= 0 || drawStart >= visibleCols) continue;

                float rx = textX + drawStart * cellWidth;
                float ry = textY + i * cellHeight + HIGHLIGHT_OFFSET_Y;
                float rw = (drawEnd - drawStart) * cellWidth;
                font.drawRect(rx, ry, rw, cellHeight, 0.2f, 0.35f, 0.55f);
            }
        }

        // Text (syntax-highlighted)
        for (int i = 0; i < visibleRows && (scrollRow + i) < buffer.lineCount(); i++) {
            int row = scrollRow + i;
            String line = buffer.getLine(row);

            float y = textY + (i + 1) * cellHeight;
            int startCol = scrollCol;
            int endCol = Math.min(line.length(), scrollCol + visibleCols);

            if (startCol < line.length()) {
                List<Token> tokens = highlightCache.getTokens(row, line);
                ColorSpan[] spans = tokens.stream()
                        .filter(t -> t.type() != TokenType.WHITESPACE)
                        .map(t -> new ColorSpan(t.startCol(), t.endCol(),
                                t.type().r, t.type().g, t.type().b))
                        .toArray(ColorSpan[]::new);
                font.drawColoredLine(line, spans, textX, y, fontScale, startCol, endCol);
            }
        }

        // Phantom cursor
        if (mouseInBounds && !mouseDragging && phantomRow >= 0
                && (phantomRow != cursorRow || phantomCol != cursorCol)) {
            int pRow = phantomRow - scrollRow;
            int pCol = phantomCol - scrollCol;
            if (pRow >= 0 && pRow < visibleRows && pCol >= 0) {
                float px = textX + pCol * cellWidth;
                float py = textY + pRow * cellHeight;
                font.drawRect(px, py, 2f, cellHeight, 0.4f, 0.4f, 0.2f);
            }
        }

        // Cursor
        if (cursorVisible) {
            int sRow = cursorRow - scrollRow;
            int sCol = cursorCol - scrollCol;
            if (sRow >= 0 && sRow < visibleRows && sCol >= 0) {
                float cx = textX + sCol * cellWidth;
                float cy = textY + sRow * cellHeight;
                font.drawRect(cx, cy, 2f, cellHeight, 0.9f, 0.9f, 0.3f);
            }
        }
    }

    // ------------------------------------------------------------------
    // Input forwarding — called by the host window
    // ------------------------------------------------------------------

    public void onCharInput(int codepoint) {
        int rBefore = cursorRow, cBefore = cursorCol;
        String selRemoved = removeSelection();
        int editRow = cursorRow, editCol = cursorCol;

        String inserted = new String(Character.toChars(codepoint));
        for (int i = 0; i < inserted.length(); i++) {
            buffer.insertChar(cursorRow, cursorCol, inserted.charAt(i));
            cursorCol++;
        }

        if (!undoRedoInProgress) {
            undo.record(editRow, editCol, selRemoved, inserted,
                    rBefore, cBefore, cursorRow, cursorCol);
        }
        scrollToCursor = true;
        resetBlink();
    }

    public void onKey(int key, int mods) {
        boolean ctrl  = (mods & GLFW_MOD_CONTROL) != 0;
        boolean shift = (mods & GLFW_MOD_SHIFT) != 0;

        switch (key) {
            case GLFW_KEY_ENTER -> {
                int rB = cursorRow, cB = cursorCol;
                String selRem = removeSelection();
                int eRow = cursorRow, eCol = cursorCol;
                buffer.splitLine(cursorRow, cursorCol);
                cursorRow++;
                cursorCol = 0;
                if (!undoRedoInProgress)
                    undo.record(eRow, eCol, selRem, "\n", rB, cB, cursorRow, cursorCol);
            }

            case GLFW_KEY_BACKSPACE -> {
                int rB = cursorRow, cB = cursorCol;
                if (hasSelection()) {
                    String rem = removeSelection();
                    if (!undoRedoInProgress)
                        undo.record(cursorRow, cursorCol, rem, "", rB, cB, cursorRow, cursorCol);
                } else if (cursorCol > 0) {
                    String ch = String.valueOf(buffer.getLine(cursorRow).charAt(cursorCol - 1));
                    cursorCol--;
                    buffer.deleteChar(cursorRow, cursorCol);
                    if (!undoRedoInProgress)
                        undo.record(cursorRow, cursorCol, ch, "", rB, cB, cursorRow, cursorCol);
                } else if (cursorRow > 0) {
                    cursorCol = buffer.lineLength(cursorRow - 1);
                    buffer.joinWithPrevious(cursorRow);
                    cursorRow--;
                    if (!undoRedoInProgress)
                        undo.record(cursorRow, cursorCol, "\n", "", rB, cB, cursorRow, cursorCol);
                }
            }

            case GLFW_KEY_DELETE -> {
                int rB = cursorRow, cB = cursorCol;
                if (hasSelection()) {
                    String rem = removeSelection();
                    if (!undoRedoInProgress)
                        undo.record(cursorRow, cursorCol, rem, "", rB, cB, cursorRow, cursorCol);
                } else if (cursorCol < buffer.lineLength(cursorRow)) {
                    String ch = String.valueOf(buffer.getLine(cursorRow).charAt(cursorCol));
                    buffer.deleteChar(cursorRow, cursorCol);
                    if (!undoRedoInProgress)
                        undo.record(cursorRow, cursorCol, ch, "", rB, cB, cursorRow, cursorCol);
                } else if (cursorRow + 1 < buffer.lineCount()) {
                    buffer.joinWithNext(cursorRow);
                    if (!undoRedoInProgress)
                        undo.record(cursorRow, cursorCol, "\n", "", rB, cB, cursorRow, cursorCol);
                }
            }

            case GLFW_KEY_LEFT -> {
                if (!shift && hasSelection()) {
                    int[] s = selectionBounds();
                    cursorRow = s[0]; cursorCol = s[1];
                    clearSelection();
                } else {
                    if (shift) startSelection();
                    if (ctrl) cursorCol = wordBoundaryLeft();
                    else if (cursorCol > 0) cursorCol--;
                    else if (cursorRow > 0) { cursorRow--; cursorCol = buffer.lineLength(cursorRow); }
                    if (!shift) clearSelection();
                }
            }

            case GLFW_KEY_RIGHT -> {
                if (!shift && hasSelection()) {
                    int[] s = selectionBounds();
                    cursorRow = s[2]; cursorCol = s[3];
                    clearSelection();
                } else {
                    if (shift) startSelection();
                    if (ctrl) cursorCol = wordBoundaryRight();
                    else if (cursorCol < buffer.lineLength(cursorRow)) cursorCol++;
                    else if (cursorRow + 1 < buffer.lineCount()) { cursorRow++; cursorCol = 0; }
                    if (!shift) clearSelection();
                }
            }

            case GLFW_KEY_UP -> {
                if (shift) startSelection(); else clearSelection();
                if (cursorRow > 0) {
                    cursorRow--;
                    cursorCol = Math.min(cursorCol, buffer.lineLength(cursorRow));
                }
            }

            case GLFW_KEY_DOWN -> {
                if (shift) startSelection(); else clearSelection();
                if (cursorRow + 1 < buffer.lineCount()) {
                    cursorRow++;
                    cursorCol = Math.min(cursorCol, buffer.lineLength(cursorRow));
                }
            }

            case GLFW_KEY_HOME -> {
                if (shift) startSelection(); else clearSelection();
                cursorCol = 0;
            }

            case GLFW_KEY_END -> {
                if (shift) startSelection(); else clearSelection();
                cursorCol = buffer.lineLength(cursorRow);
            }

            case GLFW_KEY_PAGE_UP -> {
                if (shift) startSelection(); else clearSelection();
                cursorRow = Math.max(0, cursorRow - 20);
                cursorCol = Math.min(cursorCol, buffer.lineLength(cursorRow));
            }

            case GLFW_KEY_PAGE_DOWN -> {
                if (shift) startSelection(); else clearSelection();
                cursorRow = Math.min(buffer.lineCount() - 1, cursorRow + 20);
                cursorCol = Math.min(cursorCol, buffer.lineLength(cursorRow));
            }

            case GLFW_KEY_TAB -> {
                int rB = cursorRow, cB = cursorCol;
                String selRem = removeSelection();
                int eRow = cursorRow, eCol = cursorCol;
                for (int i = 0; i < 4; i++) {
                    buffer.insertChar(cursorRow, cursorCol, ' ');
                    cursorCol++;
                }
                if (!undoRedoInProgress)
                    undo.record(eRow, eCol, selRem, "    ", rB, cB, cursorRow, cursorCol);
            }

            case GLFW_KEY_A -> {
                if (ctrl) {
                    selAnchorRow = 0;
                    selAnchorCol = 0;
                    cursorRow = buffer.lineCount() - 1;
                    cursorCol = buffer.lineLength(cursorRow);
                }
            }

            case GLFW_KEY_C -> {
                if (ctrl && hasSelection()) clipboard.set(getSelectedText());
            }

            case GLFW_KEY_X -> {
                if (ctrl && hasSelection()) {
                    int rB = cursorRow, cB = cursorCol;
                    clipboard.set(getSelectedText());
                    String rem = removeSelection();
                    if (!undoRedoInProgress)
                        undo.record(cursorRow, cursorCol, rem, "", rB, cB, cursorRow, cursorCol);
                }
            }

            case GLFW_KEY_V -> {
                if (ctrl) {
                    String clip = clipboard.get();
                    if (clip != null && !clip.isEmpty()) {
                        int rB = cursorRow, cB = cursorCol;
                        String selRem = removeSelection();
                        int eRow = cursorRow, eCol = cursorCol;
                        int[] end = buffer.insertText(cursorRow, cursorCol, clip);
                        cursorRow = end[0];
                        cursorCol = end[1];
                        if (!undoRedoInProgress)
                            undo.record(eRow, eCol, selRem, clip, rB, cB, cursorRow, cursorCol);
                    }
                }
            }

            case GLFW_KEY_Z -> {
                if (ctrl && !shift) performUndo();
                else if (ctrl && shift) performRedo();
            }

            case GLFW_KEY_Y -> {
                if (ctrl) performRedo();
            }

            case GLFW_KEY_LEFT_BRACKET -> {
                if (ctrl) undo.switchBranch(-1);
            }

            case GLFW_KEY_RIGHT_BRACKET -> {
                if (ctrl) undo.switchBranch(1);
            }

            case GLFW_KEY_EQUAL -> {
                if (ctrl) {
                    fontScale = Math.min(FONT_SCALE_MAX, fontScale + FONT_SCALE_STEP);
                    recomputeMetrics();
                }
            }

            case GLFW_KEY_MINUS -> {
                if (ctrl) {
                    fontScale = Math.max(FONT_SCALE_MIN, fontScale - FONT_SCALE_STEP);
                    recomputeMetrics();
                }
            }

            case GLFW_KEY_0 -> {
                if (ctrl) {
                    fontScale = FONT_SCALE_DEFAULT;
                    recomputeMetrics();
                }
            }

            default -> { return; }
        }
        scrollToCursor = true;
        resetBlink();
    }

    public void onMouseButton(int button, int action, int mods, double mx, double my) {
        if (button != GLFW_MOUSE_BUTTON_LEFT) return;

        if (action == GLFW_PRESS) {
            int[] pos = screenToDocPos(mx, my);
            double now = glfwGetTime();
            boolean dblClick = (now - lastClickTime < DOUBLE_CLICK_TIME)
                    && pos[0] == lastClickRow && pos[1] == lastClickCol;
            lastClickTime = now;
            lastClickRow = pos[0];
            lastClickCol = pos[1];

            if (dblClick) {
                int[] wb = wordBoundsAt(pos[0], pos[1]);
                selAnchorRow = pos[0];
                selAnchorCol = wb[0];
                cursorRow = pos[0];
                cursorCol = wb[1];
                wordDragging = true;
                wordDragRow = pos[0];
                wordDragStartCol = wb[0];
                wordDragEndCol = wb[1];
            } else {
                wordDragging = false;
                if ((mods & GLFW_MOD_SHIFT) != 0) {
                    startSelection();
                } else {
                    clearSelection();
                    selAnchorRow = pos[0];
                    selAnchorCol = pos[1];
                }
                cursorRow = pos[0];
                cursorCol = pos[1];
            }
            mouseDragging = true;
            resetBlink();
        } else if (action == GLFW_RELEASE) {
            mouseDragging = false;
            wordDragging = false;
            if (selAnchorRow == cursorRow && selAnchorCol == cursorCol) {
                clearSelection();
            }
        }
    }

    public void onCursorPos(double mx, double my) {
        int[] pos = screenToDocPos(mx, my);
        phantomRow = pos[0];
        phantomCol = pos[1];
        if (mouseDragging) {
            if (wordDragging) {
                int[] wb = wordBoundsAt(pos[0], pos[1]);
                if (pos[0] > wordDragRow
                        || (pos[0] == wordDragRow && wb[0] >= wordDragEndCol)) {
                    selAnchorRow = wordDragRow;
                    selAnchorCol = wordDragStartCol;
                    cursorRow = pos[0];
                    cursorCol = wb[1];
                } else if (pos[0] < wordDragRow
                        || (pos[0] == wordDragRow && wb[1] <= wordDragStartCol)) {
                    selAnchorRow = wordDragRow;
                    selAnchorCol = wordDragEndCol;
                    cursorRow = pos[0];
                    cursorCol = wb[0];
                } else {
                    selAnchorRow = wordDragRow;
                    selAnchorCol = wordDragStartCol;
                    cursorRow = wordDragRow;
                    cursorCol = wordDragEndCol;
                }
            } else {
                cursorRow = pos[0];
                cursorCol = pos[1];
            }
            resetBlink();
        }
    }

    public void onCursorEnter(boolean entered) {
        mouseInBounds = entered;
        if (!entered) { phantomRow = -1; phantomCol = -1; }
    }

    public void onScroll(double xoff, double yoff) {
        scrollRow = clampScrollRow(scrollRow - (int) yoff * 3);
    }

    // ------------------------------------------------------------------
    // Undo / redo
    // ------------------------------------------------------------------

    void performUndo() {
        UndoManager.Entry e = undo.undo();
        if (e == null) return;
        undoRedoInProgress = true;
        try {
            clearSelection();
            // Reverse: remove what was inserted, re-insert what was removed
            if (!e.inserted.isEmpty()) {
                int[] end = endPositionOf(e.row, e.col, e.inserted);
                buffer.deleteRange(e.row, e.col, end[0], end[1]);
            }
            if (!e.removed.isEmpty()) {
                buffer.insertText(e.row, e.col, e.removed);
            }
            cursorRow = e.cursorRowBefore;
            cursorCol = e.cursorColBefore;
        } finally {
            undoRedoInProgress = false;
        }
    }

    void performRedo() {
        UndoManager.Entry e = undo.redo();
        if (e == null) return;
        undoRedoInProgress = true;
        try {
            clearSelection();
            // Re-apply: remove what was removed, re-insert what was inserted
            if (!e.removed.isEmpty()) {
                int[] end = endPositionOf(e.row, e.col, e.removed);
                buffer.deleteRange(e.row, e.col, end[0], end[1]);
            }
            if (!e.inserted.isEmpty()) {
                buffer.insertText(e.row, e.col, e.inserted);
            }
            cursorRow = e.cursorRowAfter;
            cursorCol = e.cursorColAfter;
        } finally {
            undoRedoInProgress = false;
        }
    }

    /** Compute the (row, col) end position of inserting `text` at (startRow, startCol). */
    private static int[] endPositionOf(int startRow, int startCol, String text) {
        int row = startRow, col = startCol;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                row++;
                col = 0;
            } else {
                col++;
            }
        }
        return new int[]{row, col};
    }

    // ------------------------------------------------------------------
    // Selection helpers
    // ------------------------------------------------------------------

    public boolean hasSelection() {
        return selAnchorRow >= 0
                && (selAnchorRow != cursorRow || selAnchorCol != cursorCol);
    }

    private int[] selectionBounds() {
        if (selAnchorRow < cursorRow
                || (selAnchorRow == cursorRow && selAnchorCol <= cursorCol)) {
            return new int[]{selAnchorRow, selAnchorCol, cursorRow, cursorCol};
        }
        return new int[]{cursorRow, cursorCol, selAnchorRow, selAnchorCol};
    }

    private void startSelection() {
        if (selAnchorRow < 0) {
            selAnchorRow = cursorRow;
            selAnchorCol = cursorCol;
        }
    }

    private void clearSelection() {
        selAnchorRow = -1;
        selAnchorCol = -1;
    }

    /**
     * Deletes the current selection and returns the removed text (empty string if none).
     * Does NOT record an undo entry — the caller is responsible for that.
     */
    private String removeSelection() {
        if (!hasSelection()) return "";
        int[] s = selectionBounds();
        String removed = buffer.getTextRange(s[0], s[1], s[2], s[3]);
        buffer.deleteRange(s[0], s[1], s[2], s[3]);
        cursorRow = s[0];
        cursorCol = s[1];
        clearSelection();
        return removed;
    }

    private void deleteSelection() {
        removeSelection();
    }

    private String getSelectedText() {
        if (!hasSelection()) return "";
        int[] s = selectionBounds();
        return buffer.getTextRange(s[0], s[1], s[2], s[3]);
    }

    // ------------------------------------------------------------------
    // Coordinate mapping
    // ------------------------------------------------------------------

    private int[] screenToDocPos(double mx, double my) {
        if (cellWidth == 0 || cellHeight == 0) return new int[]{0, 0};
        float gutter = gutterWidth();
        int row = (int) ((my - boundsY - PAD) / cellHeight) + scrollRow;
        int col = (int) Math.round((mx - boundsX - PAD - gutter) / cellWidth) + scrollCol;
        row = Math.max(0, Math.min(row, buffer.lineCount() - 1));
        col = Math.max(0, Math.min(col, buffer.lineLength(row)));
        return new int[]{row, col};
    }

    // ------------------------------------------------------------------
    // Word helpers
    // ------------------------------------------------------------------

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
    }

    private int[] wordBoundsAt(int row, int col) {
        String line = buffer.getLine(row);
        int len = line.length();

        int anchor = -1;
        if (col < len && isWordChar(line.charAt(col))) anchor = col;
        else if (col > 0 && col <= len && isWordChar(line.charAt(col - 1))) anchor = col - 1;

        if (anchor < 0) return new int[]{col, col};

        int start = anchor, end = anchor + 1;
        while (start > 0 && isWordChar(line.charAt(start - 1))) start--;
        while (end < len && isWordChar(line.charAt(end))) end++;
        return new int[]{start, end};
    }

    // ------------------------------------------------------------------
    // Grid metrics — exposed for overlay positioning (template mode)
    // ------------------------------------------------------------------

    public float getCellWidth()  { return cellWidth; }
    public float getCellHeight() { return cellHeight; }
    public float getFontScale()  { return fontScale; }
    public float getBoundsX()    { return boundsX; }
    public float getBoundsY()    { return boundsY; }
    public float getBoundsW()    { return boundsW; }
    public float getBoundsH()    { return boundsH; }
    public int   getScrollRowVal() { return scrollRow; }
    public int   getScrollColVal() { return scrollCol; }

    void zoomIn() {
        fontScale = Math.min(FONT_SCALE_MAX, fontScale + FONT_SCALE_STEP);
        recomputeMetrics();
    }

    void zoomOut() {
        fontScale = Math.max(FONT_SCALE_MIN, fontScale - FONT_SCALE_STEP);
        recomputeMetrics();
    }

    void zoomReset() {
        fontScale = FONT_SCALE_DEFAULT;
        recomputeMetrics();
    }

    public float getGutterWidth() { return gutterWidth(); }
    public float getTextOriginX() { return boundsX + PAD + gutterWidth(); }
    public float getTextOriginY() { return boundsY + PAD; }
    public float getHighlightOffsetY() { return HIGHLIGHT_OFFSET_Y; }

    /**
     * Returns the word under the cursor, or null if the cursor is not on a word.
     */
    String wordAtCursor() {
        int[] bounds = wordBoundsAt(cursorRow, cursorCol);
        if (bounds[0] == bounds[1]) return null;
        return buffer.getLine(cursorRow).substring(bounds[0], bounds[1]);
    }

    private int wordBoundaryLeft() {
        String line = buffer.getLine(cursorRow);
        int col = cursorCol;
        while (col > 0 && Character.isWhitespace(line.charAt(col - 1))) col--;
        while (col > 0 && !Character.isWhitespace(line.charAt(col - 1))) col--;
        return col;
    }

    private int wordBoundaryRight() {
        String line = buffer.getLine(cursorRow);
        int col = cursorCol;
        int len = line.length();
        while (col < len && !Character.isWhitespace(line.charAt(col))) col++;
        while (col < len && Character.isWhitespace(line.charAt(col))) col++;
        return col;
    }

    private void resetBlink() {
        cursorVisible = true;
        lastBlinkTime = glfwGetTime();
    }

    private void recomputeMetrics() {
        cellWidth  = font.getTextWidth("M", fontScale);
        cellHeight = font.getLineHeight(fontScale) * 1.2f;
    }

    /** Width of the line-number gutter in pixels, based on digit count. */
    private float gutterWidth() {
        int digits = Math.max(3, Integer.toString(buffer.lineCount()).length());
        return digits * cellWidth + GUTTER_PAD;
    }

    private int clampScrollRow(int row) {
        int max = Math.max(0, buffer.lineCount() - getVisibleRows());
        return Math.max(0, Math.min(row, max));
    }

    private int clampScrollCol(int col) {
        // +1 accounts for the cursor sitting one past the last character
        int max = Math.max(0, buffer.maxLineLength() + 1 - getVisibleCols());
        return Math.max(0, Math.min(col, max));
    }
}
