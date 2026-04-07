package spn.stdui.highlight;

import spn.stdui.render.ColorSpan;

/**
 * Converts a line of text into colored spans for rendering.
 * Implementations are language-specific and live outside spn-stdui.
 *
 * <p>The contract is one line in, colored spans out. No cross-line state.
 */
@FunctionalInterface
public interface Highlighter {
    ColorSpan[] highlight(String line);
}
