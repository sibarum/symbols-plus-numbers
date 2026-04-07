package spn.gui;

import spn.fonts.SdfFontRenderer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * "New" menu: New File or New Template.
 * If the current editor is clean, acts on it directly.
 * If dirty, spawns a new window.
 */
class NewMenuMode implements Mode {

    private static final float BG_R = 0.10f, BG_G = 0.10f, BG_B = 0.12f;
    private static final float TITLE_R = 0.85f, TITLE_G = 0.85f, TITLE_B = 0.85f;
    private static final float NORMAL_R = 0.60f, NORMAL_G = 0.60f, NORMAL_B = 0.65f;
    private static final float SEL_BG_R = 0.20f, SEL_BG_G = 0.30f, SEL_BG_B = 0.50f;
    private static final float SEL_R = 0.90f, SEL_G = 0.90f, SEL_B = 0.95f;
    private static final float KEY_R = 0.55f, KEY_G = 0.75f, KEY_B = 0.95f;
    private static final float FONT_SCALE = 0.35f;
    private static final float SMALL_SCALE = 0.25f;

    private static final String[] LABELS = { "New File", "New Template (.spnt)" };
    private static final String[] KEY_HINTS = { "1", "2" };

    private static final String TEMPLATE_CONTENT = """
            -- SPN Template (.spnt)
            --
            -- Placeholders:
            --   %{name:Label}     Name field. Same label = linked (edits sync).
            --   %{string:Label}   String field (quoted, escaped on output).
            --   %{number:Label}   Number field (validated on output).
            --
            -- Save as .spnt. When opened, choose Instantiate to fill placeholders.

            """;

    private final EditorWindow window;
    private final SdfFontRenderer font;
    private int selected = 0;

    NewMenuMode(EditorWindow window) {
        this.window = window;
        this.font = window.getFont();
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS) return true;
        switch (key) {
            case GLFW_KEY_UP    -> { if (selected > 0) selected--; }
            case GLFW_KEY_DOWN  -> { if (selected < LABELS.length - 1) selected++; }
            case GLFW_KEY_ENTER -> execute(selected);
            case GLFW_KEY_ESCAPE -> window.popMode();
            case GLFW_KEY_1     -> execute(0);
            case GLFW_KEY_2     -> execute(1);
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
        float paletteW = Math.min(width - 60f, 500f);
        float px = (width - paletteW) / 2f;
        float y = height * 0.3f;

        font.drawText("New", px, y, FONT_SCALE, TITLE_R, TITLE_G, TITLE_B);
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

    private float listTop;
    private float listRowH;

    @Override
    public String hudText() {
        return "1 New File | 2 New Template | Esc Cancel";
    }

    private void execute(int option) {
        window.popMode(); // remove this menu first

        // Determine target window: current if clean, new if dirty
        EditorWindow target = window;
        boolean dirty = !window.getTextArea().getText().equals(window.getSavedContent());
        if (dirty) {
            target = Main.instance.spawnWindow();
        }

        switch (option) {
            case 0 -> target.clearForNewFile();
            case 1 -> target.clearForNewFile(TEMPLATE_CONTENT);
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
