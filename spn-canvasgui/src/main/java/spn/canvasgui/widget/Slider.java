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
 * Horizontal numeric slider. Stateless — value is supplied by the caller
 * each frame; user interaction (drag or arrow keys) fires {@code onChange}
 * with the new value, and the caller is expected to update its state and
 * re-render.
 *
 * <p>Mouse capture (see {@code InputRouter}) means the thumb continues to
 * track pointer motion even when the cursor leaves the slider's bounds
 * during a drag, until the button is released.
 */
public class Slider extends Component {

    private final Theme theme;
    private double min = 0;
    private double max = 1;
    private double value = 0;
    private DoubleConsumer onChange = v -> {};

    public Slider(Theme theme) {
        this.theme = theme;
    }

    @Override
    public boolean focusable() { return true; }

    public Slider setRange(double min, double max) {
        this.min = min; this.max = max;
        invalidate();
        return this;
    }

    public Slider setValue(double v) {
        double clamped = clamp(v);
        if (clamped != this.value) {
            this.value = clamped;
            invalidate();
        }
        return this;
    }

    public double value() { return value; }

    public Slider onChange(DoubleConsumer cb) { this.onChange = cb; return this; }

    @Override
    public Size measure(Constraints c, GuiContext ctx) {
        return new Size(c.clampW(ctx.rem(theme.sliderDefaultWidthRem)),
                c.clampH(ctx.rem(theme.sliderDefaultHeightRem)));
    }

    @Override
    public void paint(List<GuiCommand> out, GuiContext ctx) {
        float w = bounds().w();
        float h = bounds().h();
        float trackH = ctx.rem(theme.sliderTrackHeightRem);
        float thumbW = ctx.rem(theme.sliderThumbWidthRem);
        float trackY = (h - trackH) * 0.5f;
        float trackX = thumbW * 0.5f;
        float trackW = w - thumbW;

        // Track
        out.add(new GuiCommand.Draw(new DrawCommand.SetFill(
                theme.sliderTrackR, theme.sliderTrackG, theme.sliderTrackB)));
        out.add(new GuiCommand.Draw(new DrawCommand.FillRect(trackX, trackY, trackW, trackH)));

        // Thumb
        float t = (float) ((value - min) / Math.max(1e-9, max - min));
        if (t < 0) t = 0; else if (t > 1) t = 1;
        float thumbX = trackX + t * trackW - thumbW * 0.5f;
        float thumbY = 0;
        float thumbH = h;

        boolean active = pressed() || hovered() || focused();
        float tr = active ? theme.sliderThumbHoverR : theme.sliderThumbR;
        float tg = active ? theme.sliderThumbHoverG : theme.sliderThumbG;
        float tb = active ? theme.sliderThumbHoverB : theme.sliderThumbB;
        out.add(new GuiCommand.Draw(new DrawCommand.SetFill(tr, tg, tb)));
        out.add(new GuiCommand.Draw(new DrawCommand.FillRect(thumbX, thumbY, thumbW, thumbH)));

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
                        emit(valueAt(p.localX()));
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

    private double valueAt(float localX) {
        float thumbW = 1; // any non-zero — only the ratio matters here
        // Use the bounds we know match what we drew with. Same math as paint.
        // We reconstruct using the latest GuiContext at paint time — but here
        // we don't have ctx, so use a simpler proportional fit on bounds.w().
        float w = bounds().w();
        float t = w > 0 ? localX / w : 0;
        if (t < 0) t = 0; else if (t > 1) t = 1;
        return min + t * (max - min);
    }

    private void emit(double newValue) {
        double clamped = clamp(newValue);
        if (clamped != value) {
            // We don't update `value` here — the caller will set it on the
            // next frame via setValue. But invalidating now keeps the visual
            // smooth in case the caller's update is delayed by one frame.
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
