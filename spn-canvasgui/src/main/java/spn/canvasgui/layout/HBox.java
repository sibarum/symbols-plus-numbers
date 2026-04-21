package spn.canvasgui.layout;

import spn.canvasgui.cmd.GuiCommand;
import spn.canvasgui.component.Bounds;
import spn.canvasgui.component.Component;
import spn.canvasgui.component.Constraints;
import spn.canvasgui.component.Size;
import spn.canvasgui.unit.GuiContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal box layout. Children with {@code flex == 0} size to their
 * preferred width; remaining space is divided among flex children
 * proportionally to their weights.
 */
public class HBox extends Component {

    private final List<Component> children = new ArrayList<>();
    private final List<Float> flex = new ArrayList<>();
    private float gapRem = 0;

    public HBox add(Component child) { return add(child, 0f); }

    public HBox add(Component child, float flexWeight) {
        children.add(child);
        flex.add(flexWeight);
        child.setParent(this);
        invalidate();
        return this;
    }

    public HBox setGapRem(float g) {
        this.gapRem = g;
        invalidate();
        return this;
    }

    public List<Component> children() { return children; }

    /** Replace child at {@code idx} with {@code newChild}, or remove if null. */
    public void replaceChild(int idx, Component newChild) {
        if (newChild == null) {
            children.remove(idx);
            flex.remove(idx);
        } else {
            children.set(idx, newChild);
            newChild.setParent(this);
        }
        invalidate();
    }

    @Override
    public Size measure(Constraints c, GuiContext ctx) {
        float gap = ctx.rem(gapRem);
        float totalFlex = 0;
        float fixedW = 0;
        float maxH = 0;
        Size[] measured = new Size[children.size()];
        for (int i = 0; i < children.size(); i++) {
            float f = flex.get(i);
            if (f == 0f) {
                Size s = children.get(i).measure(Constraints.loose(c.maxW(), c.maxH()), ctx);
                measured[i] = s;
                fixedW += s.w();
                maxH = Math.max(maxH, s.h());
            } else {
                totalFlex += f;
            }
        }
        fixedW += gap * Math.max(0, children.size() - 1);
        if (totalFlex > 0) {
            float remaining = Math.max(0, c.maxW() - fixedW);
            for (int i = 0; i < children.size(); i++) {
                float f = flex.get(i);
                if (f > 0f) {
                    float w = remaining * (f / totalFlex);
                    Size s = children.get(i).measure(Constraints.tight(w, c.maxH()), ctx);
                    measured[i] = s;
                    maxH = Math.max(maxH, s.h());
                }
            }
        }
        float totalW = fixedW;
        for (int i = 0; i < children.size(); i++) {
            float f = flex.get(i);
            if (f > 0f) totalW += measured[i].w();
        }
        return new Size(c.clampW(totalW), c.clampH(maxH));
    }

    @Override
    public void arrange(Bounds b, GuiContext ctx) {
        super.arrange(b, ctx);
        float gap = ctx.rem(gapRem);
        float totalFlex = 0;
        float fixedW = 0;
        float[] widths = new float[children.size()];
        for (int i = 0; i < children.size(); i++) {
            float f = flex.get(i);
            if (f == 0f) {
                Size s = children.get(i).measure(Constraints.loose(b.w(), b.h()), ctx);
                widths[i] = s.w();
                fixedW += s.w();
            } else {
                totalFlex += f;
            }
        }
        fixedW += gap * Math.max(0, children.size() - 1);
        float remaining = Math.max(0, b.w() - fixedW);
        for (int i = 0; i < children.size(); i++) {
            float f = flex.get(i);
            if (f > 0f) widths[i] = remaining * (f / totalFlex);
        }
        float cursor = b.x();
        for (int i = 0; i < children.size(); i++) {
            Bounds cb = new Bounds(cursor, b.y(), widths[i], b.h());
            children.get(i).arrange(cb, ctx);
            cursor += widths[i] + gap;
        }
    }

    @Override
    public void paint(List<GuiCommand> out, GuiContext ctx) {
        for (Component child : children) {
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
        for (Component child : children) {
            Component hit = child.hitTest(px, py);
            if (hit != null) return hit;
        }
        return this;
    }

    @Override
    public void collectTabOrder(List<Component> out) {
        for (Component child : children) child.collectTabOrder(out);
    }
}
