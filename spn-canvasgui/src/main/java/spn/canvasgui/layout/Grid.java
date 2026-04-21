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
 * Fixed-shape grid layout. Children fill {@code rows * cols} cells in row-major
 * order; each cell is an even share of the parent's allotted size minus gaps.
 *
 * <p>No per-row/col weights or cell spans yet — they're a follow-up. For now,
 * regular grids (e.g., calculator buttons, swatches, spreadsheets) work cleanly.
 */
public class Grid extends Component {

    private final int rows;
    private final int cols;
    private final List<Component> cells = new ArrayList<>();
    private float gapRem = 0;

    public Grid(int rows, int cols) {
        this.rows = rows;
        this.cols = cols;
    }

    public int rows() { return rows; }
    public int cols() { return cols; }

    public Grid add(Component child) {
        cells.add(child);
        child.setParent(this);
        invalidate();
        return this;
    }

    public Grid setGapRem(float r) {
        this.gapRem = r;
        invalidate();
        return this;
    }

    public List<Component> children() { return cells; }

    /** Replace child at {@code idx} with {@code newChild}, or remove if null. */
    public void replaceChild(int idx, Component newChild) {
        if (newChild == null) {
            cells.remove(idx);
        } else {
            cells.set(idx, newChild);
            newChild.setParent(this);
        }
        invalidate();
    }

    @Override
    public Size measure(Constraints c, GuiContext ctx) {
        // Greedy: take the max we're allowed.
        return new Size(c.clampW(c.maxW()), c.clampH(c.maxH()));
    }

    @Override
    public void arrange(Bounds b, GuiContext ctx) {
        super.arrange(b, ctx);
        float gap = ctx.rem(gapRem);
        float cellW = cols > 0 ? (b.w() - gap * (cols - 1)) / cols : 0;
        float cellH = rows > 0 ? (b.h() - gap * (rows - 1)) / rows : 0;
        int max = rows * cols;
        for (int i = 0; i < cells.size() && i < max; i++) {
            int row = i / cols;
            int col = i % cols;
            float x = b.x() + col * (cellW + gap);
            float y = b.y() + row * (cellH + gap);
            cells.get(i).arrange(new Bounds(x, y, cellW, cellH), ctx);
        }
    }

    @Override
    public void paint(List<GuiCommand> out, GuiContext ctx) {
        for (Component child : cells) {
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
        for (Component child : cells) {
            Component hit = child.hitTest(px, py);
            if (hit != null) return hit;
        }
        return this;
    }

    @Override
    public void collectTabOrder(List<Component> out) {
        for (Component child : cells) child.collectTabOrder(out);
    }
}
