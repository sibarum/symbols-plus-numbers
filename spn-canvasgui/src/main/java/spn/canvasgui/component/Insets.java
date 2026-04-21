package spn.canvasgui.component;

/** Per-edge spacing in rem. */
public record Insets(float left, float top, float right, float bottom) {

    public static final Insets ZERO = new Insets(0, 0, 0, 0);

    public static Insets uniform(float v) { return new Insets(v, v, v, v); }

    public float horizontal() { return left + right; }
    public float vertical()   { return top + bottom; }
}
