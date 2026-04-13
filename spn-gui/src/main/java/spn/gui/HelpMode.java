package spn.gui;

import spn.fonts.SdfFontRenderer;
import spn.stdui.action.Action;
import spn.stdui.action.ActionRegistry;

import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Help search mode (Ctrl+/). Searches two indexes:
 * 1) IDE commands from the ActionRegistry
 * 2) (Future) API manual pages for functions, syntax, types
 *
 * Results show shortcut + name. Selecting one opens a detail page.
 * Query matches against shortcut sequence and description.
 */
class HelpMode implements Mode {

    private static final float FONT_SCALE = 0.35f;
    private static final float SMALL_SCALE = 0.25f;
    private static final float DETAIL_SCALE = 0.30f;
    private static final float PAD = 30f;
    private static final float ROW_HEIGHT_FACTOR = 1.4f;
    private static final int MAX_VISIBLE_ROWS = 20;

    // Colors
    private static final float BG_R = 0.10f, BG_G = 0.10f, BG_B = 0.12f;
    private static final float INPUT_BG_R = 0.16f, INPUT_BG_G = 0.16f, INPUT_BG_B = 0.20f;
    private static final float SEL_R = 0.20f, SEL_G = 0.30f, SEL_B = 0.50f;
    private static final float NAME_R = 0.85f, NAME_G = 0.85f, NAME_B = 0.85f;
    private static final float KEY_R = 0.65f, KEY_G = 0.55f, KEY_B = 0.80f;
    private static final float CAT_R = 0.50f, CAT_G = 0.50f, CAT_B = 0.55f;
    private static final float DESC_R = 0.70f, DESC_G = 0.70f, DESC_B = 0.72f;
    private static final float CURSOR_R = 0.90f, CURSOR_G = 0.90f, CURSOR_B = 0.30f;
    private static final float PROMPT_R = 0.55f, PROMPT_G = 0.55f, PROMPT_B = 0.60f;
    private static final float HEADING_R = 0.45f, HEADING_G = 0.60f, HEADING_B = 0.85f;

    private final EditorWindow window;
    private final SdfFontRenderer font;
    private final ActionRegistry registry;

    private final StringBuilder query = new StringBuilder();
    private int cursorPos;
    private int selectedIndex;
    private int scrollOffset;
    private List<Action> filtered;

    // Detail view — null means we're in search view
    private Action detailAction;

    HelpMode(EditorWindow window, ActionRegistry registry) {
        this.window = window;
        this.font = window.getFont();
        this.registry = registry;
        this.filtered = registry.all();
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return true;

        // Detail view: Escape goes back to search
        if (detailAction != null) {
            if (key == GLFW_KEY_ESCAPE || key == GLFW_KEY_BACKSPACE) {
                detailAction = null;
                return true;
            }
            return true;
        }

        // Search view
        switch (key) {
            case GLFW_KEY_ESCAPE -> {
                window.popMode();
                return true;
            }
            case GLFW_KEY_ENTER -> {
                if (!filtered.isEmpty() && selectedIndex < filtered.size()) {
                    detailAction = filtered.get(selectedIndex);
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
        return true;
    }

    @Override
    public boolean onChar(int codepoint) {
        if (detailAction != null) return true;
        query.insert(cursorPos, Character.toChars(codepoint));
        cursorPos++;
        refilter();
        return true;
    }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        if (detailAction != null) {
            if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
                detailAction = null; // click anywhere goes back
            }
            return true;
        }
        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
            int clickedIndex = rowAtY(my);
            if (clickedIndex >= 0 && clickedIndex < filtered.size()) {
                detailAction = filtered.get(clickedIndex);
            }
        }
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        if (detailAction != null) return true;
        int hoverIndex = rowAtY(my);
        if (hoverIndex >= 0 && hoverIndex < filtered.size()) {
            selectedIndex = hoverIndex;
        }
        return true;
    }

    @Override
    public boolean onScroll(double xoff, double yoff) {
        scrollOffset = Math.max(0, Math.min(scrollOffset - ListScroll.delta(yoff),
                Math.max(0, filtered.size() - MAX_VISIBLE_ROWS)));
        return true;
    }

    @Override
    public void render(float width, float height) {
        font.drawRect(0, 0, width, height, BG_R, BG_G, BG_B);

        if (detailAction != null) {
            renderDetail(width, height);
        } else {
            renderSearch(width, height);
        }
    }

    private void renderSearch(float width, float height) {
        float rowHeight = font.getLineHeight(FONT_SCALE) * ROW_HEIGHT_FACTOR;
        float smallHeight = font.getLineHeight(SMALL_SCALE) * ROW_HEIGHT_FACTOR;

        float paletteWidth = Math.min(width - PAD * 2, 700f);
        float paletteX = (width - paletteWidth) / 2f;
        float y = PAD + 40f;

        // Title
        font.drawText("> Help", paletteX, y, SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
        y += smallHeight + 8f;

        // Search input
        float inputH = rowHeight + 8f;
        font.drawRect(paletteX, y - rowHeight, paletteWidth, inputH,
                INPUT_BG_R, INPUT_BG_G, INPUT_BG_B);

        String queryStr = query.toString();
        float textX = paletteX + 12f;
        font.drawText(queryStr, textX, y, FONT_SCALE, NAME_R, NAME_G, NAME_B);

        float cursorX = textX + font.getTextWidth(queryStr.substring(0, cursorPos), FONT_SCALE);
        font.drawRect(cursorX, y - rowHeight + 4f, 2f, rowHeight, CURSOR_R, CURSOR_G, CURSOR_B);

        y += inputH + 8f;

        // Results
        int visibleCount = Math.min(MAX_VISIBLE_ROWS, filtered.size() - scrollOffset);
        for (int i = 0; i < visibleCount; i++) {
            int idx = scrollOffset + i;
            Action a = filtered.get(idx);
            float rowY = y + i * rowHeight;

            if (idx == selectedIndex) {
                font.drawRect(paletteX, rowY, paletteWidth, rowHeight, SEL_R, SEL_G, SEL_B);
            }

            float itemY = rowY + rowHeight - 4f;

            // Shortcut (left, accent color)
            String shortcut = a.shortcut();
            if (!shortcut.isEmpty()) {
                font.drawText(shortcut, paletteX + 12f, itemY, SMALL_SCALE, KEY_R, KEY_G, KEY_B);
                float shortcutW = font.getTextWidth(shortcut, SMALL_SCALE);
                // Name (after shortcut)
                font.drawText(a.name(), paletteX + 12f + shortcutW + 12f, itemY, FONT_SCALE,
                        NAME_R, NAME_G, NAME_B);
            } else {
                font.drawText(a.name(), paletteX + 12f, itemY, FONT_SCALE, NAME_R, NAME_G, NAME_B);
            }

            // Category tag (right-aligned, dim)
            float catW = font.getTextWidth(a.category(), SMALL_SCALE);
            font.drawText(a.category(), paletteX + paletteWidth - 12f - catW, itemY,
                    SMALL_SCALE, CAT_R, CAT_G, CAT_B);
        }

        // Scroll indicator
        if (filtered.size() > MAX_VISIBLE_ROWS) {
            String info = (scrollOffset + 1) + "-" + (scrollOffset + visibleCount)
                    + " of " + filtered.size();
            float infoW = font.getTextWidth(info, SMALL_SCALE);
            font.drawText(info, paletteX + paletteWidth - 12f - infoW,
                    y + visibleCount * rowHeight + smallHeight,
                    SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
        }
    }

    private void renderDetail(float width, float height) {
        float paletteWidth = Math.min(width - PAD * 2, 600f);
        float paletteX = (width - paletteWidth) / 2f;
        float lineH = font.getLineHeight(DETAIL_SCALE);
        float y = PAD + 60f;

        // Heading: name
        font.drawText(detailAction.name(), paletteX, y, FONT_SCALE, HEADING_R, HEADING_G, HEADING_B);
        y += font.getLineHeight(FONT_SCALE) * 1.6f;

        // Shortcut
        if (!detailAction.shortcut().isEmpty()) {
            font.drawText("Shortcut", paletteX, y, SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
            y += font.getLineHeight(SMALL_SCALE) * 1.2f;
            font.drawText(detailAction.shortcut(), paletteX + 16f, y, DETAIL_SCALE, KEY_R, KEY_G, KEY_B);
            y += lineH * 1.8f;
        }

        // Category
        font.drawText("Category", paletteX, y, SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
        y += font.getLineHeight(SMALL_SCALE) * 1.2f;
        font.drawText(detailAction.category(), paletteX + 16f, y, DETAIL_SCALE, CAT_R, CAT_G, CAT_B);
        y += lineH * 1.8f;

        // Description
        String desc = detailAction.description();
        if (!desc.isEmpty()) {
            font.drawText("Description", paletteX, y, SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
            y += font.getLineHeight(SMALL_SCALE) * 1.2f;

            // Word-wrap the description
            for (String line : wordWrap(desc, paletteWidth - 16f, DETAIL_SCALE)) {
                font.drawText(line, paletteX + 16f, y, DETAIL_SCALE, DESC_R, DESC_G, DESC_B);
                y += lineH * 1.3f;
            }
        }

        // Back hint
        y += lineH * 2;
        font.drawText("Press Esc or Backspace to return to search", paletteX, y,
                SMALL_SCALE, PROMPT_R, PROMPT_G, PROMPT_B);
    }

    @Override
    public String hudText() {
        if (detailAction != null) {
            return "Esc Back to Search";
        }
        return "Type to search | Enter View Details | Esc Close";
    }

    // ── Internal ────────────────────────────────────────────────────

    private void refilter() {
        filtered = registry.filter(query.toString());
        selectedIndex = 0;
        scrollOffset = 0;
    }

    private void ensureVisible() {
        if (selectedIndex < scrollOffset) scrollOffset = selectedIndex;
        else if (selectedIndex >= scrollOffset + MAX_VISIBLE_ROWS)
            scrollOffset = selectedIndex - MAX_VISIBLE_ROWS + 1;
    }

    private int rowAtY(double my) {
        float rowHeight = font.getLineHeight(FONT_SCALE) * ROW_HEIGHT_FACTOR;
        float smallHeight = font.getLineHeight(SMALL_SCALE) * ROW_HEIGHT_FACTOR;
        float listTop = PAD + 40f + smallHeight + 8f + rowHeight + 8f + 8f;
        if (my < listTop) return -1;
        return (int) ((my - listTop) / rowHeight) + scrollOffset;
    }

    /** Simple word-wrap: splits text into lines that fit within maxWidth. */
    private String[] wordWrap(String text, float maxWidth, float scale) {
        var lines = new java.util.ArrayList<String>();
        for (String paragraph : text.split("\n")) {
            String[] words = paragraph.split(" ");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (font.getTextWidth(candidate, scale) > maxWidth && !line.isEmpty()) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            if (!line.isEmpty()) lines.add(line.toString());
        }
        return lines.toArray(new String[0]);
    }
}
