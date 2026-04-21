package spn.canvasgui.component;

import spn.canvasgui.cmd.GuiCommand;
import spn.canvasgui.input.GuiEvent;
import spn.canvasgui.unit.GuiContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for all GUI components. Open (not sealed) so consumers can add
 * their own widgets by composing commands, text, and input events.
 *
 * <p>Layout is two-pass: {@link #measure} returns a preferred size given
 * constraints; {@link #arrange} assigns a final rectangle. After arrange,
 * {@link #bounds()} is the inspectable pixel location.
 *
 * <p>Paint emits {@link GuiCommand}s into a caller-provided list. Coordinates
 * are in the component's own local space (origin = top-left of its bounds);
 * the surrounding paint loop wraps each child in {@code PushOffset}/{@code PopOffset}.
 */
public abstract class Component {

    private Component parent;
    private Bounds bounds = Bounds.ZERO;
    private boolean focused;
    private boolean hovered;
    private boolean pressed;
    private boolean dirty = true;

    /** Components marked focusable participate in tab navigation. */
    public boolean focusable() { return false; }

    public Component parent() { return parent; }

    public void setParent(Component parent) { this.parent = parent; }

    public Bounds bounds() { return bounds; }

    public boolean focused() { return focused; }

    public boolean hovered() { return hovered; }

    public boolean pressed() { return pressed; }

    public void setFocused(boolean v) {
        if (focused != v) { focused = v; invalidate(); }
    }

    public void setHovered(boolean v) {
        if (hovered != v) { hovered = v; invalidate(); }
    }

    public void setPressed(boolean v) {
        if (pressed != v) { pressed = v; invalidate(); }
    }

    /** Mark this component (and its ancestors) as needing re-paint. */
    public void invalidate() {
        dirty = true;
        if (parent != null) parent.invalidate();
    }

    public boolean dirty() { return dirty; }

    protected void clearDirty() { dirty = false; }

    /** Return preferred size given the constraints. */
    public abstract Size measure(Constraints c, GuiContext ctx);

    /** Assign final bounds. Default stores the rect; containers recurse into children. */
    public void arrange(Bounds b, GuiContext ctx) {
        if (!b.equals(this.bounds)) {
            this.bounds = b;
            invalidate();
        }
    }

    /** Emit paint commands in local coordinates (origin = top-left of bounds). */
    public abstract void paint(List<GuiCommand> out, GuiContext ctx);

    /**
     * Handle a routed event. Return {@code true} to consume (stops bubble).
     * Default: ignore. Event coordinates are component-local.
     */
    public boolean onEvent(GuiEvent e) { return false; }

    /** Hit test in parent coordinates — child implementations may override to pass through. */
    public Component hitTest(float px, float py) {
        return bounds.contains(px, py) ? this : null;
    }

    /** Collect this component and all descendants into the list, in tab order. */
    public void collectTabOrder(List<Component> out) {
        if (focusable()) out.add(this);
    }

    protected static List<Component> childList() { return new ArrayList<>(); }
}
