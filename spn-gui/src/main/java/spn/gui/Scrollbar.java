package spn.gui;

import spn.fonts.SdfFontRenderer;
import spn.stdui.widget.ScrollbarTheme;
import java.util.function.IntConsumer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Reusable scrollbar component (horizontal or vertical).
 *
 * Renders via a shared {@link SdfFontRenderer} — call {@link #render()} between
 * {@code font.beginText()} and {@code font.endText()}.
 */
public class Scrollbar {

    public enum Orientation { HORIZONTAL, VERTICAL }

    private final SdfFontRenderer font;
    private final Orientation orientation;
    private ScrollbarTheme theme = ScrollbarTheme.dark();

    // Bounds in screen pixels
    private float x, y, width, height;

    // Content / viewport / scroll value (in logical units — rows or columns)
    private int contentSize = 1;
    private int viewportSize = 1;
    private int value;

    // Interaction state
    private boolean hovered;
    private boolean thumbHovered;
    private boolean dragging;
    private float dragAnchor;

    // Computed thumb geometry (pixels along track)
    private float thumbStart;
    private float thumbLength;
    private static final float MIN_THUMB = 20f;

    // Callback when user changes the scroll value
    private IntConsumer onChange;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public Scrollbar(SdfFontRenderer font, Orientation orientation) {
        this.font = font;
        this.orientation = orientation;
    }

    public void setBounds(float x, float y, float w, float h) {
        this.x = x; this.y = y; this.width = w; this.height = h;
    }

    public void setTheme(ScrollbarTheme theme) {
        this.theme = theme;
    }

    public void setContent(int contentSize, int viewportSize) {
        this.contentSize = Math.max(1, contentSize);
        this.viewportSize = Math.max(1, viewportSize);
        this.value = clampValue(this.value);
    }

    public void setValue(int value) {
        this.value = clampValue(value);
    }

    public int getValue() { return value; }

    public void setOnChange(IntConsumer onChange) {
        this.onChange = onChange;
    }

    public boolean isDragging() { return dragging; }

    // ------------------------------------------------------------------
    // Rendering — call between font.beginText / font.endText
    // ------------------------------------------------------------------

    public void render() {
        computeThumb();

        // Track
        font.drawRect(x, y, width, height, theme.trackR, theme.trackG, theme.trackB);

        // Thumb (only if content exceeds viewport)
        if (contentSize > viewportSize) {
            float tr, tg, tb;
            if (dragging)         { tr = theme.thumbDragR;  tg = theme.thumbDragG;  tb = theme.thumbDragB; }
            else if (thumbHovered){ tr = theme.thumbHoverR; tg = theme.thumbHoverG; tb = theme.thumbHoverB; }
            else                  { tr = theme.thumbR;      tg = theme.thumbG;      tb = theme.thumbB; }

            if (orientation == Orientation.VERTICAL) {
                font.drawRect(x, y + thumbStart, width, thumbLength, tr, tg, tb);
            } else {
                font.drawRect(x + thumbStart, y, thumbLength, height, tr, tg, tb);
            }
        }
    }

    // ------------------------------------------------------------------
    // Input forwarding
    // ------------------------------------------------------------------

    public void onMouseButton(int button, int action, int mods, double mx, double my) {
        if (button != GLFW_MOUSE_BUTTON_LEFT) return;

        if (action == GLFW_PRESS && hitTest(mx, my)) {
            float along = mouseAlongTrack(mx, my);
            if (along >= thumbStart && along <= thumbStart + thumbLength) {
                // Drag thumb
                dragging = true;
                dragAnchor = along - thumbStart;
            } else {
                // Click track — page towards click
                if (along < thumbStart) {
                    applyValue(value - viewportSize);
                } else {
                    applyValue(value + viewportSize);
                }
            }
        } else if (action == GLFW_RELEASE) {
            dragging = false;
        }
    }

    public void onCursorPos(double mx, double my) {
        hovered = hitTest(mx, my);
        if (hovered && contentSize > viewportSize) {
            computeThumb();
            float along = mouseAlongTrack(mx, my);
            thumbHovered = along >= thumbStart && along <= thumbStart + thumbLength;
        } else {
            thumbHovered = false;
        }

        if (dragging) {
            float along = mouseAlongTrack(mx, my);
            float newThumbStart = along - dragAnchor;
            float track = trackLength();
            float scrollRange = track - thumbLength;
            if (scrollRange > 0) {
                float ratio = Math.max(0, Math.min(1, newThumbStart / scrollRange));
                int maxScroll = contentSize - viewportSize;
                applyValue(Math.round(ratio * maxScroll));
            }
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void computeThumb() {
        float track = trackLength();
        if (contentSize <= viewportSize) {
            thumbStart = 0;
            thumbLength = track;
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
