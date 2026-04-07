package spn.stdui.render;

/**
 * Abstract rendering surface for text and rectangles. All coordinates are
 * in screen pixels with (0,0) at top-left.
 *
 * <p>Call {@link #beginFrame}/{@link #endFrame} once per render cycle;
 * all draw calls go between them.
 *
 * <p>Implementations: {@code SdfFontRenderer} in spn-fonts.
 */
public interface Renderer {

    /** Begin a frame. Sets up projection for the given screen dimensions. */
    void beginFrame(int screenWidth, int screenHeight);

    /** End a frame. Flushes batches, restores state. */
    void endFrame();

    /** Draw a string in a single color. */
    void drawText(String text, float x, float y, float scale, float r, float g, float b);

    /**
     * Draw a line of text with per-span coloring.
     *
     * @param line     full line text
     * @param spans    color spans
     * @param x        pixel x of column 0
     * @param y        pixel y (baseline)
     * @param scale    font scale
     * @param startCol first visible column
     * @param endCol   one past last visible column
     */
    void drawColoredLine(String line, ColorSpan[] spans, float x, float y,
                         float scale, int startCol, int endCol);

    /** Draw a solid filled rectangle. */
    void drawRect(float x, float y, float w, float h, float r, float g, float b);

    /** Measure the pixel width of a string at the given scale. */
    float getTextWidth(String text, float scale);

    /** Line height at the given scale. */
    float getLineHeight(float scale);
}
