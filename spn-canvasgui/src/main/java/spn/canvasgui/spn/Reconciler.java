package spn.canvasgui.spn;

import com.oracle.truffle.api.CallTarget;
import spn.canvasgui.component.Component;
import spn.canvasgui.layout.HBox;
import spn.canvasgui.layout.VBox;
import spn.canvasgui.theme.Theme;
import spn.canvasgui.widget.Button;
import spn.canvasgui.widget.Text;
import spn.type.SpnSymbol;

import java.util.List;
import java.util.Map;

/**
 * Reconciles a {@link GuiCmd} tree against a live {@link Component} tree.
 * Children are matched by position (no explicit keys — user confirmed not needed).
 * Handler wiring is re-established each reconciliation so the latest
 * closure always fires.
 */
public final class Reconciler {

    private final Theme theme;

    public Reconciler(Theme theme) {
        this.theme = theme;
    }

    /** Build a fresh component for the given GuiCmd. */
    public Component build(GuiCmd cmd) {
        return switch (cmd) {
            case GuiCmd.Button b -> {
                Button btn = new Button(b.label(), theme);
                bindHandlers(btn, b.handlers());
                yield btn;
            }
            case GuiCmd.Text t -> new Text(t.content(), theme);
            case GuiCmd.HBox h -> {
                HBox box = new HBox();
                for (GuiCmd child : h.children()) box.add(build(child));
                yield box;
            }
            case GuiCmd.VBox v -> {
                VBox box = new VBox();
                for (GuiCmd child : v.children()) box.add(build(child));
                yield box;
            }
        };
    }

    /**
     * Update {@code existing} in-place if it matches {@code cmd}'s variant;
     * otherwise build a fresh component. Returns the component to use.
     */
    public Component update(Component existing, GuiCmd cmd) {
        return switch (cmd) {
            case GuiCmd.Button b -> {
                if (existing instanceof Button btn && btn.label().equals(b.label())) {
                    bindHandlers(btn, b.handlers());
                    yield btn;
                }
                yield build(cmd);
            }
            case GuiCmd.Text t -> {
                if (existing instanceof Text txt) {
                    txt.setText(t.content());
                    yield txt;
                }
                yield build(cmd);
            }
            case GuiCmd.HBox h -> {
                if (existing instanceof HBox box) {
                    updateChildren(box.children(), h.children(), box::replaceChild, box::add);
                    yield box;
                }
                yield build(cmd);
            }
            case GuiCmd.VBox v -> {
                if (existing instanceof VBox box) {
                    updateChildren(box.children(), v.children(), box::replaceChild, box::add);
                    yield box;
                }
                yield build(cmd);
            }
        };
    }

    private void updateChildren(List<Component> live, List<GuiCmd> next,
                                ReplaceFn replacer, AddFn adder) {
        int n = next.size();
        int m = live.size();
        for (int i = 0; i < n; i++) {
            if (i < m) {
                Component existing = live.get(i);
                Component updated = update(existing, next.get(i));
                if (updated != existing) replacer.replace(i, updated);
            } else {
                adder.add(build(next.get(i)));
            }
        }
        // Trim extras
        while (live.size() > n) replacer.replace(live.size() - 1, null);
    }

    private void bindHandlers(Button btn, Map<SpnSymbol, CallTarget> handlers) {
        CallTarget click = null;
        for (var e : handlers.entrySet()) {
            if (e.getKey().name().equals("click")) click = e.getValue();
        }
        if (click != null) {
            CallTarget ct = click;
            btn.onClick(() -> ct.call());
        } else {
            btn.onClick(() -> {});
        }
    }

    @FunctionalInterface interface ReplaceFn { void replace(int idx, Component c); }
    @FunctionalInterface interface AddFn { void add(Component c); }
}
