package spn.gui;

import spn.fonts.SdfFontRenderer;

import java.io.IOException;
import java.util.List;
import java.util.regex.PatternSyntaxException;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Module browser mode (Ctrl+M). Shows module info and a searchable file list.
 * Files opened from here are pushed onto the mode stack as FileViewModes.
 */
class ModuleMode implements Mode {

    private static final float FONT_SCALE = 0.35f;
    private static final float SMALL_SCALE = 0.25f;
    private static final float PAD = 30f;
    private static final int MAX_VISIBLE_ROWS = 20;
    private static final float ROW_HEIGHT_FACTOR = 1.4f;

    private static final float BG_R = 0.10f, BG_G = 0.10f, BG_B = 0.12f;
    private static final float INPUT_BG_R = 0.16f, INPUT_BG_G = 0.16f, INPUT_BG_B = 0.20f;
    private static final float SEL_R = 0.20f, SEL_G = 0.30f, SEL_B = 0.50f;
    private static final float NAME_R = 0.85f, NAME_G = 0.85f, NAME_B = 0.85f;
    private static final float PATH_R = 0.50f, PATH_G = 0.50f, PATH_B = 0.55f;
    private static final float CURSOR_R = 0.90f, CURSOR_G = 0.90f, CURSOR_B = 0.30f;
    private static final float PROMPT_R = 0.55f, PROMPT_G = 0.55f, PROMPT_B = 0.60f;
    private static final float HEADER_R = 0.45f, HEADER_G = 0.65f, HEADER_B = 0.85f;
    private static final float VERSION_R = 0.55f, VERSION_G = 0.55f, VERSION_B = 0.60f;
    private static final float HL_R = 0.55f, HL_G = 0.45f, HL_B = 0.10f;
    private static final float HIT_R = 1.0f, HIT_G = 0.95f, HIT_B = 0.55f;
    private static final float ERR_R = 0.95f, ERR_G = 0.45f, ERR_B = 0.45f;

    private enum SearchKind { FILENAME, TEXT, REGEX }

    private final EditorWindow window;
    private final SdfFontRenderer font;
    private final ModuleContext module;

    private final StringBuilder query = new StringBuilder();
    private int cursorPos;
    private int selectedIndex;
    private int scrollOffset;
    private SearchKind kind = SearchKind.FILENAME;
    private List<ModuleContext.ModuleFile> nameResults;
    private List<ModuleContext.ContentMatch> contentResults = List.of();
    private String regexError;

    ModuleMode(EditorWindow window, ModuleContext module) {
        this.window = window;
        this.font = window.getFont();
        this.module = module;
        this.nameResults = module.getFiles();
    }

    private int resultCount() {
        return kind == SearchKind.FILENAME ? nameResults.size() : contentResults.size();
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return true;

        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

        switch (key) {
            case GLFW_KEY_ESCAPE -> { window.popMode(); return true; }
            case GLFW_KEY_ENTER -> {
                openSelected();
                return true;
            }
            case GLFW_KEY_UP -> {
                if (selectedIndex > 0) selectedIndex--;
                ensureVisible();
                return true;
            }
            case GLFW_KEY_DOWN -> {
                if (selectedIndex < resultCount() - 1) selectedIndex++;
                ensureVisible();
                return true;
            }
            case GLFW_KEY_BACKSPACE -> {
                if (cursorPos > 0) {
                    query.deleteCharAt(cursorPos - 1);
                    cursorPos--;
                    refilter();
                }
                return true;
            }
            case GLFW_KEY_DELETE -> {
                if (cursorPos < query.length()) {
                    query.deleteCharAt(cursorPos);
                    refilter();
                }
                return true;
            }
            case GLFW_KEY_LEFT -> { if (cursorPos > 0) cursorPos--; return true; }
            case GLFW_KEY_RIGHT -> { if (cursorPos < query.length()) cursorPos++; return true; }
            case GLFW_KEY_HOME -> { cursorPos = 0; return true; }
            case GLFW_KEY_END -> { cursorPos = query.length(); return true; }
        }

        // Ctrl+F cycles search kind: filename -> full-text -> regex -> filename
        if (ctrl && key == GLFW_KEY_F) {
            kind = switch (kind) {
                case FILENAME -> SearchKind.TEXT;
                case TEXT -> SearchKind.REGEX;
                case REGEX -> SearchKind.FILENAME;
            };
            refilter();
            return true;
        }

        // Ctrl+V — paste clipboard text into the query at the cursor
        if (ctrl && key == GLFW_KEY_V) {
            String clip = window.getClipboardText();
            if (!clip.isEmpty()) {
                query.insert(cursorPos, clip);
                cursorPos += clip.length();
                refilter();
            }
            return true;
        }

        // Ctrl+R refreshes module: rescan files, clear caches, force re-parse
        if (ctrl && key == GLFW_KEY_R) {
            module.rescan();
            refilter();
            window.refreshModuleCaches();
            window.flash("Module refreshed — caches cleared", false);
            return true;
        }

        return true;
    }

    @Override
    public boolean onChar(int codepoint) {
        query.insert(cursorPos, Character.toChars(codepoint));
        cursorPos++;
        refilter();
        return true;
    }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
            int clicked = rowAtY(my);
            if (clicked >= 0 && clicked < resultCount()) {
                selectedIndex = clicked;
                openSelected();
            }
        }
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        int hover = rowAtY(my);
        if (hover >= 0 && hover < resultCount()) selectedIndex = hover;
        return true;
    }

    @Override
    public boolean onScroll(double xoff, double yoff) {
        scrollOffset = Math.max(0, Math.min(scrollOffset - ListScroll.delta(yoff),
                Math.max(0, resultCount() - MAX_VISIBLE_ROWS)));
        return true;
    }

    @Override
    public void render(float width, float height) {
        font.drawRect(0, 0, width, height, BG_R, BG_G, BG_B);

        float rowHeight = font.getLineHeight(FONT_SCALE) * ROW_HEIGHT_FACTOR;
        float smallHeight = font.getLineHeight(SMALL_SCALE) * ROW_HEIGHT_FACTOR;
        float paletteWidth = Math.min(width - PAD * 2, 700f);
        float paletteX = (width - paletteWidth) / 2f;
        float y = PAD + 40f;

        // Module header
        font.drawText(module.getNamespace(), paletteX, y, FONT_SCALE,
                HEADER_R, HEADER_G, HEADER_B);
        if (!module.getVersion().isEmpty()) {
            float nsW = font.getTextWidth(module.getNamespace(), FONT_SCALE);
            font.drawText("  v" + module.getVersion(), paletteX + nsW, y, SMALL_SCALE,
                    VERSION_R, VERSION_G, VERSION_B);
        }
        y += rowHeight;

        // Root path
        font.drawText(module.getRoot().toString(), paletteX, y, SMALL_SCALE,
                PATH_R, PATH_G, PATH_B);
        y += smallHeight;

        // File count + search mode
        String modeLabel = switch (kind) {
            case FILENAME -> "Filename search";
            case TEXT -> "Full-text (wildcards: * ?)";
            case REGEX -> "Regex search";
        };
        font.drawText(module.getFiles().size() + " files | " + modeLabel + " (Ctrl+F cycle)",
                paletteX, y, SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
        y += smallHeight + 16f;

        // Search input box
        float inputH = rowHeight + 8f;
        font.drawRect(paletteX, y - rowHeight, paletteWidth, inputH,
                INPUT_BG_R, INPUT_BG_G, INPUT_BG_B);
        String queryStr = query.toString();
        float textX = paletteX + 12f;
        font.drawText(queryStr, textX, y, FONT_SCALE, NAME_R, NAME_G, NAME_B);
        float cursorX = textX + font.getTextWidth(queryStr.substring(0, cursorPos), FONT_SCALE);
        font.drawRect(cursorX, y - rowHeight + 4f, 2f, rowHeight,
                CURSOR_R, CURSOR_G, CURSOR_B);
        y += inputH + 8f;

        // Regex error (replaces results when present)
        if (regexError != null) {
            font.drawText("Regex error: " + regexError, paletteX, y + rowHeight - 4f,
                    SMALL_SCALE, ERR_R, ERR_G, ERR_B);
            listTop = y;
            listRowH = rowHeight;
            return;
        }

        // Results
        listTop = y;
        listRowH = rowHeight;
        int total = resultCount();
        int visibleCount = Math.min(MAX_VISIBLE_ROWS, total - scrollOffset);
        for (int i = 0; i < visibleCount; i++) {
            int idx = scrollOffset + i;
            float rowY = y + i * rowHeight;

            if (idx == selectedIndex) {
                font.drawRect(paletteX, rowY, paletteWidth, rowHeight,
                        SEL_R, SEL_G, SEL_B);
            }

            float itemY = rowY + rowHeight - 4f;
            if (kind == SearchKind.FILENAME) {
                ModuleContext.ModuleFile f = nameResults.get(idx);
                font.drawText(f.relativePath(), paletteX + 12f, itemY, FONT_SCALE,
                        NAME_R, NAME_G, NAME_B);
            } else {
                drawContentRow(contentResults.get(idx), paletteX + 12f, itemY,
                        paletteWidth - 24f);
            }
        }

        // Scroll indicator
        if (total > MAX_VISIBLE_ROWS) {
            String info = (scrollOffset + 1) + "-" + (scrollOffset + visibleCount)
                    + " of " + total;
            float infoWidth = font.getTextWidth(info, SMALL_SCALE);
            font.drawText(info,
                    paletteX + paletteWidth - 12f - infoWidth,
                    y + visibleCount * rowHeight + smallHeight,
                    SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
        }
    }

    /**
     * Draw one content-match row: dim {@code path:line} prefix, then the source line
     * with the matched span highlighted. Snippet is left-trimmed; long lines render
     * past the palette right edge but selection background already clips visually.
     */
    private void drawContentRow(ModuleContext.ContentMatch m, float x, float y, float maxW) {
        String prefix = m.file().relativePath() + ":" + m.lineNumber() + "  ";
        font.drawText(prefix, x, y, SMALL_SCALE, PATH_R, PATH_G, PATH_B);
        float prefixW = font.getTextWidth(prefix, SMALL_SCALE);

        // Left-trim leading whitespace, adjusting highlight offsets to match.
        String line = m.lineText();
        int trim = 0;
        while (trim < line.length() && Character.isWhitespace(line.charAt(trim))) trim++;
        int hlStart = Math.max(0, m.matchStart() - trim);
        int hlEnd = Math.max(hlStart, m.matchEnd() - trim);
        String snippet = line.substring(trim);
        if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "…";
        if (hlEnd > snippet.length()) hlEnd = snippet.length();

        // Pre-match
        String pre = snippet.substring(0, hlStart);
        font.drawText(pre, x + prefixW, y, FONT_SCALE, NAME_R, NAME_G, NAME_B);
        float preW = font.getTextWidth(pre, FONT_SCALE);

        // Match (highlight bg + bright text)
        String hit = snippet.substring(hlStart, hlEnd);
        if (!hit.isEmpty()) {
            float hitW = font.getTextWidth(hit, FONT_SCALE);
            float lh = font.getLineHeight(FONT_SCALE);
            font.drawRect(x + prefixW + preW, y - lh + 4f, hitW, lh,
                    HL_R, HL_G, HL_B);
            font.drawText(hit, x + prefixW + preW, y, FONT_SCALE, HIT_R, HIT_G, HIT_B);
        }

        // Post-match
        String post = snippet.substring(hlEnd);
        font.drawText(post, x + prefixW + preW + font.getTextWidth(hit, FONT_SCALE),
                y, FONT_SCALE, NAME_R, NAME_G, NAME_B);
    }

    private float listTop;
    private float listRowH;

    @Override
    public String hudText() {
        String mode = switch (kind) {
            case FILENAME -> "Filename";
            case TEXT -> "Text/Wildcard";
            case REGEX -> "Regex";
        };
        return "Type to search (" + mode + ") | Ctrl+F Cycle | Ctrl+R Refresh | Enter Open | Esc Close";
    }

    private void refilter() {
        regexError = null;
        String q = query.toString();
        if (kind == SearchKind.FILENAME) {
            nameResults = module.filterByName(q);
            contentResults = List.of();
        } else {
            nameResults = List.of();
            try {
                contentResults = module.searchContents(q, kind == SearchKind.REGEX);
            } catch (PatternSyntaxException e) {
                contentResults = List.of();
                regexError = e.getDescription();
            }
        }
        selectedIndex = 0;
        scrollOffset = 0;
    }

    private void ensureVisible() {
        if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
        else if (selectedIndex >= scrollOffset + MAX_VISIBLE_ROWS)
            scrollOffset = selectedIndex - MAX_VISIBLE_ROWS + 1;
    }

    private void openSelected() {
        int n = resultCount();
        if (n == 0 || selectedIndex >= n) return;
        if (kind == SearchKind.FILENAME) {
            openFile(nameResults.get(selectedIndex));
        } else {
            ModuleContext.ContentMatch m = contentResults.get(selectedIndex);
            window.popMode();
            window.openFileAtLine(m.file().absolutePath(), m.lineNumber(), m.matchStart() + 1);
        }
    }

    private void openFile(ModuleContext.ModuleFile file) {
        window.popMode();
        try {
            window.loadFile(file.absolutePath());
        } catch (IOException e) {
            window.flash("Error: " + e.getMessage(), true);
        }
    }

    private int rowAtY(double my) {
        if (listRowH <= 0) return -1;
        double rel = my - listTop;
        if (rel < 0) return -1;
        return (int) (rel / listRowH) + scrollOffset;
    }
}
