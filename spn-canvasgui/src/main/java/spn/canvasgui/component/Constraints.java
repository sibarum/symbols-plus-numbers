package spn.canvasgui.component;

/**
 * Layout constraints passed to {@code Component.measure}. The component returns
 * a {@link Size} that fits within these bounds.
 */
public record Constraints(float minW, float maxW, float minH, float maxH) {

    public static Constraints tight(float w, float h) {
        return new Constraints(w, w, h, h);
    }

    public static Constraints loose(float maxW, float maxH) {
        return new Constraints(0, maxW, 0, maxH);
    }

    public float clampW(float w) {
        return Math.max(minW, Math.min(maxW, w));
    }

    public float clampH(float h) {
        return Math.max(minH, Math.min(maxH, h));
    }
}
