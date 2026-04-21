package spn.canvasgui.widget;

import spn.canvas.DrawCommand;
import spn.canvasgui.cmd.GuiCommand;
import spn.canvasgui.component.Component;
import spn.canvasgui.component.Constraints;
import spn.canvasgui.component.Size;
import spn.canvasgui.input.GuiEvent;
import spn.canvasgui.theme.Theme;
import spn.canvasgui.unit.GuiContext;
import spn.stdui.input.Key;

import java.util.List;
import java.util.function.DoubleConsumer;

/**
 * Bounded rotary knob. Value in [min, max] maps linearly to a fixed angular
 * sweep (theme-configurable — default 270° from 7 o'clock to 5 o'clock).
 *
 * <p>Stateless like {@link Slider}: the caller supplies the value each frame
 * and listens for {@code on:change} to update its own state.
 *
 * <p>Drag: the pointer angle from the dial's center is clamped to the sweep
 * and mapped back to a value. Mouse capture (from {@code InputRouter}) means
 * the dial keeps tracking even if the cursor leaves its bounds during drag.
 */
public class Dial extends Component {

    private final Theme theme;
    private double min = 0;
    private double max = 1;
    private double value = 0;
    private DoubleConsumer onChange = v -> {};

    public Dial(Theme theme) {
        this.theme = theme;
    }

    @Override
    public boolean focusable() { return true; }

    public Dial setRange(double min, double max) {
        this.min = min; this.max = max;
        invalidate();
        return this;
    }

    public Dial setValue(double v) {
        double clamped = clamp(v);
        if (clamped != this.value) {
            this.value = clamped;
            invalidate();
        }
        return this;
    }

    public double value() { return value; }

    public Dial onChange(DoubleConsumer cb) { this.onChange = cb; return this; }

    @Override
    public Size measure(Constraints c, GuiContext ctx) {
        float s = ctx.rem(theme.dialDefaultSizeRem);
        return new Size(c.clampW(s), c.clampH(s));
    }

    @Override
    public void paint(List<GuiCommand> out, GuiContext ctx) {
        float w = bounds().w();
        float h = bounds().h();
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float r = Math.min(w, h) * 0.5f;

        // Track disc
        float tr = hovered() || pressed() ? theme.dialTrackR + 0.05f : theme.dialTrackR;
        float tg = hovered() || pressed() ? theme.dialTrackG + 0.05f : theme.dialTrackG;
        float tb = hovered() || pressed() ? theme.dialTrackB + 0.05f : theme.dialTrackB;
        out.add(new GuiCommand.Draw(new DrawCommand.SetFill(tr, tg, tb)));
        out.add(new GuiCommand.Draw(new DrawCommand.FillCircle(cx, cy, r)));

        // Indicator line from center outward at the current-value angle
        double t = (value - min) / Math.max(1e-9, max - min);
        if (t < 0) t = 0; else if (t > 1) t = 1;
        double angle = theme.dialSweepStartRad + t * theme.dialSweepRangeRad;
        float ex = cx + (float) (Math.cos(angle) * r * 0.85);
        float ey = cy + (float) (Math.sin(angle) * r * 0.85);
        out.add(new GuiCommand.Draw(new DrawCommand.SetStroke(
                theme.dialIndicatorR, theme.dialIndicatorG, theme.dialIndicatorB)));
        out.add(new GuiCommand.Draw(new DrawCommand.SetStrokeWeight(ctx.rem(theme.dialIndicatorWidthRem))));
        out.add(new GuiCommand.Draw(new DrawCommand.StrokeLine(cx, cy, ex, ey)));

        if (focused()) {
            float ringW = ctx.rem(theme.focusRingWidthRem);
            out.add(new GuiCommand.Draw(new DrawCommand.SetFill(
                    theme.focusRingR, theme.focusRingG, theme.focusRingB)));
            out.add(new GuiCommand.Draw(new DrawCommand.FillRect(0, 0, w, ringW)));
            out.add(new GuiCommand.Draw(new DrawCommand.FillRect(0, h - ringW, w, ringW)));
            out.add(new GuiCommand.Draw(new DrawCommand.FillRect(0, 0, ringW, h)));
            out.add(new GuiCommand.Draw(new DrawCommand.FillRect(w - ringW, 0, ringW, h)));
        }
        clearDirty();
    }

    @Override
    public boolean onEvent(GuiEvent e) {
        if (e instanceof GuiEvent.Pointer p) {
            switch (p.phase()) {
                case PRESS, MOVE -> {
                    if (p.phase() == GuiEvent.PointerPhase.PRESS || pressed()) {
                        Double v = valueAtPointer(p.localX(), p.localY());
                        if (v != null) emit(v);
                        return true;
                    }
                }
                case CLICK, RELEASE, ENTER, EXIT -> { return true; }
            }
        }
        if (e instanceof GuiEvent.KeyDown k && focused()) {
            double step = (max - min) * 0.05;
            if (k.key() == Key.LEFT || k.key() == Key.DOWN) { emit(value - step); return true; }
            if (k.key() == Key.RIGHT || k.key() == Key.UP)  { emit(value + step); return true; }
            if (k.key() == Key.HOME) { emit(min); return true; }
            if (k.key() == Key.END)  { emit(max); return true; }
        }
        return false;
    }

    /**
     * Map a pointer position (component-local) to a value on the sweep.
     * Returns null if the pointer is effectively at the center (angle
     * ambiguous) so we don't snap to an arbitrary position.
     */
    private Double valueAtPointer(float localX, float localY) {
        float cx = bounds().w() * 0.5f;
        float cy = bounds().h() * 0.5f;
        float dx = localX - cx;
        float dy = localY - cy;
        if (dx * dx + dy * dy < 1e-4f) return null;

        double theta = Math.atan2(dy, dx);
        // Normalize to the sweep's coordinate space: relative to the start angle,
        // going clockwise in [0, 2π).
        double rel = theta - theme.dialSweepStartRad;
        rel = ((rel % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);

        if (rel > theme.dialSweepRangeRad) {
            // Pointer is in the dead sector. Snap to the nearer end.
            double distToStart = 2 * Math.PI - rel;
            double distToEnd = rel - theme.dialSweepRangeRad;
            rel = distToStart < distToEnd ? 0 : theme.dialSweepRangeRad;
        }
        double t = rel / theme.dialSweepRangeRad;
        return min + t * (max - min);
    }

    private void emit(double newValue) {
        double clamped = clamp(newValue);
        if (clamped != value) {
            invalidate();
            onChange.accept(clamped);
        }
    }

    private double clamp(double v) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
