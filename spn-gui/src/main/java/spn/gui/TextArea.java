package spn.gui;

import spn.stdui.buffer.TextBuffer;
import spn.stdui.buffer.UndoManager;
import spn.stdui.render.ColorSpan;
import spn.stdui.widget.TermHighlighter;
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

    // Multi-click state: 1=single, 2=double (word), 3=triple (line)
    private static final double MULTI_CLICK_TIME = 0.4;
    private double lastClickTime;
    private int lastClickRow, lastClickCol;
    private int clickCount;
    private boolean wordDragging;
    private int wordDragRow, wordDragStartCol, wordDragEndCol;
    private boolean lineDragging;
    private int lineDragRow;

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

    // Diagnostic overlay (optional — set by EditorTab for error highlighting)
    private spn.gui.diagnostic.DiagnosticOverlay diagnosticOverlay;
    private spn.gui.diagnostic.ChangeOverlay changeOverlay;
    private Runnable onEditCallback; // notified on any edit for debounce
    private PreTextRenderer preTextRenderer; // drawn after background, before text

    /** Callback for rendering overlays between background and text. */
    public interface PreTextRenderer {
        void render(SdfFontRenderer font, float textX, float textY,
                    float cellWidth, float cellHeight, float highlightY,
                    int scrollRow, int visibleRows, float boundsX, float totalWidth);
    }

    public void setPreTextRenderer(PreTextRenderer renderer) {
        this.preTextRenderer = renderer;
    }

    public void setDiagnosticOverlay(spn.gui.diagnostic.DiagnosticOverlay overlay) {
        this.diagnosticOverlay = overlay;
    }

    public void setChangeOverlay(spn.gui.diagnostic.ChangeOverlay overlay) {
        this.changeOverlay = overlay;
    }

    public void setOnEditCallback(Runnable callback) {
        this.onEditCallback = callback;
    }

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
                case TextBuffer.ChangeListener.LINE_CHANGED  -> {
                    highlightCache.invalidateLine(row);
                    if (diagnosticOverlay != null) diagnosticOverlay.markLineStale(row);
                }
                case TextBuffer.ChangeListener.LINE_INSERTED -> highlightCache.insertLine(row);
                case TextBuffer.ChangeListener.LINE_REMOVED  -> highlightCache.removeLine(row);
                case TextBuffer.ChangeListener.BULK_CHANGE   -> highlightCache.invalidateAll();
            }
            if (onEditCallback != null) onEditCallback.run();
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

    // ── Search state ────────────────────────────────────────────────────
    // Active when searchTerm != null. Matches are substring occurrences.
    private String searchTerm;
    private final java.util.List<int[]> searchMatches = new java.util.ArrayList<>(); // [row, startCol, endCol]
    private int currentMatchIndex = -1;

    /** Activate search with the given term (substring match). Null/empty clears search. */
    public void setSearchTerm(String term) {
        this.searchTerm = (term == null || term.isEmpty()) ? null : term;
        recomputeSearchMatches();
    }

    public String getSearchTerm() { return searchTerm; }
    public int getSearchMatchCount() { return searchMatches.size(); }
    public int getCurrentSearchIndex() { return currentMatchIndex; }

    private void recomputeSearchMatches() {
        searchMatches.clear();
        currentMatchIndex = -1;
        if (searchTerm == null) return;
        for (int row = 0; row < buffer.lineCount(); row++) {
            String line = buffer.getLine(row);
            int idx = 0;
            while ((idx = line.indexOf(searchTerm, idx)) != -1) {
                searchMatches.add(new int[]{row, idx, idx + searchTerm.length()});
                idx += searchTerm.length();
            }
        }
        // Select the first match at/after the cursor, if any
        for (int i = 0; i < searchMatches.size(); i++) {
            int[] m = searchMatches.get(i);
            if (m[0] > cursorRow || (m[0] == cursorRow && m[1] >= cursorCol)) {
                currentMatchIndex = i;
                return;
            }
        }
        if (!searchMatches.isEmpty()) currentMatchIndex = 0; // wrap to first
    }

    /** Jump to next match (wraps). Returns true if there was a match to jump to. */
    public boolean jumpToNextMatch() {
        if (searchMatches.isEmpty()) return false;
        currentMatchIndex = (currentMatchIndex + 1) % searchMatches.size();
        jumpToCurrentMatch();
        return true;
    }

    /** Jump to previous match (wraps). Returns true if there was a match to jump to. */
    public boolean jumpToPrevMatch() {
        if (searchMatches.isEmpty()) return false;
        currentMatchIndex = (currentMatchIndex - 1 + searchMatches.size()) % searchMatches.size();
        jumpToCurrentMatch();
        return true;
    }

    private void jumpToCurrentMatch() {
        int[] m = searchMatches.get(currentMatchIndex);
        cursorRow = m[0];
        cursorCol = m[1];
        scrollToCursor = true;
        clearSelection();
    }

    /** Replace the current match with the given replacement and advance. Returns true on success. */
    public boolean replaceCurrentMatch(String replacement) {
        if (currentMatchIndex < 0 || currentMatchIndex >= searchMatches.size()) return false;
        int[] m = searchMatches.get(currentMatchIndex);
        buffer.deleteRange(m[0], m[1], m[0], m[2]);
        buffer.insertText(m[0], m[1], replacement);
        if (onEditCallback != null) onEditCallback.run();
        // Recompute matches (buffer changed)
        int savedIdx = currentMatchIndex;
        recomputeSearchMatches();
        // Try to stay at the same index (or wrap)
        if (!searchMatches.isEmpty()) {
            currentMatchIndex = Math.min(savedIdx, searchMatches.size() - 1);
            jumpToCurrentMatch();
        }
        return true;
    }

    /** Replace all matches with the replacement. Returns the number of replacements made. */
    public int replaceAllMatches(String replacement) {
        if (searchMatches.isEmpty()) return 0;
        // Replace from the end so earlier offsets stay valid
        int count = 0;
        for (int i = searchMatches.size() - 1; i >= 0; i--) {
            int[] m = searchMatches.get(i);
            buffer.deleteRange(m[0], m[1], m[0], m[2]);
            buffer.insertText(m[0], m[1], replacement);
            count++;
        }
        if (onEditCallback != null) onEditCallback.run();
        recomputeSearchMatches();
        return count;
    }

    public UndoManager.Info getUndoInfo() { return undo.getInfo(); }

    UndoManager getUndoManager() { return undo; }

    public int getCursorRow() { return cursorRow; }
    public int getCursorCol() { return cursorCol; }

    public void setCursorPosition(int row, int col) {
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

        // Term highlights (word under cursor) — suppressed during search
        if (!hasSelection() && searchTerm == null) {
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

        // Search highlights — all matches dim, current match bright
        if (searchTerm != null) {
            for (int i = 0; i < searchMatches.size(); i++) {
                int[] m = searchMatches.get(i);
                int vRow = m[0] - scrollRow;
                if (vRow < 0 || vRow >= visibleRows) continue;
                int drawStart = Math.max(m[1] - scrollCol, 0);
                int drawEnd = Math.min(m[2] - scrollCol, visibleCols);
                if (drawEnd <= 0 || drawStart >= visibleCols) continue;
                float rx = textX + drawStart * cellWidth;
                float ry = textY + vRow * cellHeight + HIGHLIGHT_OFFSET_Y;
                float rw = (drawEnd - drawStart) * cellWidth;
                if (i == currentMatchIndex) {
                    // Active match: bright yellow
                    font.drawRect(rx, ry, rw, cellHeight, 0.55f, 0.45f, 0.10f);
                } else {
                    // Other matches: dim amber
                    font.drawRect(rx, ry, rw, cellHeight, 0.30f, 0.25f, 0.10f);
                }
            }
        }

        // Pre-text overlays (e.g., trace block highlights)
        if (preTextRenderer != null) {
            preTextRenderer.render(font, textX, textY, cellWidth, cellHeight,
                    HIGHLIGHT_OFFSET_Y, scrollRow, visibleRows, boundsX, boundsW);
        }

        // Change indicators (faint gutter bar for modified lines)
        if (changeOverlay != null && !changeOverlay.isEmpty()) {
            changeOverlay.render(font, boundsX + PAD, textY, cellHeight,
                    HIGHLIGHT_OFFSET_Y, scrollRow, visibleRows, 3f);
        }

        // Diagnostic overlays (error underlines, stale hazes)
        if (diagnosticOverlay != null && !diagnosticOverlay.isEmpty()) {
            diagnosticOverlay.render(font, textX, textY, cellWidth, cellHeight,
                    HIGHLIGHT_OFFSET_Y, scrollRow, scrollCol, visibleRows, visibleCols,
                    r -> r < buffer.lineCount() ? buffer.lineLength(r) : 0);
        }

        // Selection highlights (highest priority — drawn last so they cover all other highlights)
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

                // Detect operator-in-declaration: pure/action followed by operator symbol
                int opDeclStart = -1, opDeclEnd = -1;
                {
                    Token prevNonWs = null;
                    for (Token t : tokens) {
                        if (t.type() == TokenType.WHITESPACE) continue;
                        if (prevNonWs != null
                                && prevNonWs.type() == TokenType.KEYWORD
                                && t.type() == TokenType.OPERATOR) {
                            String kwText = line.substring(prevNonWs.startCol(),
                                    Math.min(prevNonWs.endCol(), line.length()));
                            if (kwText.equals("pure") || kwText.equals("action")) {
                                opDeclStart = t.startCol();
                                opDeclEnd = t.endCol();
                            }
                        }
                        prevNonWs = t;
                    }
                }
                final int operatorDeclStart = opDeclStart;

                // Draw background highlight for operator declaration symbol
                if (opDeclStart >= 0) {
                    int bgStart = Math.max(opDeclStart - scrollCol, 0);
                    int bgEnd = opDeclEnd - scrollCol;
                    if (bgStart < visibleCols && bgEnd > 0) {
                        float opX = textX + bgStart * cellWidth - 2f;
                        float opW = (bgEnd - bgStart) * cellWidth + 4f;
                        float opY = textY + i * cellHeight + HIGHLIGHT_OFFSET_Y;
                        font.drawRect(opX, opY, opW, cellHeight, 0.20f, 0.16f, 0.08f);
                    }
                }

                ColorSpan[] spans = tokens.stream()
                        .filter(t -> t.type() != TokenType.WHITESPACE)
                        .map(t -> {
                            float cr = t.type().r, cg = t.type().g, cb = t.type().b;
                            // Special identifiers get keyword coloring
                            if (t.type() == TokenType.IDENTIFIER) {
                                String text = line.substring(t.startCol(), Math.min(t.endCol(), line.length()));
                                if (text.equals("this") || text.equals("true") || text.equals("false")) {
                                    cr = TokenType.KEYWORD.r; cg = TokenType.KEYWORD.g; cb = TokenType.KEYWORD.b;
                                }
                            }
                            // Operator declaration symbol: bright gold
                            if (operatorDeclStart >= 0 && t.type() == TokenType.OPERATOR
                                    && t.startCol() == operatorDeclStart) {
                                cr = 1.0f; cg = 0.82f; cb = 0.35f;
                            }
                            return new ColorSpan(t.startCol(), t.endCol(), cr, cg, cb);
                        })
                        .toArray(ColorSpan[]::new);
                font.drawColoredLine(line, spans, textX, y, fontScale, startCol, endCol);
            }
        }

        // Phantom cursor (aligned to highlight offset like selections)
        if (mouseInBounds && !mouseDragging && phantomRow >= 0
                && (phantomRow != cursorRow || phantomCol != cursorCol)) {
            int pRow = phantomRow - scrollRow;
            int pCol = phantomCol - scrollCol;
            if (pRow >= 0 && pRow < visibleRows && pCol >= 0) {
                float px = textX + pCol * cellWidth;
                float py = textY + pRow * cellHeight + HIGHLIGHT_OFFSET_Y;
                font.drawRect(px, py, 2f, cellHeight, 0.4f, 0.4f, 0.2f);
            }
        }

        // Cursor (aligned to highlight offset so it covers descenders)
        if (cursorVisible) {
            int sRow = cursorRow - scrollRow;
            int sCol = cursorCol - scrollCol;
            if (sRow >= 0 && sRow < visibleRows && sCol >= 0) {
                float cx = textX + sCol * cellWidth;
                float cy = textY + sRow * cellHeight + HIGHLIGHT_OFFSET_Y;
                font.drawRect(cx, cy, 2f, cellHeight, 0.9f, 0.9f, 0.3f);
            }
        }
    }

    // ------------------------------------------------------------------
    // Input forwarding — called by the host window
    // ------------------------------------------------------------------

    public void onCharInput(int codepoint) {
        phantomRow = -1; phantomCol = -1; // hide hover cursor while typing
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
        phantomRow = -1; phantomCol = -1; // hide hover cursor while typing
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
                for (int i = 0; i < 2; i++) {
                    buffer.insertChar(cursorRow, cursorCol, ' ');
                    cursorCol++;
                }
                if (!undoRedoInProgress)
                    undo.record(eRow, eCol, selRem, "  ", rB, cB, cursorRow, cursorCol);
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
            boolean sameSpot = (now - lastClickTime < MULTI_CLICK_TIME)
                    && pos[0] == lastClickRow && pos[1] == lastClickCol;
            lastClickTime = now;
            lastClickRow = pos[0];
            lastClickCol = pos[1];
            clickCount = sameSpot ? clickCount + 1 : 1;

            if (clickCount == 3) {
                // Triple-click: select entire line
                selAnchorRow = pos[0];
                selAnchorCol = 0;
                cursorRow = pos[0];
                cursorCol = buffer.lineLength(pos[0]);
                lineDragging = true;
                lineDragRow = pos[0];
                wordDragging = false;
            } else if (clickCount == 2) {
                // Double-click: select word
                int[] wb = wordBoundsAt(pos[0], pos[1]);
                selAnchorRow = pos[0];
                selAnchorCol = wb[0];
                cursorRow = pos[0];
                cursorCol = wb[1];
                wordDragging = true;
                wordDragRow = pos[0];
                wordDragStartCol = wb[0];
                wordDragEndCol = wb[1];
                lineDragging = false;
            } else {
                // Single click
                wordDragging = false;
                lineDragging = false;
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
            lineDragging = false;
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
            if (lineDragging) {
                // Extend selection by whole lines from the triple-click origin
                if (pos[0] >= lineDragRow) {
                    selAnchorRow = lineDragRow;
                    selAnchorCol = 0;
                    cursorRow = pos[0];
                    cursorCol = buffer.lineLength(pos[0]);
                } else {
                    selAnchorRow = lineDragRow;
                    selAnchorCol = buffer.lineLength(lineDragRow);
                    cursorRow = pos[0];
                    cursorCol = 0;
                }
            } else if (wordDragging) {
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
        int dy = ListScroll.delta(yoff);
        if (dy != 0) scrollRow = clampScrollRow(scrollRow - dy);
        int dx = ListScroll.delta(xoff);
        if (dx != 0) scrollCol = clampScrollCol(scrollCol - dx);
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

    String getSelectedText() {
        if (!hasSelection()) return "";
        int[] s = selectionBounds();
        return buffer.getTextRange(s[0], s[1], s[2], s[3]);
    }

    // ------------------------------------------------------------------
    // Coordinate mapping
    // ------------------------------------------------------------------

    /** Returns the identifier at (row, col), or null if the position isn't on
     *  a word char. Used by go-to-definition to pick the symbol under a click. */
    public String wordAt(int row, int col) {
        if (row < 0 || row >= buffer.lineCount()) return null;
        int[] bounds = wordBoundsAt(row, col);
        if (bounds[0] == bounds[1]) return null;
        return buffer.getLine(row).substring(bounds[0], bounds[1]);
    }

    /** Returns {startCol, endCol} for the identifier at (row, col), or null. */
    public int[] wordBoundsPublic(int row, int col) {
        if (row < 0 || row >= buffer.lineCount()) return null;
        int[] bounds = wordBoundsAt(row, col);
        if (bounds[0] == bounds[1]) return null;
        return bounds;
    }

    /** Places the cursor at (row, col) and extends the selection back to the
     *  given anchor. Scrolls the viewport so the cursor is visible. */
    public void selectRange(int anchorRow, int anchorCol, int cursorRow, int cursorCol) {
        int rows = buffer.lineCount();
        this.selAnchorRow = Math.max(0, Math.min(anchorRow, rows - 1));
        this.selAnchorCol = Math.max(0, Math.min(anchorCol, buffer.lineLength(this.selAnchorRow)));
        this.cursorRow = Math.max(0, Math.min(cursorRow, rows - 1));
        this.cursorCol = Math.max(0, Math.min(cursorCol, buffer.lineLength(this.cursorRow)));
        scrollToCursor = true;
    }

    public int[] screenToDocPos(double mx, double my) {
        if (cellWidth == 0 || cellHeight == 0) return new int[]{0, 0};
        float gutter = gutterWidth();
        // Account for highlight offset so clicks align with the visual text position
        int row = (int) ((my - boundsY - PAD - HIGHLIGHT_OFFSET_Y) / cellHeight) + scrollRow;
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

    private int extraScrollPadding; // extra rows of scroll beyond end of file

    /** Set extra scroll padding (in rows) to allow scrolling past end of file. */
    public void setExtraScrollPadding(int rows) {
        this.extraScrollPadding = rows;
    }

    private int clampScrollRow(int row) {
        int max = Math.max(0, buffer.lineCount() - getVisibleRows() + extraScrollPadding);
        return Math.max(0, Math.min(row, max));
    }

    private int clampScrollCol(int col) {
        // +1 accounts for the cursor sitting one past the last character
        int max = Math.max(0, buffer.maxLineLength() + 1 - getVisibleCols());
        return Math.max(0, Math.min(col, max));
    }
}
