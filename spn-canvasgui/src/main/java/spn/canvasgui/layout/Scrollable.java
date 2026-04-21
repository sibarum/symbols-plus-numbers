package spn.canvasgui.layout;

import spn.canvas.DrawCommand;
import spn.canvasgui.cmd.GuiCommand;
import spn.canvasgui.component.Bounds;
import spn.canvasgui.component.Component;
import spn.canvasgui.component.Constraints;
import spn.canvasgui.component.Size;
import spn.canvasgui.input.GuiEvent;
import spn.canvasgui.theme.Theme;
import spn.canvasgui.unit.GuiContext;

import java.util.List;

/**
 * Vertically-scrolling single-child container. Holds its own scroll offset
 * as view state (not user state — scroll position isn't app-semantic).
 * The mouse wheel scrolls; when the child overflows, a thin indicator on
 * the right shows the current position.
 *
 * <p>Child is given unbounded vertical space when measuring — it decides
 * how tall it wants to be. Horizontal remains constrained to the
 * scrollable's width (no horizontal scroll for now).
 */
public class Scrollable extends Component {

    private final Theme theme;
    private Component child;
    private float scrollY = 0;
    private float contentHeightPx;

    public Scrollable(Theme theme) {
        this.theme = theme;
    }

    public Scrollable setChild(Component c) {
        this.child = c;
        if (c != null) c.setParent(this);
        invalidate();
        return this;
    }

    public Component child() { return child; }

    @Override
    public Size measure(Constraints c, GuiContext ctx) {
        return new Size(c.clampW(c.maxW()), c.clampH(c.maxH()));
    }

    @Override
    public void arrange(Bounds b, GuiContext ctx) {
        super.arrange(b, ctx);
        if (child != null) {
            Size childSize = child.measure(
                    new Constraints(b.w(), b.w(), 0, Float.MAX_VALUE), ctx);
            contentHeightPx = childSize.h();
            float maxScroll = Math.max(0, contentHeightPx - b.h());
            if (scrollY > maxScroll) scrollY = maxScroll;
            if (scrollY < 0) scrollY = 0;
            child.arrange(new Bounds(b.x(), b.y() - scrollY, b.w(), contentHeightPx), ctx);
        }
    }

    @Override
    public void paint(List<GuiCommand> out, GuiContext ctx) {
        float w = bounds().w();
        float h = bounds().h();

        out.add(new GuiCommand.PushClip(0, 0, w, h));
        if (child != null) {
            Bounds cb = child.bounds();
            out.add(new GuiCommand.PushOffset(cb.x() - bounds().x(), cb.y() - bounds().y()));
            child.paint(out, ctx);
            out.add(new GuiCommand.PopOffset());
        }
        out.add(new GuiCommand.PopClip());

        if (contentHeightPx > h) {
            float sbW = ctx.rem(theme.scrollbarWidthRem);
            float sbX = w - sbW;
            out.add(new GuiCommand.Draw(new DrawCommand.SetFill(
                    theme.scrollbarTrackR, theme.scrollbarTrackG, theme.scrollbarTrackB)));
            out.add(new GuiCommand.Draw(new DrawCommand.FillRect(sbX, 0, sbW, h)));

            float thumbH = Math.max(sbW, h * (h / contentHeightPx));
            float thumbY = (contentHeightPx - h) > 0
                    ? scrollY * (h - thumbH) / (contentHeightPx - h)
                    : 0;
            out.add(new GuiCommand.Draw(new DrawCommand.SetFill(
                    theme.scrollbarThumbR, theme.scrollbarThumbG, theme.scrollbarThumbB)));
            out.add(new GuiCommand.Draw(new DrawCommand.FillRect(sbX, thumbY, sbW, thumbH)));
        }
        clearDirty();
    }

    @Override
    public boolean onEvent(GuiEvent e) {
        if (e instanceof GuiEvent.Scroll s) {
            scrollY -= (float) s.yOff() * theme.scrollWheelStepPx;
            if (scrollY < 0) scrollY = 0;
            float maxScroll = Math.max(0, contentHeightPx - bounds().h());
            if (scrollY > maxScroll) scrollY = maxScroll;
            invalidate();
            return true;
        }
        return false;
    }

    @Override
    public Component hitTest(float px, float py) {
        if (!bounds().contains(px, py)) return null;
        if (child != null) {
            Component hit = child.hitTest(px, py);
            if (hit != null) return hit;
        }
        return this;
    }

    @Override
    public void collectTabOrder(List<Component> out) {
        if (child != null) child.collectTabOrder(out);
    }
}
