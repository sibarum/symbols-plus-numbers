package spn.stdui.highlight;

import spn.stdui.render.ColorSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-line highlight cache with lazy recompute on access.
 * Wire to {@link spn.stdui.buffer.TextBuffer.ChangeListener} for automatic invalidation.
 */
public class HighlightCache {

    private Highlighter highlighter;
    private final List<ColorSpan[]> cache = new ArrayList<>();

    public HighlightCache(Highlighter highlighter) {
        this.highlighter = highlighter;
    }

    public void setHighlighter(Highlighter highlighter) {
        this.highlighter = highlighter;
        invalidateAll();
    }

    /** Get (or lazily compute) highlights for the given line. */
    public ColorSpan[] getSpans(int row, String lineContent) {
        ensureSize(row + 1);
        ColorSpan[] spans = cache.get(row);
        if (spans == null) {
            spans = highlighter.highlight(lineContent);
            cache.set(row, spans);
        }
        return spans;
    }

    public void invalidateLine(int row) {
        if (row >= 0 && row < cache.size()) cache.set(row, null);
    }

    public void insertLine(int row) {
        if (row >= 0 && row <= cache.size()) cache.add(row, null);
    }

    public void removeLine(int row) {
        if (row >= 0 && row < cache.size()) cache.remove(row);
    }

    public void invalidateAll() {
        cache.clear();
    }

    private void ensureSize(int minSize) {
        while (cache.size() < minSize) cache.add(null);
    }
}
