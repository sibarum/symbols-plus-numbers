package spn.canvas;

import java.awt.image.BufferedImage;

/**
 * A writable image: thin wrapper around {@link BufferedImage} that SPN code
 * can pass around as an opaque value. Supports per-pixel writes, bulk
 * {@code mapPixels} fills, and replay onto the OpenGL canvas via
 * {@link DrawCommand.DrawImage}.
 *
 * <p>The {@code dirty} flag lets {@link CanvasRenderer}'s texture cache
 * detect mutations between {@code drawImage} calls and re-upload the GL
 * texture only when needed.
 */
public final class SpnImage {

    private final BufferedImage buffer;
    private boolean dirty = true;

    public SpnImage(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("image dimensions must be positive, got "
                    + width + "x" + height);
        }
        this.buffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }

    public int width()  { return buffer.getWidth(); }
    public int height() { return buffer.getHeight(); }

    public BufferedImage buffer() { return buffer; }

    public boolean isDirty()       { return dirty; }
    public void clearDirty()       { dirty = false; }

    /** Write a pixel from normalized RGB doubles in [0,1]. Out-of-range values are clamped. */
    public void setPixel(int x, int y, double r, double g, double b) {
        if (x < 0 || x >= width() || y < 0 || y >= height()) return;
        int ir = clampByte(r);
        int ig = clampByte(g);
        int ib = clampByte(b);
        int argb = (0xFF << 24) | (ir << 16) | (ig << 8) | ib;
        buffer.setRGB(x, y, argb);
        dirty = true;
    }

    /** Fast path for the bulk fill loop: caller pre-clamps and packs. */
    public void setPixelArgb(int x, int y, int argb) {
        if (x < 0 || x >= width() || y < 0 || y >= height()) return;
        buffer.setRGB(x, y, argb);
        dirty = true;
    }

    public static int packArgb(double r, double g, double b) {
        return (0xFF << 24) | (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
    }

    private static int clampByte(double v) {
        if (v <= 0.0) return 0;
        if (v >= 1.0) return 255;
        return (int) (v * 255.0 + 0.5);
    }

    @Override
    public String toString() {
        return "Image(" + width() + "x" + height() + ")";
    }
}
