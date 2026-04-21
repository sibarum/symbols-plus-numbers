package spn.canvasgui.layout;

import spn.canvasgui.cmd.GuiCommand;
import spn.canvasgui.component.Bounds;
import spn.canvasgui.component.Component;
import spn.canvasgui.component.Constraints;
import spn.canvasgui.component.Size;
import spn.canvasgui.unit.GuiContext;

import java.util.ArrayList;
import java.util.List;

/** Vertical sibling of {@link HBox}. Children stack top-to-bottom. */
public class VBox extends Component {

    private final List<Component> children = new ArrayList<>();
    private final List<Float> flex = new ArrayList<>();
    private float gapRem = 0;

    public VBox add(Component child) { return add(child, 0f); }

    public VBox add(Component child, float flexWeight) {
        children.add(child);
        flex.add(flexWeight);
        child.setParent(this);
        invalidate();
        return this;
    }

    public VBox setGapRem(float g) { this.gapRem = g; invalidate(); return this; }

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
        float fixedH = 0;
        float maxW = 0;
        Size[] measured = new Size[children.size()];
        for (int i = 0; i < children.size(); i++) {
            float f = flex.get(i);
            if (f == 0f) {
                Size s = children.get(i).measure(Constraints.loose(c.maxW(), c.maxH()), ctx);
                measured[i] = s;
                fixedH += s.h();
                maxW = Math.max(maxW, s.w());
            } else {
                totalFlex += f;
            }
        }
        fixedH += gap * Math.max(0, children.size() - 1);
        if (totalFlex > 0) {
            float remaining = Math.max(0, c.maxH() - fixedH);
            for (int i = 0; i < children.size(); i++) {
                float f = flex.get(i);
                if (f > 0f) {
                    float h = remaining * (f / totalFlex);
                    Size s = children.get(i).measure(Constraints.tight(c.maxW(), h), ctx);
                    measured[i] = s;
                    maxW = Math.max(maxW, s.w());
                }
            }
        }
        float totalH = fixedH;
        for (int i = 0; i < children.size(); i++) {
            float f = flex.get(i);
            if (f > 0f) totalH += measured[i].h();
        }
        return new Size(c.clampW(maxW), c.clampH(totalH));
    }

    @Override
    public void arrange(Bounds b, GuiContext ctx) {
        super.arrange(b, ctx);
        float gap = ctx.rem(gapRem);
        float totalFlex = 0;
        float fixedH = 0;
        float[] heights = new float[children.size()];
        for (int i = 0; i < children.size(); i++) {
            float f = flex.get(i);
            if (f == 0f) {
                Size s = children.get(i).measure(Constraints.loose(b.w(), b.h()), ctx);
                heights[i] = s.h();
                fixedH += s.h();
            } else {
                totalFlex += f;
            }
        }
        fixedH += gap * Math.max(0, children.size() - 1);
        float remaining = Math.max(0, b.h() - fixedH);
        for (int i = 0; i < children.size(); i++) {
            float f = flex.get(i);
            if (f > 0f) heights[i] = remaining * (f / totalFlex);
        }
        float cursor = b.y();
        for (int i = 0; i < children.size(); i++) {
            Bounds cb = new Bounds(b.x(), cursor, b.w(), heights[i]);
            children.get(i).arrange(cb, ctx);
            cursor += heights[i] + gap;
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
