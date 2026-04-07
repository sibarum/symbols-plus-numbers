package spn.gui;

import spn.fonts.SdfFontRenderer;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

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

    private final EditorWindow window;
    private final SdfFontRenderer font;
    private final ModuleContext module;

    private final StringBuilder query = new StringBuilder();
    private int cursorPos;
    private int selectedIndex;
    private int scrollOffset;
    private List<ModuleContext.ModuleFile> filtered;
    private boolean fullTextSearch; // false = filename, true = content search

    ModuleMode(EditorWindow window, ModuleContext module) {
        this.window = window;
        this.font = window.getFont();
        this.module = module;
        this.filtered = module.getFiles();
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return true;

        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

        switch (key) {
            case GLFW_KEY_ESCAPE -> { window.popMode(); return true; }
            case GLFW_KEY_ENTER -> {
                if (!filtered.isEmpty() && selectedIndex < filtered.size()) {
                    openFile(filtered.get(selectedIndex));
                }
                return true;
            }
            case GLFW_KEY_UP -> {
                if (selectedIndex > 0) selectedIndex--;
                ensureVisible();
                return true;
            }
            case GLFW_KEY_DOWN -> {
                if (selectedIndex < filtered.size() - 1) selectedIndex++;
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

        // Ctrl+F toggles search mode
        if (ctrl && key == GLFW_KEY_F) {
            fullTextSearch = !fullTextSearch;
            refilter();
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
            if (clicked >= 0 && clicked < filtered.size()) {
                openFile(filtered.get(clicked));
            }
        }
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        int hover = rowAtY(my);
        if (hover >= 0 && hover < filtered.size()) selectedIndex = hover;
        return true;
    }

    @Override
    public boolean onScroll(double xoff, double yoff) {
        scrollOffset = Math.max(0, Math.min(scrollOffset - (int) yoff * 3,
                Math.max(0, filtered.size() - MAX_VISIBLE_ROWS)));
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
        String modeLabel = fullTextSearch ? "Full-text search" : "Filename search";
        font.drawText(module.getFiles().size() + " files | " + modeLabel + " (Ctrl+F toggle)",
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

        // Results
        listTop = y;
        listRowH = rowHeight;
        int visibleCount = Math.min(MAX_VISIBLE_ROWS, filtered.size() - scrollOffset);
        for (int i = 0; i < visibleCount; i++) {
            int idx = scrollOffset + i;
            ModuleContext.ModuleFile f = filtered.get(idx);
            float rowY = y + i * rowHeight;

            if (idx == selectedIndex) {
                font.drawRect(paletteX, rowY, paletteWidth, rowHeight,
                        SEL_R, SEL_G, SEL_B);
            }

            float itemY = rowY + rowHeight - 4f;
            font.drawText(f.relativePath(), paletteX + 12f, itemY, FONT_SCALE,
                    NAME_R, NAME_G, NAME_B);
        }

        // Scroll indicator
        if (filtered.size() > MAX_VISIBLE_ROWS) {
            String info = (scrollOffset + 1) + "-" + (scrollOffset + visibleCount)
                    + " of " + filtered.size();
            float infoWidth = font.getTextWidth(info, SMALL_SCALE);
            font.drawText(info,
                    paletteX + paletteWidth - 12f - infoWidth,
                    y + visibleCount * rowHeight + smallHeight,
                    SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
        }
    }

    private float listTop;
    private float listRowH;

    @Override
    public String hudText() {
        String mode = fullTextSearch ? "Full-text" : "Filename";
        return "Type to search (" + mode + ") | Ctrl+F Toggle | Enter Open | Esc Close";
    }

    private void refilter() {
        if (fullTextSearch) {
            filtered = module.searchContents(query.toString());
        } else {
            filtered = module.filterByName(query.toString());
        }
        selectedIndex = 0;
        scrollOffset = 0;
    }

    private void ensureVisible() {
        if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
        else if (selectedIndex >= scrollOffset + MAX_VISIBLE_ROWS)
            scrollOffset = selectedIndex - MAX_VISIBLE_ROWS + 1;
    }

    private void openFile(ModuleContext.ModuleFile file) {
        window.popMode(); // pop ModuleMode
        try {
            String content = Files.readString(file.absolutePath());
            // Push a FileViewMode (stacked editor) instead of replacing current file
            window.pushLegacyMode(new FileViewMode(window, file.absolutePath(), content));
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
