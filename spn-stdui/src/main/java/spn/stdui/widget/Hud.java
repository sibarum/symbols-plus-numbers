package spn.stdui.widget;

import spn.stdui.render.Renderer;

import java.util.List;

/**
 * Status bar rendered at the bottom of a window frame.
 * Belongs to the window, not to any particular mode.
 *
 * <p>Displays structured {@link HudSegment}s (key hint + label pairs)
 * and supports timed flash messages for success/error feedback.
 */
public class Hud {

    private static final float FONT_SCALE = 0.25f;
    private static final float PAD_X = 10f;
    private static final float BG_R = 0.16f, BG_G = 0.16f, BG_B = 0.20f;
    private static final float SEP_R = 0.28f, SEP_G = 0.28f, SEP_B = 0.35f;
    private static final float LABEL_R = 0.50f, LABEL_G = 0.50f, LABEL_B = 0.55f;
    private static final float KEY_R = 0.75f, KEY_G = 0.70f, KEY_B = 0.50f;
    private static final float SEP_HEIGHT = 1f;
    private static final float OK_R = 0.40f, OK_G = 0.75f, OK_B = 0.45f;
    private static final float ERR_R = 0.90f, ERR_G = 0.35f, ERR_B = 0.35f;

    private float boundsX, boundsY, boundsW, boundsH;
    private List<HudSegment> segments = List.of();

    private String flashText;
    private boolean flashIsError;
    private double flashExpiry; // seconds (monotonic)

    public void setBounds(float x, float y, float w, float h) {
        boundsX = x; boundsY = y; boundsW = w; boundsH = h;
    }

    public void setSegments(List<HudSegment> segments) {
        this.segments = segments;
    }

    /** Show a timed flash message (4 seconds). */
    public void flash(String message, boolean isError) {
        this.flashText = message;
        this.flashIsError = isError;
        this.flashExpiry = System.nanoTime() / 1e9 + 4.0;
    }

    /** Preferred height for layout calculations. */
    public float preferredHeight(Renderer renderer) {
        return renderer.getLineHeight(FONT_SCALE) * 1.4f;
    }

    /** Render the HUD. Call between beginFrame/endFrame. */
    public void render(Renderer renderer, double now) {
        // Background
        renderer.drawRect(boundsX, boundsY, boundsW, boundsH, BG_R, BG_G, BG_B);
        // Top separator line
        renderer.drawRect(boundsX, boundsY, boundsW, SEP_HEIGHT, SEP_R, SEP_G, SEP_B);

        float lineH = renderer.getLineHeight(FONT_SCALE);
        float textY = boundsY + (boundsH + lineH) * 0.5f;

        // Flash message overrides segments
        if (flashText != null) {
            if (now < flashExpiry) {
                float r = flashIsError ? ERR_R : OK_R;
                float g = flashIsError ? ERR_G : OK_G;
                float b = flashIsError ? ERR_B : OK_B;
                renderer.drawText(flashText, boundsX + PAD_X, textY, FONT_SCALE, r, g, b);
                return;
            }
            flashText = null;
        }

        if (segments.isEmpty()) return;

        float x = boundsX + PAD_X;
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                String sep = " | ";
                renderer.drawText(sep, x, textY, FONT_SCALE, SEP_R, SEP_G, SEP_B);
                x += renderer.getTextWidth(sep, FONT_SCALE);
            }
            HudSegment seg = segments.get(i);
            if (!seg.keyHint().isEmpty()) {
                renderer.drawText(seg.keyHint(), x, textY, FONT_SCALE, KEY_R, KEY_G, KEY_B);
                x += renderer.getTextWidth(seg.keyHint(), FONT_SCALE);
                renderer.drawText(" ", x, textY, FONT_SCALE, LABEL_R, LABEL_G, LABEL_B);
                x += renderer.getTextWidth(" ", FONT_SCALE);
            }
            renderer.drawText(seg.label(), x, textY, FONT_SCALE, LABEL_R, LABEL_G, LABEL_B);
            x += renderer.getTextWidth(seg.label(), FONT_SCALE);
        }
    }
}
