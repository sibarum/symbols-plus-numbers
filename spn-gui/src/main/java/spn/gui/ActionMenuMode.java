package spn.gui;

import spn.fonts.SdfFontRenderer;
import spn.stdui.action.Action;
import spn.stdui.action.ActionRegistry;

import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Full-window command palette. Type to filter, Up/Down to navigate,
 * Enter to execute, Escape to close.
 *
 * <p>Follows the design constraint: full-window takeover, no popups.
 */
public class ActionMenuMode implements Mode {

    private static final float FONT_SCALE = 0.35f;
    private static final float SMALL_SCALE = 0.25f;
    private static final float PAD = 30f;
    private static final float ROW_HEIGHT_FACTOR = 1.4f;
    private static final int MAX_VISIBLE_ROWS = 20;

    // Colors
    private static final float BG_R = 0.10f, BG_G = 0.10f, BG_B = 0.12f;
    private static final float INPUT_BG_R = 0.16f, INPUT_BG_G = 0.16f, INPUT_BG_B = 0.20f;
    private static final float SEL_R = 0.20f, SEL_G = 0.30f, SEL_B = 0.50f;
    private static final float NAME_R = 0.85f, NAME_G = 0.85f, NAME_B = 0.85f;
    private static final float CAT_R = 0.50f, CAT_G = 0.50f, CAT_B = 0.55f;
    private static final float KEY_R = 0.60f, KEY_G = 0.55f, KEY_B = 0.40f;
    private static final float CURSOR_R = 0.90f, CURSOR_G = 0.90f, CURSOR_B = 0.30f;
    private static final float PROMPT_R = 0.55f, PROMPT_G = 0.55f, PROMPT_B = 0.60f;

    private final EditorWindow window;
    private final SdfFontRenderer font;
    private final ActionRegistry registry;

    private final StringBuilder query = new StringBuilder();
    private int cursorPos;
    private int selectedIndex;
    private int scrollOffset;
    private List<Action> filtered;

    ActionMenuMode(EditorWindow window, ActionRegistry registry) {
        this.window = window;
        this.font = window.getFont();
        this.registry = registry;
        this.filtered = registry.all();
        this.selectedIndex = 0;
        this.scrollOffset = 0;
    }

    // ---- Mode interface -------------------------------------------------

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return true;

        switch (key) {
            case GLFW_KEY_ESCAPE -> {
                window.popMode();
                return true;
            }
            case GLFW_KEY_ENTER -> {
                if (!filtered.isEmpty() && selectedIndex < filtered.size()) {
                    Action a = filtered.get(selectedIndex);
                    window.popMode();
                    a.execute().run();
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
            case GLFW_KEY_LEFT -> {
                if (cursorPos > 0) cursorPos--;
                return true;
            }
            case GLFW_KEY_RIGHT -> {
                if (cursorPos < query.length()) cursorPos++;
                return true;
            }
            case GLFW_KEY_HOME -> {
                cursorPos = 0;
                return true;
            }
            case GLFW_KEY_END -> {
                cursorPos = query.length();
                return true;
            }
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
        // Click on a row to select and execute
        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
            int clickedIndex = rowAtY(my);
            if (clickedIndex >= 0 && clickedIndex < filtered.size()) {
                Action a = filtered.get(clickedIndex);
                window.popMode();
                a.execute().run();
            }
        }
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        // Hover highlights the row
        int hoverIndex = rowAtY(my);
        if (hoverIndex >= 0 && hoverIndex < filtered.size()) {
            selectedIndex = hoverIndex;
        }
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
        // Full background
        font.drawRect(0, 0, width, height, BG_R, BG_G, BG_B);

        float rowHeight = font.getLineHeight(FONT_SCALE) * ROW_HEIGHT_FACTOR;
        float smallHeight = font.getLineHeight(SMALL_SCALE) * ROW_HEIGHT_FACTOR;

        // Center the palette horizontally with a max width
        float paletteWidth = Math.min(width - PAD * 2, 700f);
        float paletteX = (width - paletteWidth) / 2f;
        float y = PAD + 40f;

        // Prompt label
        font.drawText("> Action Menu", paletteX, y, SMALL_SCALE,
                PROMPT_R, PROMPT_G, PROMPT_B);
        y += smallHeight + 8f;

        // Search input box
        float inputH = rowHeight + 8f;
        font.drawRect(paletteX, y - rowHeight, paletteWidth, inputH,
                INPUT_BG_R, INPUT_BG_G, INPUT_BG_B);

        String queryStr = query.toString();
        float textX = paletteX + 12f;
        font.drawText(queryStr, textX, y, FONT_SCALE, NAME_R, NAME_G, NAME_B);

        // Cursor
        float cursorX = textX + font.getTextWidth(queryStr.substring(0, cursorPos), FONT_SCALE);
        font.drawRect(cursorX, y - rowHeight + 4f, 2f, rowHeight,
                CURSOR_R, CURSOR_G, CURSOR_B);

        y += inputH + 8f;

        // Filtered results
        int visibleCount = Math.min(MAX_VISIBLE_ROWS, filtered.size() - scrollOffset);
        for (int i = 0; i < visibleCount; i++) {
            int idx = scrollOffset + i;
            Action a = filtered.get(idx);

            float rowY = y + i * rowHeight;

            // Selection highlight
            if (idx == selectedIndex) {
                font.drawRect(paletteX, rowY, paletteWidth, rowHeight,
                        SEL_R, SEL_G, SEL_B);
            }

            float itemY = rowY + rowHeight - 4f;

            // Category tag
            String cat = a.category();
            font.drawText(cat, paletteX + 12f, itemY, SMALL_SCALE,
                    CAT_R, CAT_G, CAT_B);
            float catWidth = font.getTextWidth(cat, SMALL_SCALE);

            // Action name
            font.drawText(a.name(), paletteX + 12f + catWidth + 10f, itemY, FONT_SCALE,
                    NAME_R, NAME_G, NAME_B);

            // Shortcut (right-aligned)
            if (!a.shortcut().isEmpty()) {
                float shortcutWidth = font.getTextWidth(a.shortcut(), SMALL_SCALE);
                font.drawText(a.shortcut(),
                        paletteX + paletteWidth - 12f - shortcutWidth, itemY,
                        SMALL_SCALE, KEY_R, KEY_G, KEY_B);
            }
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

    @Override
    public String hudText() {
        return "Type to search | Up/Down Navigate | Enter Execute | Esc Cancel";
    }

    // ---- Internal -------------------------------------------------------

    private void refilter() {
        filtered = registry.filter(query.toString());
        selectedIndex = 0;
        scrollOffset = 0;
    }

    private void ensureVisible() {
        if (selectedIndex < scrollOffset) {
            scrollOffset = selectedIndex;
        } else if (selectedIndex >= scrollOffset + MAX_VISIBLE_ROWS) {
            scrollOffset = selectedIndex - MAX_VISIBLE_ROWS + 1;
        }
    }

    /** Determine which result row index a y-coordinate maps to, or -1. */
    private int rowAtY(double my) {
        float rowHeight = font.getLineHeight(FONT_SCALE) * ROW_HEIGHT_FACTOR;
        float smallHeight = font.getLineHeight(SMALL_SCALE) * ROW_HEIGHT_FACTOR;
        float listTop = PAD + 40f + smallHeight + 8f + rowHeight + 8f + 8f;
        if (my < listTop) return -1;
        int row = (int) ((my - listTop) / rowHeight) + scrollOffset;
        return row;
    }
}
