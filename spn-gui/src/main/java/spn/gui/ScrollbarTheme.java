package spn.gui;

/**
 * Color theme for a {@link Scrollbar}. All values are 0-1 RGB.
 */
public class ScrollbarTheme {

    public float trackR, trackG, trackB;
    public float thumbR, thumbG, thumbB;
    public float thumbHoverR, thumbHoverG, thumbHoverB;
    public float thumbDragR, thumbDragG, thumbDragB;

    public static ScrollbarTheme dark() {
        ScrollbarTheme t = new ScrollbarTheme();
        t.trackR = 0.15f; t.trackG = 0.15f; t.trackB = 0.17f;
        t.thumbR = 0.30f; t.thumbG = 0.30f; t.thumbB = 0.33f;
        t.thumbHoverR = 0.42f; t.thumbHoverG = 0.42f; t.thumbHoverB = 0.46f;
        t.thumbDragR = 0.52f; t.thumbDragG = 0.52f; t.thumbDragB = 0.56f;
        return t;
    }

    public static ScrollbarTheme light() {
        ScrollbarTheme t = new ScrollbarTheme();
        t.trackR = 0.90f; t.trackG = 0.90f; t.trackB = 0.90f;
        t.thumbR = 0.65f; t.thumbG = 0.65f; t.thumbB = 0.68f;
        t.thumbHoverR = 0.55f; t.thumbHoverG = 0.55f; t.thumbHoverB = 0.58f;
        t.thumbDragR = 0.45f; t.thumbDragG = 0.45f; t.thumbDragB = 0.48f;
        return t;
    }
}
