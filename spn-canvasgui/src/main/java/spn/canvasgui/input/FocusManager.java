package spn.canvasgui.input;

import spn.canvasgui.component.Component;

import java.util.ArrayList;
import java.util.List;

/** Tracks the currently focused component and tab-order traversal. */
public final class FocusManager {

    private Component current;

    public Component current() { return current; }

    public void setFocus(Component c) {
        if (current == c) return;
        if (current != null) {
            current.setFocused(false);
            current.onEvent(new GuiEvent.Focus(false));
        }
        current = c;
        if (current != null) {
            current.setFocused(true);
            current.onEvent(new GuiEvent.Focus(true));
        }
    }

    public List<Component> collect(Component root) {
        List<Component> out = new ArrayList<>();
        root.collectTabOrder(out);
        return out;
    }

    public void next(List<Component> order) {
        if (order.isEmpty()) return;
        int idx = order.indexOf(current);
        idx = (idx + 1) % order.size();
        setFocus(order.get(idx));
    }

    public void prev(List<Component> order) {
        if (order.isEmpty()) return;
        int idx = order.indexOf(current);
        if (idx < 0) idx = 0;
        idx = (idx - 1 + order.size()) % order.size();
        setFocus(order.get(idx));
    }
}
