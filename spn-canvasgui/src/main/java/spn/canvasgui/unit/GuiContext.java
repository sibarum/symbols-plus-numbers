package spn.canvasgui.unit;

import spn.canvasgui.font.FontRegistry;
import spn.fonts.SdfFontRenderer;

/**
 * Per-frame rendering context. Threaded into measure/paint so widgets can
 * convert rem to pixels, resolve fonts, and query other host state.
 *
 * <p>The {@code remPx} reference unit is adjustable per-window to support
 * zoom levels and DPI changes.
 */
public final class GuiContext {

    private final FontRegistry fonts;
    private float remPx;
    private int viewportW;
    private int viewportH;
    private double time;
    private long windowHandle;

    public GuiContext(FontRegistry fonts, float remPx) {
        this.fonts = fonts;
        this.remPx = remPx;
    }

    /** The font registry for this window; lookup by symbol, default fallback. */
    public FontRegistry fonts() { return fonts; }

    /** Convenience: the default font (conventionally {@code :mono}). */
    public SdfFontRenderer font() {
        return fonts != null ? fonts.getDefault() : null;
    }

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

    public double time() { return time; }
    public void setTime(double t) { this.time = t; }

    public long windowHandle() { return windowHandle; }
    public void setWindowHandle(long h) { this.windowHandle = h; }
}
