package spn.stdui.highlight;

import spn.stdui.render.ColorSpan;

/**
 * No-op highlighter: all text renders in default foreground color.
 */
public final class NullHighlighter implements Highlighter {
    public static final NullHighlighter INSTANCE = new NullHighlighter();

    private static final ColorSpan[] EMPTY = new ColorSpan[0];

    @Override
    public ColorSpan[] highlight(String line) {
        if (line.isEmpty()) return EMPTY;
        return new ColorSpan[]{ new ColorSpan(0, line.length(), 0.85f, 0.85f, 0.85f) };
    }
}
