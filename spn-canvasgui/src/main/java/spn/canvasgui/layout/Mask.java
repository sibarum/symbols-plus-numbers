package spn.canvasgui.layout;

import spn.canvasgui.cmd.GuiCommand;
import spn.canvasgui.component.Bounds;
import spn.canvasgui.component.Component;
import spn.canvasgui.component.Constraints;
import spn.canvasgui.component.Size;
import spn.canvasgui.unit.GuiContext;

import java.util.List;

/**
 * Single-child container that clips its child to a fixed rectangular size
 * via {@code PushClip}/{@code PopClip} (lowered to GL scissor). The child
 * is given the mask's full size as both min and max — overflow is silently
 * trimmed at paint time.
 *
 * <p>Clipped child still measures and arranges normally; only the visible
 * pixels are constrained.
 */
public class Mask extends Component {

    private Component child;
    private float widthRem;
    private float heightRem;

    public Mask(float widthRem, float heightRem) {
        this.widthRem = widthRem;
        this.heightRem = heightRem;
    }

    public Mask setChild(Component c) {
        this.child = c;
        if (c != null) c.setParent(this);
        invalidate();
        return this;
    }

    public Mask setSizeRem(float w, float h) {
        this.widthRem = w;
        this.heightRem = h;
        invalidate();
        return this;
    }

    @Override
    public Size measure(Constraints c, GuiContext ctx) {
        float w = ctx.rem(widthRem);
        float h = ctx.rem(heightRem);
        return new Size(c.clampW(w), c.clampH(h));
    }

    @Override
    public void arrange(Bounds b, GuiContext ctx) {
        super.arrange(b, ctx);
        if (child != null) child.arrange(b, ctx);
    }

    @Override
    public void paint(List<GuiCommand> out, GuiContext ctx) {
        if (child == null) { clearDirty(); return; }
        out.add(new GuiCommand.PushClip(0, 0, bounds().w(), bounds().h()));
        child.paint(out, ctx);
        out.add(new GuiCommand.PopClip());
        clearDirty();
    }

    @Override
    public Component hitTest(float px, float py) {
        if (!bounds().contains(px, py)) return null;
        return child != null ? child.hitTest(px, py) : this;
    }

    @Override
    public void collectTabOrder(List<Component> out) {
        if (child != null) child.collectTabOrder(out);
    }
}
