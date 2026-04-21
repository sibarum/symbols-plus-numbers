package spn.gui;

import spn.fonts.SdfFontRenderer;

import java.nio.file.Path;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Pushed when the user triggers "Reload from Disk" (Ctrl+R) on a tab with
 * unsaved changes. Offers three resolutions:
 *   (1) Reload / Discard Changes — replace in-memory content with disk
 *   (2) Overwrite / Save Changes  — write in-memory content to disk
 *   (3) Save As                   — save in-memory content to a new path
 * Esc cancels (dialog dismisses, nothing happens).
 */
class RefreshConflictMode implements Mode {

    private static final float BG_R = 0.10f, BG_G = 0.10f, BG_B = 0.12f;
    private static final float TITLE_R = 0.85f, TITLE_G = 0.85f, TITLE_B = 0.85f;
    private static final float NORMAL_R = 0.60f, NORMAL_G = 0.60f, NORMAL_B = 0.65f;
    private static final float SEL_BG_R = 0.20f, SEL_BG_G = 0.30f, SEL_BG_B = 0.50f;
    private static final float SEL_R = 0.90f, SEL_G = 0.90f, SEL_B = 0.95f;
    private static final float KEY_R = 0.55f, KEY_G = 0.75f, KEY_B = 0.95f;
    private static final float FONT_SCALE = 0.35f;
    private static final float SMALL_SCALE = 0.25f;

    private static final String[] LABELS = {
            "Reload / Discard Changes",
            "Overwrite / Save Changes",
            "Save As"
    };
    private static final String[] KEY_HINTS = { "1", "2", "3" };

    private final EditorWindow window;
    private final SdfFontRenderer font;
    private final Path filePath;
    private final String diskContent;
    private int selected = 0;

    private float listTop;
    private float listRowH;

    RefreshConflictMode(EditorWindow window, Path filePath, String diskContent) {
        this.window = window;
        this.font = window.getFont();
        this.filePath = filePath;
        this.diskContent = diskContent;
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS) return true;
        switch (key) {
            case GLFW_KEY_UP    -> { if (selected > 0) selected--; }
            case GLFW_KEY_DOWN  -> { if (selected < LABELS.length - 1) selected++; }
            case GLFW_KEY_ENTER -> execute(selected);
            case GLFW_KEY_ESCAPE -> window.popMode();
            case GLFW_KEY_1 -> execute(0);
            case GLFW_KEY_2 -> execute(1);
            case GLFW_KEY_3 -> execute(2);
        }
        return true;
    }

    @Override
    public boolean onChar(int codepoint) { return true; }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
            int clicked = rowAtY(my);
            if (clicked >= 0 && clicked < LABELS.length) execute(clicked);
        }
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        int hover = rowAtY(my);
        if (hover >= 0 && hover < LABELS.length) selected = hover;
        return true;
    }

    @Override
    public boolean onScroll(double xoff, double yoff) { return true; }

    @Override
    public void render(float width, float height) {
        font.drawRect(0, 0, width, height, BG_R, BG_G, BG_B);

        float rowH = font.getLineHeight(FONT_SCALE) * 1.4f;
        float paletteW = Math.min(width - 60f, 540f);
        float px = (width - paletteW) / 2f;
        float y = height * 0.3f;

        font.drawText("File changed on disk — you have unsaved changes",
                px, y, FONT_SCALE, TITLE_R, TITLE_G, TITLE_B);
        y += rowH + 10f;
        font.drawText(filePath.getFileName().toString(),
                px, y, SMALL_SCALE, NORMAL_R, NORMAL_G, NORMAL_B);
        y += rowH + 16f;

        for (int i = 0; i < LABELS.length; i++) {
            float oy = y + i * (rowH + 4f);
            if (i == selected) {
                font.drawRect(px - 8f, oy - rowH + 4f, paletteW + 16f, rowH + 2f,
                        SEL_BG_R, SEL_BG_G, SEL_BG_B);
            }
            float lr = (i == selected) ? SEL_R : NORMAL_R;
            float lg = (i == selected) ? SEL_G : NORMAL_G;
            float lb = (i == selected) ? SEL_B : NORMAL_B;
            font.drawText(KEY_HINTS[i], px, oy, SMALL_SCALE, KEY_R, KEY_G, KEY_B);
            float kw = font.getTextWidth(KEY_HINTS[i], SMALL_SCALE);
            font.drawText("  " + LABELS[i], px + kw, oy, FONT_SCALE, lr, lg, lb);
        }
        listTop = y;
        listRowH = rowH + 4f;
    }

    @Override
    public String hudText() {
        return "1 Reload  |  2 Overwrite  |  3 Save As  |  Esc Cancel";
    }

    private void execute(int option) {
        switch (option) {
            case 0 -> { // Reload: replace in-memory with disk, discard local edits
                EditorTab et = window.getActiveEditorTab();
                if (et != null) {
                    et.loadContent(diskContent);
                    window.flash("Reloaded " + filePath.getFileName() + " (discarded local changes)", false);
                }
                window.popMode();
            }
            case 1 -> { // Overwrite: write in-memory to disk, discard external edits
                window.saveFile(false);
                window.popMode();
            }
            case 2 -> { // Save As: write in-memory to a new path, leave disk untouched
                window.saveFile(true);
                window.popMode();
            }
        }
    }

    private int rowAtY(double my) {
        if (listRowH <= 0) return -1;
        float rowH = font.getLineHeight(FONT_SCALE) * 1.4f;
        double rel = my - listTop + rowH - 4f;
        if (rel < 0) return -1;
        return (int) (rel / listRowH);
    }
}
