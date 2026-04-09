package spn.stdui.widget;

import spn.stdui.buffer.TextBuffer;
import spn.stdui.buffer.UndoManager;
import spn.stdui.highlight.HighlightCache;
import spn.stdui.highlight.Highlighter;
import spn.stdui.highlight.NullHighlighter;
import spn.stdui.input.InputEvent;
import spn.stdui.input.Key;
import spn.stdui.input.Mod;
import spn.stdui.render.ColorSpan;
import spn.stdui.render.Renderer;
import spn.stdui.window.Clipboard;

import java.util.List;

/**
 * Generic text editing surface: cursor, selection, scroll, undo/redo,
 * line numbers, syntax highlighting, mouse interaction.
 *
 * <p>A Surface is a reusable widget, not a Mode. Modes compose Surfaces.
 * This is the platform-neutral equivalent of the original TextArea, with:
 * <ul>
 *   <li>{@link Renderer} instead of SdfFontRenderer</li>
 *   <li>{@link InputEvent} instead of raw GLFW constants</li>
 *   <li>Pluggable {@link Highlighter} instead of hardcoded SpnLexer</li>
 * </ul>
 */
public class Surface {

    private final TextBuffer buffer;
    private final UndoManager undo = new UndoManager();
    private final HighlightCache highlightCache;

    // Bounds in screen pixels
    private float boundsX, boundsY, boundsW, boundsH;

    // Font / grid metrics (computed from renderer on first render)
    private float fontScale = 0.35f;
    private static final float FONT_SCALE_DEFAULT = 0.35f;
    private static final float FONT_SCALE_MIN = 0.10f;
    private static final float FONT_SCALE_MAX = 2.0f;
    private static final float FONT_SCALE_STEP = 0.025f;
    private float cellWidth;
    private float cellHeight;
    private static final float PAD = 10f;
    private static final float GUTTER_PAD = 8f;
    private static final float HIGHLIGHT_OFFSET_Y = 9f;

    // Configuration
    private boolean lineNumbers = true;
    private boolean readOnly = false;

    // Cursor position
    private int cursorRow;
    private int cursorCol;

    // Viewport scroll offset
    private int scrollRow;
    private int scrollCol;

    // Selection anchor (-1 = no selection)
    private int selAnchorRow = -1;
    private int selAnchorCol = -1;

    // Mouse state
    private int phantomRow = -1, phantomCol = -1;
    private boolean mouseDragging;
    private boolean mouseInBounds;

    // Double-click / word-drag
    private static final double DOUBLE_CLICK_TIME = 0.4;
    private double lastClickTime;
    private int lastClickRow, lastClickCol;
    private boolean wordDragging;
    private int wordDragRow, wordDragStartCol, wordDragEndCol;

    private boolean scrollToCursor;

    // Cursor blink
    private double lastBlinkTime;
    private boolean cursorVisible = true;
    private static final double BLINK_RATE = 0.53;

    // Clipboard
    private Clipboard clipboard;

    // Undo/redo guard
    private boolean undoRedoInProgress;

    // ---- Construction ----

    public Surface() {
        this(new TextBuffer(), NullHighlighter.INSTANCE);
    }

    public Surface(TextBuffer buffer, Highlighter highlighter) {
        this.buffer = buffer;
        this.highlightCache = new HighlightCache(highlighter);
        buffer.setChangeListener((type, row) -> {
            switch (type) {
                case TextBuffer.ChangeListener.LINE_CHANGED  -> highlightCache.invalidateLine(row);
                case TextBuffer.ChangeListener.LINE_INSERTED -> highlightCache.insertLine(row);
                case TextBuffer.ChangeListener.LINE_REMOVED  -> highlightCache.removeLine(row);
                case TextBuffer.ChangeListener.BULK_CHANGE   -> highlightCache.invalidateAll();
            }
        });
    }

    // ---- Configuration ----

    public void setHighlighter(Highlighter h) { highlightCache.setHighlighter(h); }
    public void setFontScale(float scale) { this.fontScale = scale; }
    public void setLineNumbers(boolean show) { this.lineNumbers = show; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public void setClipboard(Clipboard clipboard) { this.clipboard = clipboard; }

    public void setBounds(float x, float y, float w, float h) {
        boundsX = x; boundsY = y; boundsW = w; boundsH = h;
    }

    // ---- Accessors ----

    public TextBuffer getBuffer() { return buffer; }
    public UndoManager getUndoManager() { return undo; }
    public int getCursorRow() { return cursorRow; }
    public int getCursorCol() { return cursorCol; }

    public void setCursorPosition(int row, int col) {
        cursorRow = Math.max(0, Math.min(row, buffer.lineCount() - 1));
        cursorCol = Math.max(0, Math.min(col, buffer.lineLength(cursorRow)));
        clearSelection();
    }

    public boolean isCurrentLineBlank() { return buffer.lineLength(cursorRow) == 0; }
    public boolean hasSelection() {
        return selAnchorRow >= 0 && (selAnchorRow != cursorRow || selAnchorCol != cursorCol);
    }

    public int getScrollRow() { return scrollRow; }
    public int getScrollCol() { return scrollCol; }
    public void setScrollRow(int row) { scrollRow = clampScrollRow(row); }
    public void setScrollCol(int col) { scrollCol = clampScrollCol(col); }
    public int getContentRows() { return buffer.lineCount(); }
    public int getContentCols() { return buffer.maxLineLength() + 1; }
    public int getVisibleRows() { return cellHeight > 0 ? (int) (boundsH / cellHeight) : 1; }
    public int getVisibleCols() {
        float gutter = lineNumbers ? gutterWidth() : 0;
        return cellWidth > 0 ? (int) ((boundsW - gutter - PAD) / cellWidth) : 1;
    }

    public UndoManager.Info getUndoInfo() { return undo.getInfo(); }

    public String getText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < buffer.lineCount(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(buffer.getLine(i));
        }
        return sb.toString();
    }

    public void setText(String text) {
        while (buffer.lineCount() > 1)
            buffer.deleteRange(0, 0, buffer.lineCount() - 1, buffer.lineLength(buffer.lineCount() - 1));
        if (buffer.lineLength(0) > 0) buffer.deleteRange(0, 0, 0, buffer.lineLength(0));
        if (text != null && !text.isEmpty()) buffer.insertText(0, 0, text);
        cursorRow = 0; cursorCol = 0; scrollRow = 0; scrollCol = 0;
        clearSelection();
        highlightCache.invalidateAll();
        undo.clear();
    }

    public void insertSnippet(String snippet) {
        if (readOnly) return;
        int rB = cursorRow, cB = cursorCol;
        String selRem = removeSelection();
        int eRow = cursorRow, eCol = cursorCol;
        int[] end = buffer.insertText(cursorRow, cursorCol, snippet);
        cursorRow = end[0]; cursorCol = end[1];
        undo.record(eRow, eCol, selRem, snippet, rB, cB, cursorRow, cursorCol);
        scrollToCursor = true;
        resetBlink();
    }

    public void switchUndoBranch(int direction) { undo.switchBranch(direction); }

    // ---- Word at cursor ----

    public String wordAtCursor() {
        int[] bounds = wordBoundsAt(cursorRow, cursorCol);
        if (bounds[0] == bounds[1]) return null;
        return buffer.getLine(cursorRow).substring(bounds[0], bounds[1]);
    }

    // ---- Grid metrics (for overlay positioning) ----

    public float getCellWidth()  { return cellWidth; }
    public float getCellHeight() { return cellHeight; }
    public float getFontScale()  { return fontScale; }
    public float getBoundsX()    { return boundsX; }
    public float getBoundsY()    { return boundsY; }
    public float getTextOriginX() { return boundsX + PAD + (lineNumbers ? gutterWidth() : 0); }
    public float getTextOriginY() { return boundsY + PAD; }
    public float getHighlightOffsetY() { return HIGHLIGHT_OFFSET_Y; }
    public int getScrollRowVal() { return scrollRow; }
    public int getScrollColVal() { return scrollCol; }

    // ---- Input handling ----

    public boolean onInput(InputEvent event) {
        return switch (event) {
            case InputEvent.KeyPress kp   -> onKeyPress(kp.key(), kp.mods());
            case InputEvent.KeyRepeat kr  -> onKeyPress(kr.key(), kr.mods());
            case InputEvent.CharInput ci  -> onCharInput(ci.codepoint());
            case InputEvent.MousePress mp -> onMousePress(mp.button(), mp.mods(), mp.x(), mp.y());
            case InputEvent.MouseRelease mr -> onMouseRelease(mr.button());
            case InputEvent.MouseMove mm  -> { onMouseMove(mm.x(), mm.y()); yield mouseDragging; }
            case InputEvent.MouseScroll ms -> { onScroll(ms.yOff()); yield true; }
            case InputEvent.MouseEnter me -> { onCursorEnter(me.entered()); yield true; }
            default -> false;
        };
    }

    private boolean onKeyPress(Key key, int mods) {
        phantomRow = -1; phantomCol = -1; // hide hover cursor while typing
        boolean ctrl = Mod.ctrl(mods);
        boolean shift = Mod.shift(mods);

        switch (key) {
            case ENTER -> {
                if (readOnly) return true;
                int rB = cursorRow, cB = cursorCol;
                String selRem = removeSelection();
                int eRow = cursorRow, eCol = cursorCol;
                buffer.splitLine(cursorRow, cursorCol);
                cursorRow++; cursorCol = 0;
                if (!undoRedoInProgress)
                    undo.record(eRow, eCol, selRem, "\n", rB, cB, cursorRow, cursorCol);
            }
            case BACKSPACE -> {
                if (readOnly) return true;
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
            case DELETE -> {
                if (readOnly) return true;
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
            case LEFT -> {
                if (!shift && hasSelection()) {
                    int[] s = selectionBounds(); cursorRow = s[0]; cursorCol = s[1]; clearSelection();
                } else {
                    if (shift) startSelection();
                    if (ctrl) cursorCol = wordBoundaryLeft();
                    else if (cursorCol > 0) cursorCol--;
                    else if (cursorRow > 0) { cursorRow--; cursorCol = buffer.lineLength(cursorRow); }
                    if (!shift) clearSelection();
                }
            }
            case RIGHT -> {
                if (!shift && hasSelection()) {
                    int[] s = selectionBounds(); cursorRow = s[2]; cursorCol = s[3]; clearSelection();
                } else {
                    if (shift) startSelection();
                    if (ctrl) cursorCol = wordBoundaryRight();
                    else if (cursorCol < buffer.lineLength(cursorRow)) cursorCol++;
                    else if (cursorRow + 1 < buffer.lineCount()) { cursorRow++; cursorCol = 0; }
                    if (!shift) clearSelection();
                }
            }
            case UP -> {
                if (shift) startSelection(); else clearSelection();
                if (cursorRow > 0) { cursorRow--; cursorCol = Math.min(cursorCol, buffer.lineLength(cursorRow)); }
            }
            case DOWN -> {
                if (shift) startSelection(); else clearSelection();
                if (cursorRow + 1 < buffer.lineCount()) { cursorRow++; cursorCol = Math.min(cursorCol, buffer.lineLength(cursorRow)); }
            }
            case HOME -> { if (shift) startSelection(); else clearSelection(); cursorCol = 0; }
            case END -> { if (shift) startSelection(); else clearSelection(); cursorCol = buffer.lineLength(cursorRow); }
            case PAGE_UP -> {
                if (shift) startSelection(); else clearSelection();
                cursorRow = Math.max(0, cursorRow - 20);
                cursorCol = Math.min(cursorCol, buffer.lineLength(cursorRow));
            }
            case PAGE_DOWN -> {
                if (shift) startSelection(); else clearSelection();
                cursorRow = Math.min(buffer.lineCount() - 1, cursorRow + 20);
                cursorCol = Math.min(cursorCol, buffer.lineLength(cursorRow));
            }
            case TAB -> {
                if (readOnly) return true;
                int rB = cursorRow, cB = cursorCol;
                String selRem = removeSelection();
                int eRow = cursorRow, eCol = cursorCol;
                for (int i = 0; i < 4; i++) { buffer.insertChar(cursorRow, cursorCol, ' '); cursorCol++; }
                if (!undoRedoInProgress)
                    undo.record(eRow, eCol, selRem, "    ", rB, cB, cursorRow, cursorCol);
            }
            case A -> { if (ctrl) { selAnchorRow = 0; selAnchorCol = 0; cursorRow = buffer.lineCount() - 1; cursorCol = buffer.lineLength(cursorRow); } else return false; }
            case C -> { if (ctrl && hasSelection() && clipboard != null) clipboard.set(getSelectedText()); else return false; }
            case X -> {
                if (ctrl && hasSelection() && clipboard != null) {
                    if (readOnly) return true;
                    int rB = cursorRow, cB = cursorCol;
                    clipboard.set(getSelectedText());
                    String rem = removeSelection();
                    if (!undoRedoInProgress)
                        undo.record(cursorRow, cursorCol, rem, "", rB, cB, cursorRow, cursorCol);
                } else return false;
            }
            case V -> {
                if (ctrl && clipboard != null) {
                    if (readOnly) return true;
                    String clip = clipboard.get();
                    if (clip != null && !clip.isEmpty()) {
                        int rB = cursorRow, cB = cursorCol;
                        String selRem = removeSelection();
                        int eRow = cursorRow, eCol = cursorCol;
                        int[] end = buffer.insertText(cursorRow, cursorCol, clip);
                        cursorRow = end[0]; cursorCol = end[1];
                        if (!undoRedoInProgress)
                            undo.record(eRow, eCol, selRem, clip, rB, cB, cursorRow, cursorCol);
                    }
                } else return false;
            }
            case Z -> {
                if (ctrl && !shift) performUndo();
                else if (ctrl && shift) performRedo();
                else return false;
            }
            case Y -> { if (ctrl) performRedo(); else return false; }
            case LEFT_BRACKET  -> { if (ctrl) undo.switchBranch(-1); else return false; }
            case RIGHT_BRACKET -> { if (ctrl) undo.switchBranch(1);  else return false; }
            case EQUAL -> { if (ctrl) { fontScale = Math.min(FONT_SCALE_MAX, fontScale + FONT_SCALE_STEP); } else return false; }
            case MINUS -> { if (ctrl) { fontScale = Math.max(FONT_SCALE_MIN, fontScale - FONT_SCALE_STEP); } else return false; }
            case NUM_0 -> { if (ctrl) { fontScale = FONT_SCALE_DEFAULT; } else return false; }
            default -> { return false; }
        }
        scrollToCursor = true;
        resetBlink();
        return true;
    }

    private boolean onCharInput(int codepoint) {
        phantomRow = -1; phantomCol = -1;
        if (readOnly) return true;
        int rBefore = cursorRow, cBefore = cursorCol;
        String selRemoved = removeSelection();
        int editRow = cursorRow, editCol = cursorCol;
        String inserted = new String(Character.toChars(codepoint));
        for (int i = 0; i < inserted.length(); i++) {
            buffer.insertChar(cursorRow, cursorCol, inserted.charAt(i));
            cursorCol++;
        }
        if (!undoRedoInProgress)
            undo.record(editRow, editCol, selRemoved, inserted, rBefore, cBefore, cursorRow, cursorCol);
        scrollToCursor = true;
        resetBlink();
        return true;
    }

    // ---- Mouse ----

    private boolean onMousePress(int button, int mods, double mx, double my) {
        if (button != 0) return false;
        int[] pos = screenToDocPos(mx, my);
        double now = System.nanoTime() / 1e9;
        boolean dblClick = (now - lastClickTime < DOUBLE_CLICK_TIME)
                && pos[0] == lastClickRow && pos[1] == lastClickCol;
        lastClickTime = now; lastClickRow = pos[0]; lastClickCol = pos[1];
        if (dblClick) {
            int[] wb = wordBoundsAt(pos[0], pos[1]);
            selAnchorRow = pos[0]; selAnchorCol = wb[0];
            cursorRow = pos[0]; cursorCol = wb[1];
            wordDragging = true;
            wordDragRow = pos[0]; wordDragStartCol = wb[0]; wordDragEndCol = wb[1];
        } else {
            wordDragging = false;
            if (Mod.shift(mods)) startSelection();
            else { clearSelection(); selAnchorRow = pos[0]; selAnchorCol = pos[1]; }
            cursorRow = pos[0]; cursorCol = pos[1];
        }
        mouseDragging = true;
        resetBlink();
        return true;
    }

    private boolean onMouseRelease(int button) {
        if (button != 0) return false;
        mouseDragging = false; wordDragging = false;
        if (selAnchorRow == cursorRow && selAnchorCol == cursorCol) clearSelection();
        return true;
    }

    private void onMouseMove(double mx, double my) {
        int[] pos = screenToDocPos(mx, my);
        phantomRow = pos[0]; phantomCol = pos[1];
        if (mouseDragging) {
            if (wordDragging) {
                int[] wb = wordBoundsAt(pos[0], pos[1]);
                if (pos[0] > wordDragRow || (pos[0] == wordDragRow && wb[0] >= wordDragEndCol)) {
                    selAnchorRow = wordDragRow; selAnchorCol = wordDragStartCol; cursorRow = pos[0]; cursorCol = wb[1];
                } else if (pos[0] < wordDragRow || (pos[0] == wordDragRow && wb[1] <= wordDragStartCol)) {
                    selAnchorRow = wordDragRow; selAnchorCol = wordDragEndCol; cursorRow = pos[0]; cursorCol = wb[0];
                } else {
                    selAnchorRow = wordDragRow; selAnchorCol = wordDragStartCol; cursorRow = wordDragRow; cursorCol = wordDragEndCol;
                }
            } else {
                cursorRow = pos[0]; cursorCol = pos[1];
            }
            resetBlink();
        }
    }

    private void onScroll(double yoff) {
        if (yoff == 0) return;
        int delta = (int) Math.round(yoff * 4);
        if (delta == 0) delta = (yoff > 0) ? 1 : -1;
        scrollRow = clampScrollRow(scrollRow - delta);
    }

    private void onCursorEnter(boolean entered) {
        mouseInBounds = entered;
        if (!entered) { phantomRow = -1; phantomCol = -1; }
    }

    // ---- Rendering ----

    public void render(Renderer renderer, double now) {
        recomputeMetrics(renderer);

        // Blink
        if (now - lastBlinkTime >= BLINK_RATE) { cursorVisible = !cursorVisible; lastBlinkTime = now; }

        int visibleRows = (int) (boundsH / cellHeight);
        int visibleCols = (int) (boundsW / cellWidth);

        scrollRow = clampScrollRow(scrollRow);
        scrollCol = clampScrollCol(scrollCol);

        if (scrollToCursor) {
            if (cursorRow < scrollRow) scrollRow = cursorRow;
            if (cursorRow >= scrollRow + visibleRows) scrollRow = cursorRow - visibleRows + 1;
            if (cursorCol < scrollCol) scrollCol = cursorCol;
            if (cursorCol >= scrollCol + visibleCols) scrollCol = cursorCol - visibleCols + 1;
            scrollToCursor = false;
        }

        float gutter = lineNumbers ? gutterWidth() : 0;
        float gutterX = boundsX + PAD;
        float textX = gutterX + gutter;
        float textY = boundsY + PAD;

        // Line numbers
        if (lineNumbers) {
            for (int i = 0; i < visibleRows && (scrollRow + i) < buffer.lineCount(); i++) {
                int row = scrollRow + i;
                String num = Integer.toString(row + 1);
                float numWidth = renderer.getTextWidth(num, fontScale);
                float nx = gutterX + gutter - GUTTER_PAD - numWidth;
                float ny = textY + (i + 1) * cellHeight;
                float bright = (row == cursorRow) ? 0.7f : 0.4f;
                renderer.drawText(num, nx, ny, fontScale, bright, bright, bright);
            }
        }

        // Term highlights
        if (!hasSelection()) {
            String term = wordAtCursor();
            if (term != null) {
                List<TermHighlighter.Match> matches = TermHighlighter.findMatches(
                        buffer, term, scrollRow, scrollRow + visibleRows);
                for (TermHighlighter.Match m : matches) {
                    if (m.row() == cursorRow && m.startCol() <= cursorCol && cursorCol <= m.endCol()) continue;
                    int drawStart = Math.max(m.startCol() - scrollCol, 0);
                    int drawEnd = Math.min(m.endCol() - scrollCol, visibleCols);
                    if (drawEnd <= 0 || drawStart >= visibleCols) continue;
                    int vRow = m.row() - scrollRow;
                    float rx = textX + drawStart * cellWidth;
                    float ry = textY + vRow * cellHeight + HIGHLIGHT_OFFSET_Y;
                    float rw = (drawEnd - drawStart) * cellWidth;
                    renderer.drawRect(rx, ry, rw, cellHeight, 0.25f, 0.25f, 0.18f);
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
                renderer.drawRect(rx, ry, rw, cellHeight, 0.2f, 0.35f, 0.55f);
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
                ColorSpan[] spans = highlightCache.getSpans(row, line);
                renderer.drawColoredLine(line, spans, textX, y, fontScale, startCol, endCol);
            }
        }

        // Phantom cursor (aligned to highlight offset like selections)
        if (mouseInBounds && !mouseDragging && phantomRow >= 0
                && (phantomRow != cursorRow || phantomCol != cursorCol)) {
            int pRow = phantomRow - scrollRow, pCol = phantomCol - scrollCol;
            if (pRow >= 0 && pRow < visibleRows && pCol >= 0) {
                float px = textX + pCol * cellWidth;
                float py = textY + pRow * cellHeight + HIGHLIGHT_OFFSET_Y;
                renderer.drawRect(px, py, 2f, cellHeight, 0.4f, 0.4f, 0.2f);
            }
        }

        // Cursor (aligned to highlight offset so it covers descenders)
        if (cursorVisible) {
            int sRow = cursorRow - scrollRow, sCol = cursorCol - scrollCol;
            if (sRow >= 0 && sRow < visibleRows && sCol >= 0) {
                float cx = textX + sCol * cellWidth;
                float cy = textY + sRow * cellHeight + HIGHLIGHT_OFFSET_Y;
                renderer.drawRect(cx, cy, 2f, cellHeight, 0.9f, 0.9f, 0.3f);
            }
        }
    }

    // ---- Undo/Redo ----

    public void performUndo() {
        UndoManager.Entry e = undo.undo();
        if (e == null) return;
        undoRedoInProgress = true;
        try {
            clearSelection();
            if (!e.inserted.isEmpty()) {
                int[] end = endPositionOf(e.row, e.col, e.inserted);
                buffer.deleteRange(e.row, e.col, end[0], end[1]);
            }
            if (!e.removed.isEmpty()) buffer.insertText(e.row, e.col, e.removed);
            cursorRow = e.cursorRowBefore; cursorCol = e.cursorColBefore;
        } finally { undoRedoInProgress = false; }
    }

    public void performRedo() {
        UndoManager.Entry e = undo.redo();
        if (e == null) return;
        undoRedoInProgress = true;
        try {
            clearSelection();
            if (!e.removed.isEmpty()) {
                int[] end = endPositionOf(e.row, e.col, e.removed);
                buffer.deleteRange(e.row, e.col, end[0], end[1]);
            }
            if (!e.inserted.isEmpty()) buffer.insertText(e.row, e.col, e.inserted);
            cursorRow = e.cursorRowAfter; cursorCol = e.cursorColAfter;
        } finally { undoRedoInProgress = false; }
    }

    public void zoomIn()    { fontScale = Math.min(FONT_SCALE_MAX, fontScale + FONT_SCALE_STEP); }
    public void zoomOut()   { fontScale = Math.max(FONT_SCALE_MIN, fontScale - FONT_SCALE_STEP); }
    public void zoomReset() { fontScale = FONT_SCALE_DEFAULT; }

    // ---- Private helpers ----

    private static int[] endPositionOf(int startRow, int startCol, String text) {
        int row = startRow, col = startCol;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') { row++; col = 0; } else col++;
        }
        return new int[]{row, col};
    }

    private int[] selectionBounds() {
        if (selAnchorRow < cursorRow || (selAnchorRow == cursorRow && selAnchorCol <= cursorCol))
            return new int[]{selAnchorRow, selAnchorCol, cursorRow, cursorCol};
        return new int[]{cursorRow, cursorCol, selAnchorRow, selAnchorCol};
    }

    private void startSelection() {
        if (selAnchorRow < 0) { selAnchorRow = cursorRow; selAnchorCol = cursorCol; }
    }

    private void clearSelection() { selAnchorRow = -1; selAnchorCol = -1; }

    private String removeSelection() {
        if (!hasSelection()) return "";
        int[] s = selectionBounds();
        String removed = buffer.getTextRange(s[0], s[1], s[2], s[3]);
        buffer.deleteRange(s[0], s[1], s[2], s[3]);
        cursorRow = s[0]; cursorCol = s[1]; clearSelection();
        return removed;
    }

    private String getSelectedText() {
        if (!hasSelection()) return "";
        int[] s = selectionBounds();
        return buffer.getTextRange(s[0], s[1], s[2], s[3]);
    }

    private int[] screenToDocPos(double mx, double my) {
        if (cellWidth == 0 || cellHeight == 0) return new int[]{0, 0};
        float gutter = lineNumbers ? gutterWidth() : 0;
        int row = (int) ((my - boundsY - PAD - HIGHLIGHT_OFFSET_Y) / cellHeight) + scrollRow;
        int col = (int) Math.round((mx - boundsX - PAD - gutter) / cellWidth) + scrollCol;
        row = Math.max(0, Math.min(row, buffer.lineCount() - 1));
        col = Math.max(0, Math.min(col, buffer.lineLength(row)));
        return new int[]{row, col};
    }

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

    private int wordBoundaryLeft() {
        String line = buffer.getLine(cursorRow);
        int col = cursorCol;
        while (col > 0 && Character.isWhitespace(line.charAt(col - 1))) col--;
        while (col > 0 && !Character.isWhitespace(line.charAt(col - 1))) col--;
        return col;
    }

    private int wordBoundaryRight() {
        String line = buffer.getLine(cursorRow);
        int col = cursorCol, len = line.length();
        while (col < len && !Character.isWhitespace(line.charAt(col))) col++;
        while (col < len && Character.isWhitespace(line.charAt(col))) col++;
        return col;
    }

    private void resetBlink() { cursorVisible = true; lastBlinkTime = System.nanoTime() / 1e9; }

    private void recomputeMetrics(Renderer renderer) {
        cellWidth = renderer.getTextWidth("M", fontScale);
        cellHeight = renderer.getLineHeight(fontScale) * 1.2f;
    }

    private float gutterWidth() {
        int digits = Math.max(3, Integer.toString(buffer.lineCount()).length());
        return digits * cellWidth + GUTTER_PAD;
    }

    private int clampScrollRow(int row) {
        int max = Math.max(0, buffer.lineCount() - getVisibleRows());
        return Math.max(0, Math.min(row, max));
    }

    private int clampScrollCol(int col) {
        int max = Math.max(0, buffer.maxLineLength() + 1 - getVisibleCols());
        return Math.max(0, Math.min(col, max));
    }
}
