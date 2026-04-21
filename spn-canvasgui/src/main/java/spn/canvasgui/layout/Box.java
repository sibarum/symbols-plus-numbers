package spn.canvasgui.layout;

import spn.canvas.DrawCommand;
import spn.canvasgui.cmd.GuiCommand;
import spn.canvasgui.component.Bounds;
import spn.canvasgui.component.Component;
import spn.canvasgui.component.Constraints;
import spn.canvasgui.component.Insets;
import spn.canvasgui.component.Size;
import spn.canvasgui.unit.GuiContext;

import java.util.List;

/**
 * Single-child decorator providing margin / padding / border / background.
 * All sizes are in rem.
 *
 * <p>Layout (border-box): outer bounds = full assigned rect. Content rect is
 * the outer rect shrunk by margin, then by border width on all sides, then
 * by padding. The child gets the content rect.
 *
 * <p>Paint: background fills the post-margin rect (so margin is true outside
 * spacing). Border draws as a frame inside the bg. Then child paints in
 * content-relative coordinates.
 */
public class Box extends Component {

    private Component child;
    private Insets marginRem = Insets.ZERO;
    private Insets paddingRem = Insets.ZERO;
    private float borderRem;
    private float borderR, borderG, borderB;
    private boolean hasBorder;
    private float bgR, bgG, bgB;
    private boolean hasBg;

    public Box setChild(Component c) {
        this.child = c;
        if (c != null) c.setParent(this);
        invalidate();
        return this;
    }

    public Component child() { return child; }

    public Box setMarginRem(Insets m) { this.marginRem = m; invalidate(); return this; }
    public Box setPaddingRem(Insets p) { this.paddingRem = p; invalidate(); return this; }

    public Box setBorder(float widthRem, float r, float g, float b) {
        this.borderRem = widthRem;
        this.borderR = r; this.borderG = g; this.borderB = b;
        this.hasBorder = widthRem > 0;
        invalidate();
        return this;
    }

    public Box setBg(float r, float g, float b) {
        this.bgR = r; this.bgG = g; this.bgB = b;
        this.hasBg = true;
        invalidate();
        return this;
    }

    public Box clearBg() { this.hasBg = false; invalidate(); return this; }

    public Insets marginRem()  { return marginRem; }
    public Insets paddingRem() { return paddingRem; }
    public float borderRem()   { return borderRem; }
    public boolean hasBorder() { return hasBorder; }
    public boolean hasBg()     { return hasBg; }

    @Override
    public Size measure(Constraints c, GuiContext ctx) {
        float ph = ctx.rem(marginRem.horizontal()) + ctx.rem(borderRem) * 2 + ctx.rem(paddingRem.horizontal());
        float pv = ctx.rem(marginRem.vertical())   + ctx.rem(borderRem) * 2 + ctx.rem(paddingRem.vertical());
        Constraints inner = new Constraints(
                Math.max(0, c.minW() - ph),
                Math.max(0, c.maxW() - ph),
                Math.max(0, c.minH() - pv),
                Math.max(0, c.maxH() - pv));
        Size content = child != null ? child.measure(inner, ctx) : Size.ZERO;
        return new Size(c.clampW(content.w() + ph), c.clampH(content.h() + pv));
    }

    @Override
    public void arrange(Bounds outer, GuiContext ctx) {
        super.arrange(outer, ctx);
        if (child == null) return;
        Bounds content = contentBounds(outer, ctx);
        child.arrange(content, ctx);
    }

    private Bounds contentBounds(Bounds outer, GuiContext ctx) {
        float ml = ctx.rem(marginRem.left()) + ctx.rem(borderRem) + ctx.rem(paddingRem.left());
        float mt = ctx.rem(marginRem.top())  + ctx.rem(borderRem) + ctx.rem(paddingRem.top());
        float mr = ctx.rem(marginRem.right()) + ctx.rem(borderRem) + ctx.rem(paddingRem.right());
        float mb = ctx.rem(marginRem.bottom()) + ctx.rem(borderRem) + ctx.rem(paddingRem.bottom());
        return new Bounds(outer.x() + ml, outer.y() + mt,
                Math.max(0, outer.w() - ml - mr),
                Math.max(0, outer.h() - mt - mb));
    }

    @Override
    public void paint(List<GuiCommand> out, GuiContext ctx) {
        // Compute the post-margin rect in local coordinates (origin = top-left of bounds()).
        float ml = ctx.rem(marginRem.left());
        float mt = ctx.rem(marginRem.top());
        float mr = ctx.rem(marginRem.right());
        float mb = ctx.rem(marginRem.bottom());
        float boxX = ml;
        float boxY = mt;
        float boxW = Math.max(0, bounds().w() - ml - mr);
        float boxH = Math.max(0, bounds().h() - mt - mb);

        if (hasBg) {
            out.add(new GuiCommand.Draw(new DrawCommand.SetFill(bgR, bgG, bgB)));
            out.add(new GuiCommand.Draw(new DrawCommand.FillRect(boxX, boxY, boxW, boxH)));
        }
        if (hasBorder) {
            float bw = ctx.rem(borderRem);
            out.add(new GuiCommand.Draw(new DrawCommand.SetFill(borderR, borderG, borderB)));
            out.add(new GuiCommand.Draw(new DrawCommand.FillRect(boxX, boxY, boxW, bw)));
            out.add(new GuiCommand.Draw(new DrawCommand.FillRect(boxX, boxY + boxH - bw, boxW, bw)));
            out.add(new GuiCommand.Draw(new DrawCommand.FillRect(boxX, boxY, bw, boxH)));
            out.add(new GuiCommand.Draw(new DrawCommand.FillRect(boxX + boxW - bw, boxY, bw, boxH)));
        }

        if (child != null) {
            Bounds cb = child.bounds();
            out.add(new GuiCommand.PushOffset(cb.x() - bounds().x(), cb.y() - bounds().y()));
            child.paint(out, ctx);
            out.add(new GuiCommand.PopOffset());
        }
        clearDirty();
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
