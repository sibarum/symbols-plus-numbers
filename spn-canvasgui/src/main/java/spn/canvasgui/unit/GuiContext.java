package spn.canvasgui.unit;

import spn.fonts.SdfFontRenderer;

/**
 * Per-frame rendering context. Threaded into measure/paint so widgets can
 * convert rem to pixels and measure text.
 *
 * <p>The {@code remPx} reference unit is adjustable per-window to support
 * zoom levels and DPI changes.
 */
public final class GuiContext {

    private final SdfFontRenderer font;
    private float remPx;
    private int viewportW;
    private int viewportH;

    public GuiContext(SdfFontRenderer font, float remPx) {
        this.font = font;
        this.remPx = remPx;
    }

    public SdfFontRenderer font() { return font; }

    public float remPx() { return remPx; }

    public void setRemPx(float remPx) { this.remPx = remPx; }

    /** Convert rem → px. */
    public float rem(float r) { return r * remPx; }

    public int viewportW() { return viewportW; }

    public int viewportH() { return viewportH; }

    public void setViewport(int w, int h) {
        this.viewportW = w;
        this.viewportH = h;
    }
}
