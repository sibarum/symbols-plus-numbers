package spn.gui;

import spn.fonts.SdfFontRenderer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Placeholder mode for testing mode stack behavior.
 * Displays a message and only responds to Escape (cancel → pop).
 */
class PlaceholderMode implements Mode {

    private static final float BG_R = 0.10f, BG_G = 0.10f, BG_B = 0.12f;
    private static final float TEXT_R = 0.70f, TEXT_G = 0.70f, TEXT_B = 0.75f;
    private static final float FONT_SCALE = 0.35f;

    private final EditorWindow window;
    private final SdfFontRenderer font;
    private final String title;

    PlaceholderMode(EditorWindow window, String title) {
        this.window = window;
        this.font = window.getFont();
        this.title = title;
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action == GLFW_PRESS && key == GLFW_KEY_ESCAPE) {
            window.popMode();
            return true;
        }
        return true; // swallow everything else
    }

    @Override
    public boolean onChar(int codepoint) { return true; }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) { return true; }

    @Override
    public boolean onScroll(double xoff, double yoff) { return true; }

    @Override
    public void render(float width, float height) {
        font.drawRect(0, 0, width, height, BG_R, BG_G, BG_B);

        float lineH = font.getLineHeight(FONT_SCALE) * 1.4f;
        float x = 40f;
        float y = 40f + lineH;

        font.drawText("> " + title, x, y, FONT_SCALE, TEXT_R, TEXT_G, TEXT_B);
        y += lineH * 2;
        font.drawText("(placeholder — press Esc to cancel)", x, y, 0.25f,
                0.45f, 0.45f, 0.50f);
    }

    @Override
    public String hudText() {
        return "Esc Cancel";
    }
}
