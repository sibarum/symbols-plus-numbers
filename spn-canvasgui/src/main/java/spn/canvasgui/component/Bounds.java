package spn.canvasgui.component;

/** Read-only pixel rectangle assigned to a component during layout. */
public record Bounds(float x, float y, float w, float h) {
    public static final Bounds ZERO = new Bounds(0, 0, 0, 0);

    public boolean contains(float px, float py) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }
}
