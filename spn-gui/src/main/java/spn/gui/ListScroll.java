package spn.gui;

/**
 * Reusable scroll state for integer-offset scrolling (row lists, table panels).
 * Uses the same wheel-to-delta conversion as {@link TextArea} for consistent feel.
 */
public final class ListScroll {

    private int value;
    private int max;

    /**
     * Convert a raw mouse-wheel offset to a scroll delta (rows).
     * Shared by TextArea and list views for uniform scroll feel.
     */
    public static int delta(double yoff) {
        if (yoff == 0) return 0;
        int d = (int) Math.round(yoff * 4);
        if (d == 0) d = (yoff > 0) ? 1 : -1;
        return d;
    }

    /** Apply a mouse wheel event. Clamps to [0, max]. */
    public void onScroll(double yoff) {
        value = clamp(value - delta(yoff));
    }

    public int get() { return value; }

    public void set(int v) { value = clamp(v); }

    /** Set the upper bound (content rows − visible rows). Reclamps value. */
    public void setMax(int max) {
        this.max = Math.max(0, max);
        value = clamp(value);
    }

    public void reset() { value = 0; }

    private int clamp(int v) { return Math.max(0, Math.min(v, max)); }
}
