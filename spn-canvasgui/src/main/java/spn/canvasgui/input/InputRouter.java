package spn.canvasgui.input;

import spn.canvasgui.component.Component;
import spn.stdui.input.InputEvent;
import spn.stdui.input.Key;

import java.util.List;

/**
 * Routes platform {@link InputEvent}s to the component tree.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Hit-test via the root and route mouse events to the deepest hit target,
 *       bubbling up parents until consumed.</li>
 *   <li>Track the hover target and synthesize {@code ENTER}/{@code EXIT} transitions.</li>
 *   <li>Track the press target for strict-click semantics: a {@code CLICK} is
 *       fired only if release happens on the same component the press started on,
 *       with the pointer never having exited it in between.</li>
 *   <li>Deliver keyboard events to the focused component, bubbling up.</li>
 *   <li>Handle Tab / Shift-Tab focus navigation via {@link FocusManager}.</li>
 * </ul>
 */
public final class InputRouter {

    private final FocusManager focus;
    private Component hoverTarget;
    private Component pressTarget;
    private boolean pointerLeftPressTarget;
    private double lastX, lastY;

    public InputRouter(FocusManager focus) {
        this.focus = focus;
    }

    public void dispatch(Component root, InputEvent e) {
        switch (e) {
            case InputEvent.MouseMove m -> onMove(root, m.x(), m.y());
            case InputEvent.MousePress p -> onPress(root, p);
            case InputEvent.MouseRelease r -> onRelease(root, r);
            case InputEvent.MouseScroll s -> onScroll(root, s);
            case InputEvent.MouseEnter ignored -> { /* window-level; no-op for now */ }
            case InputEvent.KeyPress k -> onKeyPress(root, k);
            case InputEvent.KeyRepeat k -> deliverKey(focus.current(), new GuiEvent.KeyDown(k.key(), k.mods(), true));
            case InputEvent.KeyRelease k -> deliverKey(focus.current(), new GuiEvent.KeyUp(k.key(), k.mods()));
            case InputEvent.CharInput c -> deliverKey(focus.current(), new GuiEvent.Char(c.codepoint()));
        }
    }

    private void onMove(Component root, double x, double y) {
        lastX = x; lastY = y;

        // Mouse capture: while pressed, MOVE events go to the press target
        // (in its local coords) regardless of what's under the pointer.
        // This is what enables drag semantics for sliders, scrollbars, etc.
        if (pressTarget != null) {
            Component hit = root.hitTest((float) x, (float) y);
            if (hit != pressTarget) pointerLeftPressTarget = true;
            bubble(pressTarget, new GuiEvent.Pointer(GuiEvent.PointerPhase.MOVE,
                    localX(pressTarget, x), localY(pressTarget, y), 0, 0));
            return;
        }

        Component hit = root.hitTest((float) x, (float) y);
        if (hit != hoverTarget) {
            if (hoverTarget != null) {
                hoverTarget.setHovered(false);
                bubble(hoverTarget, new GuiEvent.Pointer(GuiEvent.PointerPhase.EXIT,
                        localX(hoverTarget, x), localY(hoverTarget, y), 0, 0));
            }
            hoverTarget = hit;
            if (hoverTarget != null) {
                hoverTarget.setHovered(true);
                bubble(hoverTarget, new GuiEvent.Pointer(GuiEvent.PointerPhase.ENTER,
                        localX(hoverTarget, x), localY(hoverTarget, y), 0, 0));
            }
        }
        if (hit != null) {
            bubble(hit, new GuiEvent.Pointer(GuiEvent.PointerPhase.MOVE,
                    localX(hit, x), localY(hit, y), 0, 0));
        }
    }

    private void onPress(Component root, InputEvent.MousePress p) {
        Component hit = root.hitTest((float) p.x(), (float) p.y());
        pressTarget = hit;
        pointerLeftPressTarget = false;
        if (hit != null) {
            hit.setPressed(true);
            if (hit.focusable()) focus.setFocus(hit);
            bubble(hit, new GuiEvent.Pointer(GuiEvent.PointerPhase.PRESS,
                    localX(hit, p.x()), localY(hit, p.y()), p.button(), p.mods()));
        }
    }

    private void onRelease(Component root, InputEvent.MouseRelease r) {
        Component hit = root.hitTest((float) r.x(), (float) r.y());
        if (pressTarget != null) {
            pressTarget.setPressed(false);
            bubble(pressTarget, new GuiEvent.Pointer(GuiEvent.PointerPhase.RELEASE,
                    localX(pressTarget, r.x()), localY(pressTarget, r.y()), r.button(), r.mods()));
            boolean strict = !pointerLeftPressTarget && hit == pressTarget;
            if (strict) {
                bubble(pressTarget, new GuiEvent.Pointer(GuiEvent.PointerPhase.CLICK,
                        localX(pressTarget, r.x()), localY(pressTarget, r.y()), r.button(), r.mods()));
            }
        }
        pressTarget = null;
        pointerLeftPressTarget = false;
    }

    private void onScroll(Component root, InputEvent.MouseScroll s) {
        Component hit = root.hitTest((float) lastX, (float) lastY);
        if (hit != null) {
            bubble(hit, new GuiEvent.Scroll(localX(hit, lastX), localY(hit, lastY),
                    s.xOff(), s.yOff()));
        }
    }

    private void onKeyPress(Component root, InputEvent.KeyPress k) {
        if (k.key() == Key.TAB) {
            List<Component> order = focus.collect(root);
            if ((k.mods() & spn.stdui.input.Mod.SHIFT) != 0) focus.prev(order);
            else focus.next(order);
            return;
        }
        deliverKey(focus.current(), new GuiEvent.KeyDown(k.key(), k.mods(), false));
    }

    private void deliverKey(Component target, GuiEvent e) {
        if (target != null) bubble(target, e);
    }

    private static void bubble(Component target, GuiEvent e) {
        Component c = target;
        while (c != null) {
            if (c.onEvent(e)) return;
            c = c.parent();
        }
    }

    private static float localX(Component c, double x) {
        return (float) x - c.bounds().x();
    }

    private static float localY(Component c, double y) {
        return (float) y - c.bounds().y();
    }
}
