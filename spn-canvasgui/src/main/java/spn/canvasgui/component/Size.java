package spn.canvasgui.component;

public record Size(float w, float h) {
    public static final Size ZERO = new Size(0, 0);
}
