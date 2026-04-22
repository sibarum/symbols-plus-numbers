package spn.canvasgui.widget;

import org.lwjgl.glfw.GLFW;
import spn.canvas.DrawCommand;
import spn.canvasgui.cmd.GuiCommand;
import spn.canvasgui.component.Component;
import spn.canvasgui.component.Constraints;
import spn.canvasgui.component.Size;
import spn.canvasgui.input.GuiEvent;
import spn.canvasgui.theme.Theme;
import spn.canvasgui.unit.GuiContext;
import spn.fonts.SdfFontRenderer;
import spn.stdui.input.Key;
import spn.stdui.input.Mod;
import spn.type.SpnSymbol;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Unified text component. A single class covers labels, selectable text,
 * editable inputs, and multi-line text areas — behavior is toggled by the
 * {@link #setEditable}, {@link #setSelectable}, {@link #setMultiline}, and
 * {@link #setWordWrap} flags.
 *
 * <p>State model: content is bound from the caller each frame; user edits
 * fire {@code onChange} so the caller threads the new value back through
 * their stateful instance. Cursor and selection are widget-local view state.
 *
 * <p>Visual lines (after splitting on {@code \n} and optional word-wrap) are
 * cached; the cache invalidates when the buffer, width, or flags change.
 */
public class Text extends Component {

    private String text;
    private float scale;
    private float r, g, b;
    private final Theme theme;

    private boolean editable;
    private boolean selectable;
    private boolean multiline;
    private boolean wordWrap;
    private SpnSymbol fontSymbol; // null = registry default
    private boolean bold;
    private boolean italic;

    private int cursor;
    private int selAnchor = -1;
    private double lastActionTime;

    // Multi-click state: 1=single, 2=double (word), 3=triple (line).
    // Counted on PRESS within the multi-click time/slop window.
    private int clickCount;
    private double lastClickTime;
    private float lastClickX, lastClickY;
    // When dragging after a double/triple click, extend selection by whole
    // word/line units anchored to the initial word/line bounds.
    private boolean wordDragging, lineDragging;
    private int dragAnchorStart, dragAnchorEnd;

    private float lineHeightMult = 1.0f;

    private Consumer<String> onChange = s -> {};

    // Paint-time cache — read by onEvent for hit-testing and cursor math.
    private List<VisualLine> visualLines;
    private float cachedWrapWidth = -1;
    private String cachedWrappedText;
    private boolean cachedMultiline, cachedWordWrap;
    private float cachedPadX;
    private float cachedLineAdvance;

    /** One screen row of text after splitting on {@code \n} and optional wrap. */
    private record VisualLine(String content, int startOffset, int endOffset,
                              float[] charOffsetsPx, float widthPx) {}

    public Text(String text, Theme theme) {
        this.text = text == null ? "" : text;
        this.theme = theme;
        this.scale = theme.fontScale;
        this.r = theme.textR;
        this.g = theme.textG;
        this.b = theme.textB;
        this.lineHeightMult = theme.textLineHeightMult;
    }

    public String text() { return text; }

    public Text setText(String t) {
        if (t == null) t = "";
        if (t.equals(this.text)) return this;
        this.text = t;
        if (cursor > t.length()) cursor = t.length();
        if (selAnchor > t.length()) selAnchor = t.length();
        invalidate();
        return this;
    }

    public Text setEditable(boolean e) {
        if (editable != e) {
            editable = e;
            if (!e && !selectable) { cursor = 0; selAnchor = -1; }
            invalidate();
        }
        return this;
    }

    public Text setSelectable(boolean s) {
        if (selectable != s) {
            selectable = s;
            if (!s && !editable) { cursor = 0; selAnchor = -1; }
            invalidate();
        }
        return this;
    }

    public Text setMultiline(boolean m) {
        if (multiline != m) { multiline = m; invalidate(); }
        return this;
    }

    public Text setWordWrap(boolean w) {
        if (wordWrap != w) { wordWrap = w; invalidate(); }
        return this;
    }

    public Text setFontSymbol(SpnSymbol s) {
        if (fontSymbol != s) { fontSymbol = s; invalidate(); }
        return this;
    }

    public Text setBold(boolean v) {
        if (bold != v) { bold = v; invalidate(); }
        return this;
    }

    public Text setItalic(boolean v) {
        if (italic != v) { italic = v; invalidate(); }
        return this;
    }

    public Text setLineHeightMult(float m) {
        if (lineHeightMult != m) { lineHeightMult = m; invalidate(); }
        return this;
    }

    public SpnSymbol fontSymbol() { return fontSymbol; }

    private SdfFontRenderer resolveFont(GuiContext ctx) {
        if (ctx.fonts() != null) return ctx.fonts().get(fontSymbol, bold, italic);
        return ctx.font();
    }

    public Text setColor(float r, float g, float b) {
        this.r = r; this.g = g; this.b = b;
        invalidate();
        return this;
    }

    public Text setScale(float s) {
        this.scale = s;
        invalidate();
        return this;
    }

    public Text onChange(Consumer<String> cb) { this.onChange = cb; return this; }

    @Override
    public boolean focusable() { return editable || selectable; }

    // ── Measure / paint ────────────────────────────────────────────────

    @Override
    public Size measure(Constraints c, GuiContext ctx) {
        float padX = editable ? ctx.rem(theme.textEditPadXRem) : 0;
        float lineH = resolveFont(ctx).getLineHeight(scale);
        float lineAdvance = lineH * lineHeightMult;

        if (!multiline) {
            float w = resolveFont(ctx).getTextWidth(text, scale) + 2 * padX;
            return new Size(c.clampW(w), c.clampH(lineAdvance));
        }

        float wrapWidth = Math.max(0, c.maxW() - 2 * padX);
        rebuildVisualLinesIfStale(wrapWidth, ctx);
        float maxLineW = 0;
        for (VisualLine line : visualLines) {
            if (line.widthPx() > maxLineW) maxLineW = line.widthPx();
        }
        float w = maxLineW + 2 * padX;
        float h = Math.max(1, visualLines.size()) * lineAdvance;
        return new Size(c.clampW(w), c.clampH(h));
    }

    @Override
    public void paint(List<GuiCommand> out, GuiContext ctx) {
        float padX = editable ? ctx.rem(theme.textEditPadXRem) : 0;
        float lineH = resolveFont(ctx).getLineHeight(scale);
        float lineAdvance = lineH * lineHeightMult;
        // Vertical offset that centers the glyph band inside an expanded line slot.
        float textOffset = (lineAdvance - lineH) * 0.5f;
        float wrapWidth = Math.max(0, bounds().w() - 2 * padX);
        rebuildVisualLinesIfStale(wrapWidth, ctx);
        cachedPadX = padX;
        cachedLineAdvance = lineAdvance;

        if (hasSelection()) {
            int s = Math.min(cursor, selAnchor);
            int e = Math.max(cursor, selAnchor);
            for (int li = 0; li < visualLines.size(); li++) {
                VisualLine line = visualLines.get(li);
                int lineStart = line.startOffset();
                int lineEnd = line.endOffset();
                if (e <= lineStart || s >= lineEnd) continue;
                int sInLine = Math.max(0, s - lineStart);
                int eInLine = Math.min(line.content().length(), e - lineStart);
                float x0 = padX + line.charOffsetsPx()[sInLine];
                float x1 = padX + line.charOffsetsPx()[eInLine];
                float y = li * lineAdvance;
                out.add(new GuiCommand.Draw(new DrawCommand.SetFill(
                        theme.textSelectionR, theme.textSelectionG, theme.textSelectionB)));
                out.add(new GuiCommand.Draw(new DrawCommand.FillRect(x0, y, x1 - x0, lineAdvance)));
            }
        }

        SdfFontRenderer resolved = resolveFont(ctx);
        for (int li = 0; li < visualLines.size(); li++) {
            VisualLine line = visualLines.get(li);
            float baseline = li * lineAdvance + textOffset + lineH * 0.8f;
            out.add(new GuiCommand.TextRun(padX, baseline, line.content(), scale, r, g, b, resolved));
        }

        if (focused() && editable && caretVisible() && !visualLines.isEmpty()) {
            int[] pos = offsetToVisual(cursor);
            VisualLine line = visualLines.get(pos[0]);
            float cx = padX + line.charOffsetsPx()[pos[1]];
            float cy = pos[0] * lineAdvance + textOffset;
            float cw = ctx.rem(theme.textCursorWidthRem);
            out.add(new GuiCommand.Draw(new DrawCommand.SetFill(
                    theme.textCursorR, theme.textCursorG, theme.textCursorB)));
            out.add(new GuiCommand.Draw(new DrawCommand.FillRect(cx, cy, cw, lineH)));
        }

        clearDirty();
    }

    private static double now() { return System.nanoTime() * 1e-9; }

    private boolean caretVisible() {
        double elapsed = now() - lastActionTime;
        if (elapsed < theme.textBlinkRateSec * 0.5) return true;
        double phase = (elapsed - theme.textBlinkRateSec * 0.5) % theme.textBlinkRateSec;
        return phase < theme.textBlinkRateSec * 0.5;
    }

    private boolean hasSelection() {
        return selAnchor >= 0 && selAnchor != cursor;
    }

    // ── Visual-line layout ─────────────────────────────────────────────

    private void rebuildVisualLinesIfStale(float wrapWidth, GuiContext ctx) {
        if (visualLines != null
                && cachedWrapWidth == wrapWidth
                && text.equals(cachedWrappedText)
                && cachedMultiline == multiline
                && cachedWordWrap == wordWrap) return;

        visualLines = computeVisualLines(wrapWidth, ctx);
        cachedWrapWidth = wrapWidth;
        cachedWrappedText = text;
        cachedMultiline = multiline;
        cachedWordWrap = wordWrap;
    }

    private List<VisualLine> computeVisualLines(float wrapWidth, GuiContext ctx) {
        List<VisualLine> out = new ArrayList<>();
        if (!multiline) {
            out.add(makeVisualLine(text, 0, ctx));
            return out;
        }

        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            boolean atNewline = i < text.length() && text.charAt(i) == '\n';
            boolean atEnd = i == text.length();
            if (atNewline || atEnd) {
                String logical = text.substring(start, i);
                if (wordWrap && wrapWidth > 0) {
                    wrapLineInto(out, logical, start, wrapWidth, ctx);
                } else {
                    out.add(makeVisualLine(logical, start, ctx));
                }
                start = i + 1;
            }
        }
        if (out.isEmpty()) out.add(makeVisualLine("", 0, ctx));
        return out;
    }

    private VisualLine makeVisualLine(String content, int startOffset, GuiContext ctx) {
        float[] offsets = new float[content.length() + 1];
        for (int i = 0; i < content.length(); i++) {
            offsets[i + 1] = offsets[i]
                    + resolveFont(ctx).getTextWidth(content.substring(i, i + 1), scale);
        }
        return new VisualLine(content, startOffset, startOffset + content.length(),
                offsets, offsets[content.length()]);
    }

    private void wrapLineInto(List<VisualLine> out, String logical, int baseOffset,
                              float wrapWidth, GuiContext ctx) {
        if (logical.isEmpty()) {
            out.add(makeVisualLine("", baseOffset, ctx));
            return;
        }
        int start = 0;
        while (start < logical.length()) {
            int end = findWrapEnd(logical, start, wrapWidth, ctx);
            String segment = logical.substring(start, end);
            out.add(makeVisualLine(segment, baseOffset + start, ctx));
            if (end == logical.length()) break;
            start = end;
        }
    }

    private int findWrapEnd(String line, int start, float wrapWidth, GuiContext ctx) {
        float width = 0;
        int lastSpace = -1;
        for (int i = start; i < line.length(); i++) {
            float charW = resolveFont(ctx).getTextWidth(line.substring(i, i + 1), scale);
            if (width + charW > wrapWidth && i > start) {
                if (lastSpace > start) return lastSpace + 1;
                return i;
            }
            width += charW;
            if (line.charAt(i) == ' ') lastSpace = i;
        }
        return line.length();
    }

    private int[] offsetToVisual(int offset) {
        for (int li = 0; li < visualLines.size(); li++) {
            VisualLine line = visualLines.get(li);
            boolean isLast = li == visualLines.size() - 1;
            if (offset >= line.startOffset()
                    && (offset < line.endOffset()
                        || (isLast && offset == line.endOffset())
                        || offset == line.endOffset()
                           && li + 1 < visualLines.size()
                           && visualLines.get(li + 1).startOffset() > line.endOffset())) {
                return new int[]{li, offset - line.startOffset()};
            }
        }
        int last = visualLines.size() - 1;
        VisualLine line = visualLines.get(last);
        return new int[]{last, line.content().length()};
    }

    private int localToOffset(float localX, float localY) {
        if (visualLines == null || visualLines.isEmpty()) return 0;
        int li = (int) (localY / cachedLineAdvance);
        if (li < 0) li = 0;
        if (li >= visualLines.size()) li = visualLines.size() - 1;
        VisualLine line = visualLines.get(li);
        float x = localX - cachedPadX;
        float[] offsets = line.charOffsetsPx();
        for (int i = 0; i < line.content().length(); i++) {
            float mid = (offsets[i] + offsets[i + 1]) * 0.5f;
            if (x < mid) return line.startOffset() + i;
        }
        return line.startOffset() + line.content().length();
    }

    // ── Input ──────────────────────────────────────────────────────────

    @Override
    public boolean onEvent(GuiEvent e) {
        if (!editable && !selectable) return false;

        if (e instanceof GuiEvent.Pointer p) {
            switch (p.phase()) {
                case PRESS -> {
                    int idx = localToOffset(p.localX(), p.localY());
                    double t = now();
                    boolean sameSpot = (t - lastClickTime) < theme.textMultiClickTimeSec
                            && Math.abs(p.localX() - lastClickX) <= theme.textMultiClickSlopPx
                            && Math.abs(p.localY() - lastClickY) <= theme.textMultiClickSlopPx;
                    clickCount = (sameSpot && clickCount < 3) ? clickCount + 1 : 1;
                    lastClickTime = t;
                    lastClickX = p.localX();
                    lastClickY = p.localY();
                    switch (clickCount) {
                        case 2 -> {
                            int[] wb = wordBoundsAt(idx);
                            dragAnchorStart = wb[0];
                            dragAnchorEnd = wb[1];
                            selAnchor = wb[0];
                            cursor = wb[1];
                            wordDragging = true;
                            lineDragging = false;
                        }
                        case 3 -> {
                            int ls = logicalLineStart(idx);
                            int le = logicalLineEnd(idx);
                            dragAnchorStart = ls;
                            dragAnchorEnd = le;
                            selAnchor = ls;
                            cursor = le;
                            wordDragging = false;
                            lineDragging = true;
                        }
                        default -> {
                            cursor = idx;
                            selAnchor = -1;
                            wordDragging = false;
                            lineDragging = false;
                        }
                    }
                    resetBlink();
                    invalidate();
                    return true;
                }
                case MOVE -> {
                    if (pressed()) {
                        int idx = localToOffset(p.localX(), p.localY());
                        if (wordDragging) {
                            extendByWord(idx);
                        } else if (lineDragging) {
                            extendByLine(idx);
                        } else {
                            if (selAnchor < 0) selAnchor = cursor;
                            cursor = idx;
                        }
                        resetBlink();
                        invalidate();
                        return true;
                    }
                }
                case RELEASE -> {
                    wordDragging = false;
                    lineDragging = false;
                    return true;
                }
                case CLICK, ENTER, EXIT -> { return true; }
            }
        }

        if (e instanceof GuiEvent.Char c && editable && focused()) {
            insertString(new String(Character.toChars(c.codepoint())));
            return true;
        }

        if (e instanceof GuiEvent.KeyDown k && focused()) {
            int mods = k.mods();
            boolean shift = Mod.shift(mods);
            boolean ctrl = Mod.ctrl(mods);
            switch (k.key()) {
                case LEFT -> {
                    int dest = ctrl ? prevWordBoundary(cursor) : cursor - 1;
                    setCursorPos(dest, shift);
                    return true;
                }
                case RIGHT -> {
                    int dest = ctrl ? nextWordBoundary(cursor) : cursor + 1;
                    setCursorPos(dest, shift);
                    return true;
                }
                case UP -> { verticalMove(-1, shift); return true; }
                case DOWN -> { verticalMove(1, shift); return true; }
                case HOME -> { setCursorPos(lineStartOffset(cursor), shift); return true; }
                case END -> { setCursorPos(lineEndOffset(cursor), shift); return true; }
                case ENTER -> {
                    if (multiline && editable) { insertString("\n"); return true; }
                    return false;
                }
                case BACKSPACE -> {
                    if (editable) {
                        if (ctrl) backspaceWord(); else backspace();
                        return true;
                    }
                }
                case DELETE -> {
                    if (editable) {
                        if (ctrl) deleteWordForward(); else deleteForward();
                        return true;
                    }
                }
                case A -> { if (ctrl && (selectable || editable)) { selectAll(); return true; } }
                case C -> { if (ctrl) { copy(); return true; } }
                case X -> { if (ctrl && editable) { cut(); return true; } }
                case V -> { if (ctrl && editable) { paste(); return true; } }
                default -> {}
            }
        }
        return false;
    }

    // ── Editing primitives ─────────────────────────────────────────────

    private void insertString(String s) {
        if (hasSelection()) deleteSelectedRange();
        text = text.substring(0, cursor) + s + text.substring(cursor);
        cursor += s.length();
        selAnchor = -1;
        onChange.accept(text);
        resetBlink();
        invalidate();
    }

    private void backspace() {
        if (hasSelection()) { deleteSelectedRange(); return; }
        if (cursor > 0) {
            text = text.substring(0, cursor - 1) + text.substring(cursor);
            cursor -= 1;
            onChange.accept(text);
            resetBlink();
            invalidate();
        }
    }

    private void deleteForward() {
        if (hasSelection()) { deleteSelectedRange(); return; }
        if (cursor < text.length()) {
            text = text.substring(0, cursor) + text.substring(cursor + 1);
            onChange.accept(text);
            resetBlink();
            invalidate();
        }
    }

    private void backspaceWord() {
        if (hasSelection()) { deleteSelectedRange(); return; }
        if (cursor == 0) return;
        int target = prevWordBoundary(cursor);
        text = text.substring(0, target) + text.substring(cursor);
        cursor = target;
        onChange.accept(text);
        resetBlink();
        invalidate();
    }

    private void deleteWordForward() {
        if (hasSelection()) { deleteSelectedRange(); return; }
        if (cursor >= text.length()) return;
        int target = nextWordBoundary(cursor);
        text = text.substring(0, cursor) + text.substring(target);
        onChange.accept(text);
        resetBlink();
        invalidate();
    }

    private void deleteSelectedRange() {
        int s = Math.min(cursor, selAnchor);
        int e = Math.max(cursor, selAnchor);
        text = text.substring(0, s) + text.substring(e);
        cursor = s;
        selAnchor = -1;
        onChange.accept(text);
        resetBlink();
        invalidate();
    }

    private void verticalMove(int dir, boolean extend) {
        if (visualLines == null || visualLines.isEmpty()) return;
        int[] pos = offsetToVisual(cursor);
        int newLi = pos[0] + dir;
        if (newLi < 0) { setCursorPos(0, extend); return; }
        if (newLi >= visualLines.size()) { setCursorPos(text.length(), extend); return; }
        VisualLine cur = visualLines.get(pos[0]);
        VisualLine next = visualLines.get(newLi);
        float xTarget = cur.charOffsetsPx()[pos[1]];
        int col = columnAtX(next, xTarget);
        setCursorPos(next.startOffset() + col, extend);
    }

    private int columnAtX(VisualLine line, float xTarget) {
        float[] offsets = line.charOffsetsPx();
        for (int i = 0; i < line.content().length(); i++) {
            float mid = (offsets[i] + offsets[i + 1]) * 0.5f;
            if (xTarget < mid) return i;
        }
        return line.content().length();
    }

    private int lineStartOffset(int absOffset) {
        if (visualLines == null || visualLines.isEmpty()) return 0;
        for (VisualLine line : visualLines) {
            if (absOffset >= line.startOffset() && absOffset <= line.endOffset()) {
                return line.startOffset();
            }
        }
        return 0;
    }

    private int lineEndOffset(int absOffset) {
        if (visualLines == null || visualLines.isEmpty()) return text.length();
        for (VisualLine line : visualLines) {
            if (absOffset >= line.startOffset() && absOffset <= line.endOffset()) {
                return line.endOffset();
            }
        }
        return text.length();
    }

    private void setCursorPos(int pos, boolean extend) {
        pos = Math.max(0, Math.min(text.length(), pos));
        if (extend) {
            if (selAnchor < 0) selAnchor = cursor;
        } else {
            selAnchor = -1;
        }
        cursor = pos;
        resetBlink();
        invalidate();
    }

    private void selectAll() {
        selAnchor = 0;
        cursor = text.length();
        resetBlink();
        invalidate();
    }

    // ── Word / line boundaries ─────────────────────────────────────────

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Returns {start, end} of the identifier-run containing {@code offset};
     *  collapses to {offset, offset} if the position is not adjacent to one. */
    private int[] wordBoundsAt(int offset) {
        int len = text.length();
        int anchor = -1;
        if (offset < len && isWordChar(text.charAt(offset))) anchor = offset;
        else if (offset > 0 && offset <= len && isWordChar(text.charAt(offset - 1))) anchor = offset - 1;
        if (anchor < 0) return new int[]{offset, offset};
        int s = anchor, e = anchor + 1;
        while (s > 0 && isWordChar(text.charAt(s - 1))) s--;
        while (e < len && isWordChar(text.charAt(e))) e++;
        return new int[]{s, e};
    }

    /** Skip whitespace leftward, then skip non-whitespace leftward. */
    private int prevWordBoundary(int offset) {
        int o = Math.min(offset, text.length());
        while (o > 0 && Character.isWhitespace(text.charAt(o - 1))) o--;
        while (o > 0 && !Character.isWhitespace(text.charAt(o - 1))) o--;
        return o;
    }

    /** Skip non-whitespace rightward, then skip whitespace rightward. */
    private int nextWordBoundary(int offset) {
        int len = text.length();
        int o = Math.max(0, offset);
        while (o < len && !Character.isWhitespace(text.charAt(o))) o++;
        while (o < len && Character.isWhitespace(text.charAt(o))) o++;
        return o;
    }

    /** Start of the logical line (between {@code \n}s) containing {@code offset}. */
    private int logicalLineStart(int offset) {
        int o = Math.min(offset, text.length());
        while (o > 0 && text.charAt(o - 1) != '\n') o--;
        return o;
    }

    /** End of the logical line containing {@code offset}, exclusive of the trailing {@code \n}. */
    private int logicalLineEnd(int offset) {
        int len = text.length();
        int o = Math.max(0, offset);
        while (o < len && text.charAt(o) != '\n') o++;
        return o;
    }

    private void extendByWord(int pointerOffset) {
        if (pointerOffset < dragAnchorStart) {
            int[] wb = wordBoundsAt(pointerOffset);
            selAnchor = dragAnchorEnd;
            cursor = wb[0];
        } else if (pointerOffset > dragAnchorEnd) {
            int[] wb = wordBoundsAt(pointerOffset);
            selAnchor = dragAnchorStart;
            cursor = wb[1];
        } else {
            selAnchor = dragAnchorStart;
            cursor = dragAnchorEnd;
        }
    }

    private void extendByLine(int pointerOffset) {
        if (pointerOffset < dragAnchorStart) {
            selAnchor = dragAnchorEnd;
            cursor = logicalLineStart(pointerOffset);
        } else if (pointerOffset > dragAnchorEnd) {
            selAnchor = dragAnchorStart;
            cursor = logicalLineEnd(pointerOffset);
        } else {
            selAnchor = dragAnchorStart;
            cursor = dragAnchorEnd;
        }
    }

    private void resetBlink() { lastActionTime = now(); }

    // ── Clipboard ──────────────────────────────────────────────────────

    private void copy() {
        long win = GLFW.glfwGetCurrentContext();
        if (win == 0) return;
        String toCopy = hasSelection()
                ? text.substring(Math.min(cursor, selAnchor), Math.max(cursor, selAnchor))
                : text;
        GLFW.glfwSetClipboardString(win, toCopy);
    }

    private void cut() {
        if (!hasSelection()) return;
        copy();
        deleteSelectedRange();
    }

    private void paste() {
        long win = GLFW.glfwGetCurrentContext();
        if (win == 0) return;
        String clip = GLFW.glfwGetClipboardString(win);
        if (clip == null || clip.isEmpty()) return;
        clip = clip.replace("\r", "");
        if (!multiline) {
            int nl = clip.indexOf('\n');
            if (nl >= 0) clip = clip.substring(0, nl);
        }
        insertString(clip);
    }
}
