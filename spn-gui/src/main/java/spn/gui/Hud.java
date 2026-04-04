package spn.gui;

import spn.fonts.SdfFontRenderer;

/**
 * A read-only display bar for contextual information: keyboard shortcuts,
 * error messages, code generation hints, etc.
 *
 * Renders as a single-line strip with a distinct background color.
 * Call {@link #render()} between {@code font.beginText()} and {@code font.endText()}.
 */
public class Hud {

    private final SdfFontRenderer font;

    private float boundsX, boundsY, boundsW, boundsH;

    // Visual style
    private static final float FONT_SCALE = 0.25f;
    private static final float PAD_X = 10f;
    private static final float BG_R = 0.16f, BG_G = 0.16f, BG_B = 0.20f;
    private static final float SEP_R = 0.28f, SEP_G = 0.28f, SEP_B = 0.35f;
    private static final float LABEL_R = 0.50f, LABEL_G = 0.50f, LABEL_B = 0.55f;
    private static final float KEY_R = 0.75f, KEY_G = 0.70f, KEY_B = 0.50f;
    private static final float SEP_HEIGHT = 1f;

    // Flash message colors
    private static final float OK_R = 0.40f, OK_G = 0.75f, OK_B = 0.45f;
    private static final float ERR_R = 0.90f, ERR_G = 0.35f, ERR_B = 0.35f;

    private String text = "";

    // Timed flash message (result/error overlay)
    private String flashText;
    private boolean flashIsError;
    private long flashExpiry; // System.nanoTime deadline

    public Hud(SdfFontRenderer font) {
        this.font = font;
    }

    public void setBounds(float x, float y, float w, float h) {
        boundsX = x; boundsY = y; boundsW = w; boundsH = h;
    }

    /** Set the HUD content. Segments are separated by " | " delimiters. */
    public void setText(String text) {
        this.text = text;
    }

    /** Show a timed flash message that overlays the normal HUD for a few seconds. */
    public void flash(String message, boolean isError) {
        this.flashText = message;
        this.flashIsError = isError;
        this.flashExpiry = System.nanoTime() + 4_000_000_000L; // 4 seconds
    }

    /** Returns the preferred height for this HUD at its current font scale. */
    public float preferredHeight() {
        return font.getLineHeight(FONT_SCALE) * 1.4f;
    }

    public void render() {
        // Background
        font.drawRect(boundsX, boundsY, boundsW, boundsH, BG_R, BG_G, BG_B);
        // Top separator line
        font.drawRect(boundsX, boundsY, boundsW, SEP_HEIGHT, SEP_R, SEP_G, SEP_B);

        // Flash message overrides normal HUD content while active
        if (flashText != null) {
            if (System.nanoTime() < flashExpiry) {
                float lineH = font.getLineHeight(FONT_SCALE);
                float textY = boundsY + (boundsH + lineH) * 0.5f;
                float r = flashIsError ? ERR_R : OK_R;
                float g = flashIsError ? ERR_G : OK_G;
                float b = flashIsError ? ERR_B : OK_B;
                font.drawText(flashText, boundsX + PAD_X, textY, FONT_SCALE, r, g, b);
                return;
            }
            flashText = null;
        }

        if (text.isEmpty()) return;

        float lineH = font.getLineHeight(FONT_SCALE);
        float textY = boundsY + (boundsH + lineH) * 0.5f;
        float x = boundsX + PAD_X;

        // Parse and render segments: "Ctrl+O Open | Ctrl+S Save | ..."
        // Keys (before space) get KEY color, labels (after space) get LABEL color
        String[] segments = text.split(" \\| ");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                // Draw separator
                String sep = " | ";
                font.drawText(sep, x, textY, FONT_SCALE, SEP_R, SEP_G, SEP_B);
                x += font.getTextWidth(sep, FONT_SCALE);
            }

            String seg = segments[i].trim();
            int space = seg.indexOf(' ');
            if (space > 0) {
                String key = seg.substring(0, space);
                String label = seg.substring(space);
                font.drawText(key, x, textY, FONT_SCALE, KEY_R, KEY_G, KEY_B);
                x += font.getTextWidth(key, FONT_SCALE);
                font.drawText(label, x, textY, FONT_SCALE, LABEL_R, LABEL_G, LABEL_B);
                x += font.getTextWidth(label, FONT_SCALE);
            } else {
                font.drawText(seg, x, textY, FONT_SCALE, LABEL_R, LABEL_G, LABEL_B);
                x += font.getTextWidth(seg, FONT_SCALE);
            }
        }
    }
}
