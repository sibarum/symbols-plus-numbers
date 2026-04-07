package spn.stdui.render;

/**
 * A span of characters with a specific color, used for syntax-highlighted rendering.
 *
 * @param startCol first column (inclusive)
 * @param endCol   last column (exclusive)
 * @param r        red   (0-1)
 * @param g        green (0-1)
 * @param b        blue  (0-1)
 */
public record ColorSpan(int startCol, int endCol, float r, float g, float b) {}
