package spn.gui;

import spn.fonts.SdfFontRenderer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Splash screen shown on first launch when no file is loaded.
 * Displays ASCII art for "Symbols+Numbers" and basic shortcuts.
 * Dismissed by Ctrl+N (new), Ctrl+O (open), or Escape.
 */
class SplashMode implements Mode {

    private static final float ART_SCALE = 0.22f;
    private static final float SUBTITLE_SCALE = 0.30f;

    private static final float BG_R = 0.08f, BG_G = 0.08f, BG_B = 0.10f;
    private static final float ART_R = 0.40f, ART_G = 0.55f, ART_B = 0.80f;
    private static final float PLUS_R = 0.75f, PLUS_G = 0.55f, PLUS_B = 0.30f;
    private static final float SUB_R = 0.45f, SUB_G = 0.45f, SUB_B = 0.50f;
    private static final float VER_R = 0.30f, VER_G = 0.30f, VER_B = 0.35f;

    private static final String[] ART = loadArt();

    private static String[] loadArt() {
        try (InputStream in = SplashMode.class.getResourceAsStream("/splash.txt")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1);
            }
        } catch (Exception e) {
            // fall through
        }
        return new String[]{"Symbols + Numbers"};
    }

    private final EditorWindow window;
    private final SdfFontRenderer font;

    SplashMode(EditorWindow window) {
        this.window = window;
        this.font = window.getFont();
    }

    @Override
    public void render(float width, float height) {
        // Full background
        font.drawRect(0, 0, width, height, BG_R, BG_G, BG_B);

        float lineH = font.getLineHeight(ART_SCALE);
        float artBlockHeight = ART.length * lineH;

        // Center vertically (slightly above center)
        float startY = (height - artBlockHeight) * 0.38f;
        float y = startY;

        // Find widest line for centering
        float maxWidth = 0;
        for (String line : ART) {
            float w = font.getTextWidth(line, ART_SCALE);
            if (w > maxWidth) maxWidth = w;
        }
        float artX = (width - maxWidth) / 2f;

        // Draw art — blue accent with warm highlight on the + symbols
        for (String line : ART) {
            font.drawText(line, artX, y, ART_SCALE, ART_R, ART_G, ART_B);
            y += lineH;
        }

        // Subtitle
        y += lineH * 2;
        String subtitle = "Symbols Plus Numbers";
        float subW = font.getTextWidth(subtitle, SUBTITLE_SCALE);
        font.drawText(subtitle, (width - subW) / 2f, y, SUBTITLE_SCALE, SUB_R, SUB_G, SUB_B);

        // Version
        y += font.getLineHeight(SUBTITLE_SCALE) * 1.5f;
        String version = "v0-alpha";
        float verW = font.getTextWidth(version, SUBTITLE_SCALE);
        font.drawText(version, (width - verW) / 2f, y, SUBTITLE_SCALE, VER_R, VER_G, VER_B);
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS) return true;
        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

        if (ctrl && key == GLFW_KEY_N) {
            window.popMode(); // dismiss splash
            window.pushLegacyMode(new NewMenuMode(window));
            return true;
        }
        if (ctrl && key == GLFW_KEY_O) {
            window.popMode();
            window.openFile();
            return true;
        }
        if (key == GLFW_KEY_F1) {
            window.popMode();
            window.openSample(EditorWindow.SAMPLES[0]);
            return true;
        }
        if (key == GLFW_KEY_F2) {
            window.popMode();
            window.openSample(EditorWindow.SAMPLES[1]);
            return true;
        }
        // Any other key dismisses the splash
        if (key == GLFW_KEY_ESCAPE || key == GLFW_KEY_ENTER || key == GLFW_KEY_SPACE) {
            window.popMode();
            return true;
        }
        return true; // consume all keys while splash is shown
    }

    @Override
    public boolean onChar(int codepoint) {
        return true; // consume
    }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        if (action == GLFW_PRESS) {
            window.popMode();
        }
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        return true;
    }

    @Override
    public boolean onScroll(double xoff, double yoff) {
        return true;
    }

    @Override
    public String hudText() {
        return "Ctrl+N New | Ctrl+O Open | F1 Shapes | F2 Plot | Esc Dismiss";
    }
}
