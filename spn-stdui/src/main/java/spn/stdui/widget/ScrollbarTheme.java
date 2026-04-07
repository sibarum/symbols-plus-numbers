package spn.stdui.widget;

/**
 * Color theme for a {@link Scrollbar}.
 */
public class ScrollbarTheme {
    public float trackR, trackG, trackB;
    public float thumbR, thumbG, thumbB;
    public float thumbHoverR, thumbHoverG, thumbHoverB;
    public float thumbDragR, thumbDragG, thumbDragB;

    public static ScrollbarTheme dark() {
        ScrollbarTheme t = new ScrollbarTheme();
        t.trackR = 0.12f; t.trackG = 0.12f; t.trackB = 0.14f;
        t.thumbR = 0.25f; t.thumbG = 0.25f; t.thumbB = 0.30f;
        t.thumbHoverR = 0.35f; t.thumbHoverG = 0.35f; t.thumbHoverB = 0.40f;
        t.thumbDragR = 0.45f; t.thumbDragG = 0.45f; t.thumbDragB = 0.50f;
        return t;
    }

    public static ScrollbarTheme light() {
        ScrollbarTheme t = new ScrollbarTheme();
        t.trackR = 0.90f; t.trackG = 0.90f; t.trackB = 0.92f;
        t.thumbR = 0.65f; t.thumbG = 0.65f; t.thumbB = 0.70f;
        t.thumbHoverR = 0.55f; t.thumbHoverG = 0.55f; t.thumbHoverB = 0.60f;
        t.thumbDragR = 0.45f; t.thumbDragG = 0.45f; t.thumbDragB = 0.50f;
        return t;
    }
}
