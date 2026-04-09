package spn.stdui.widget;

import spn.stdui.input.InputEvent;
import spn.stdui.render.Renderer;

import java.util.function.IntConsumer;

/**
 * Platform-neutral scrollbar component (horizontal or vertical).
 * Renders via a {@link Renderer}; handles mouse interaction through {@link InputEvent}s.
 *
 * <p>This is the stdui version used by new {@link spn.stdui.mode.Mode} implementations.
 * The legacy equivalent in spn-gui renders directly via {@code SdfFontRenderer}
 * and consumes raw GLFW callbacks.
 */
public class Scrollbar {

    public enum Orientation { HORIZONTAL, VERTICAL }

    private final Orientation orientation;
    private ScrollbarTheme theme = ScrollbarTheme.dark();

    private float x, y, width, height;

    private int contentSize = 1;
    private int viewportSize = 1;
    private int value;

    private boolean hovered;
    private boolean thumbHovered;
    private boolean dragging;
    private float dragAnchor;

    private float thumbStart;
    private float thumbLength;
    private static final float MIN_THUMB = 20f;

    private IntConsumer onChange;

    public Scrollbar(Orientation orientation) {
        this.orientation = orientation;
    }

    public void setBounds(float x, float y, float w, float h) {
        this.x = x; this.y = y; this.width = w; this.height = h;
    }

    public void setTheme(ScrollbarTheme theme) { this.theme = theme; }

    public void setContent(int contentSize, int viewportSize) {
        this.contentSize = Math.max(1, contentSize);
        this.viewportSize = Math.max(1, viewportSize);
        this.value = clampValue(this.value);
    }

    public void setValue(int value) { this.value = clampValue(value); }
    public int getValue() { return value; }

    public void setOnChange(IntConsumer onChange) { this.onChange = onChange; }
    public boolean isDragging() { return dragging; }

    // ---- Rendering ----

    public void render(Renderer renderer) {
        computeThumb();

        renderer.drawRect(x, y, width, height, theme.trackR, theme.trackG, theme.trackB);

        if (contentSize > viewportSize) {
            float tr, tg, tb;
            if (dragging)          { tr = theme.thumbDragR;  tg = theme.thumbDragG;  tb = theme.thumbDragB; }
            else if (thumbHovered) { tr = theme.thumbHoverR; tg = theme.thumbHoverG; tb = theme.thumbHoverB; }
            else                   { tr = theme.thumbR;      tg = theme.thumbG;      tb = theme.thumbB; }

            if (orientation == Orientation.VERTICAL) {
                renderer.drawRect(x, y + thumbStart, width, thumbLength, tr, tg, tb);
            } else {
                renderer.drawRect(x + thumbStart, y, thumbLength, height, tr, tg, tb);
            }
        }
    }

    // ---- Input handling ----

    /** Process a mouse input event. Returns true if consumed. */
    public boolean onInput(InputEvent event) {
        return switch (event) {
            case InputEvent.MousePress mp -> {
                if (mp.button() != 0) yield false; // left button only
                if (!hitTest(mp.x(), mp.y())) yield false;
                float along = mouseAlongTrack(mp.x(), mp.y());
                if (along >= thumbStart && along <= thumbStart + thumbLength) {
                    dragging = true;
                    dragAnchor = along - thumbStart;
                } else {
                    if (along < thumbStart) applyValue(value - viewportSize);
                    else applyValue(value + viewportSize);
                }
                yield true;
            }
            case InputEvent.MouseRelease mr -> {
                if (dragging) { dragging = false; yield true; }
                yield false;
            }
            case InputEvent.MouseMove mm -> {
                hovered = hitTest(mm.x(), mm.y());
                if (hovered && contentSize > viewportSize) {
                    computeThumb();
                    float along = mouseAlongTrack(mm.x(), mm.y());
                    thumbHovered = along >= thumbStart && along <= thumbStart + thumbLength;
                } else {
                    thumbHovered = false;
                }
                if (dragging) {
                    float along = mouseAlongTrack(mm.x(), mm.y());
                    float newThumbStart = along - dragAnchor;
                    float track = trackLength();
                    float scrollRange = track - thumbLength;
                    if (scrollRange > 0) {
                        float ratio = Math.max(0, Math.min(1, newThumbStart / scrollRange));
                        int maxScroll = contentSize - viewportSize;
                        applyValue(Math.round(ratio * maxScroll));
                    }
                    yield true;
                }
                yield false;
            }
            default -> false;
        };
    }

    // ---- Internals ----

    private void computeThumb() {
        float track = trackLength();
        if (contentSize <= viewportSize) {
            thumbStart = 0; thumbLength = track;
        } else {
            thumbLength = Math.max(MIN_THUMB, track * viewportSize / contentSize);
            float scrollRange = track - thumbLength;
            int maxScroll = contentSize - viewportSize;
            thumbStart = (maxScroll > 0) ? scrollRange * value / maxScroll : 0;
        }
    }

    private float trackLength() {
        return orientation == Orientation.VERTICAL ? height : width;
    }

    private float mouseAlongTrack(double mx, double my) {
        return orientation == Orientation.VERTICAL ? (float) my - y : (float) mx - x;
    }

    private boolean hitTest(double mx, double my) {
        return mx >= x && mx <= x + width && my >= y && my <= y + height;
    }

    private int clampValue(int v) {
        return Math.max(0, Math.min(v, contentSize - viewportSize));
    }

    private void applyValue(int v) {
        value = clampValue(v);
        if (onChange != null) onChange.accept(value);
    }
}
