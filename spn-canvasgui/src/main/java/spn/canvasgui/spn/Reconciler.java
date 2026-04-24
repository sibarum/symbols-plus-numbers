package spn.canvasgui.spn;

import com.oracle.truffle.api.CallTarget;
import spn.canvasgui.component.Component;
import spn.canvasgui.layout.Box;
import spn.canvasgui.layout.Grid;
import spn.canvasgui.layout.HBox;
import spn.canvasgui.layout.Mask;
import spn.canvasgui.layout.Scrollable;
import spn.canvasgui.layout.Spacer;
import spn.canvasgui.layout.VBox;
import spn.canvasgui.theme.Theme;
import spn.canvasgui.widget.Button;
import spn.canvasgui.widget.Canvas;
import spn.canvasgui.widget.Dial;
import spn.canvasgui.widget.Slider;
import spn.canvasgui.widget.Tabs;
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
                Button btn = new Button(b.label(), theme).setSelected(b.selected());
                bindHandlers(btn, b.handlers());
                yield btn;
            }
            case GuiCmd.Text t -> {
                Text txt = new Text(t.content(), theme);
                txt.setEditable(t.editable());
                txt.setSelectable(t.selectable());
                txt.setMultiline(t.multiline());
                txt.setWordWrap(t.wordWrap());
                txt.setFontSymbol(t.font());
                txt.setBold(t.bold());
                txt.setItalic(t.italic());
                txt.setLineHeightMult(t.lineHeight());
                bindTextHandlers(txt, t.handlers());
                yield txt;
            }
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
            case GuiCmd.Grid g -> {
                Grid grid = new Grid(g.rows(), g.cols());
                for (GuiCmd child : g.children()) grid.add(build(child));
                yield grid;
            }
            case GuiCmd.Spacer s -> new Spacer();
            case GuiCmd.Slider sl -> {
                Slider slider = new Slider(theme).setRange(sl.min(), sl.max()).setValue(sl.value());
                bindSliderHandlers(slider, sl.handlers());
                yield slider;
            }
            case GuiCmd.Dial d -> {
                Dial dial = new Dial(theme).setRange(d.min(), d.max()).setValue(d.value());
                bindDialHandlers(dial, d.handlers());
                yield dial;
            }
            case GuiCmd.Tabs t -> {
                Tabs tabs = new Tabs(theme).setLabels(t.labels()).setActiveIndex(t.activeIndex());
                if (!t.pages().isEmpty() && t.activeIndex() >= 0 && t.activeIndex() < t.pages().size()) {
                    tabs.setActivePage(build(t.pages().get(t.activeIndex())));
                }
                bindTabsHandlers(tabs, t.handlers());
                yield tabs;
            }
            case GuiCmd.Mask m -> {
                Mask mask = new Mask(m.widthRem(), m.heightRem());
                if (m.child() != null) mask.setChild(build(m.child()));
                yield mask;
            }
            case GuiCmd.Box b -> {
                Box box = new Box();
                applyBoxStyling(box, b);
                if (b.child() != null) box.setChild(build(b.child()));
                yield box;
            }
            case GuiCmd.Scrollable sc -> {
                Scrollable scroll = new Scrollable(theme);
                if (sc.child() != null) scroll.setChild(build(sc.child()));
                yield scroll;
            }
            case GuiCmd.Canvas c -> new Canvas(c.w(), c.h(), c.cmds());
        };
    }

    private static void applyBoxStyling(Box dst, GuiCmd.Box src) {
        dst.setMarginRem(src.marginRem());
        dst.setPaddingRem(src.paddingRem());
        if (src.hasBorder()) dst.setBorder(src.borderRem(),
                src.borderR(), src.borderG(), src.borderB());
        if (src.hasBg()) dst.setBg(src.bgR(), src.bgG(), src.bgB());
        else dst.clearBg();
    }

    /**
     * Update {@code existing} in-place if it matches {@code cmd}'s variant;
     * otherwise build a fresh component. Returns the component to use.
     */
    public Component update(Component existing, GuiCmd cmd) {
        return switch (cmd) {
            case GuiCmd.Button b -> {
                if (existing instanceof Button btn && btn.label().equals(b.label())) {
                    btn.setSelected(b.selected());
                    bindHandlers(btn, b.handlers());
                    yield btn;
                }
                yield build(cmd);
            }
            case GuiCmd.Text t -> {
                if (existing instanceof Text txt) {
                    txt.setText(t.content());
                    txt.setEditable(t.editable());
                    txt.setSelectable(t.selectable());
                    txt.setMultiline(t.multiline());
                    txt.setWordWrap(t.wordWrap());
                    txt.setFontSymbol(t.font());
                    txt.setBold(t.bold());
                    txt.setItalic(t.italic());
                    txt.setLineHeightMult(t.lineHeight());
                    bindTextHandlers(txt, t.handlers());
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
            case GuiCmd.Grid g -> {
                if (existing instanceof Grid grid
                        && grid.rows() == g.rows() && grid.cols() == g.cols()) {
                    updateChildren(grid.children(), g.children(), grid::replaceChild, grid::add);
                    yield grid;
                }
                yield build(cmd);
            }
            case GuiCmd.Spacer s -> existing instanceof Spacer ? existing : build(cmd);
            case GuiCmd.Slider sl -> {
                if (existing instanceof Slider slider) {
                    slider.setRange(sl.min(), sl.max()).setValue(sl.value());
                    bindSliderHandlers(slider, sl.handlers());
                    yield slider;
                }
                yield build(cmd);
            }
            case GuiCmd.Dial d -> {
                if (existing instanceof Dial dial) {
                    dial.setRange(d.min(), d.max()).setValue(d.value());
                    bindDialHandlers(dial, d.handlers());
                    yield dial;
                }
                yield build(cmd);
            }
            case GuiCmd.Tabs t -> {
                if (existing instanceof Tabs tabs) {
                    tabs.setLabels(t.labels());
                    int prevIndex = tabs.activeIndex();
                    tabs.setActiveIndex(t.activeIndex());
                    if (!t.pages().isEmpty() && t.activeIndex() >= 0 && t.activeIndex() < t.pages().size()) {
                        GuiCmd activeCmd = t.pages().get(t.activeIndex());
                        Component newPage = (prevIndex == t.activeIndex() && tabs.activePage() != null)
                                ? update(tabs.activePage(), activeCmd)
                                : build(activeCmd);
                        tabs.setActivePage(newPage);
                    } else {
                        tabs.setActivePage(null);
                    }
                    bindTabsHandlers(tabs, t.handlers());
                    yield tabs;
                }
                yield build(cmd);
            }
            case GuiCmd.Mask m -> {
                if (existing instanceof Mask mask) {
                    mask.setSizeRem(m.widthRem(), m.heightRem());
                    mask.setChild(m.child() != null ? build(m.child()) : null);
                    yield mask;
                }
                yield build(cmd);
            }
            case GuiCmd.Box b -> {
                if (existing instanceof Box box) {
                    applyBoxStyling(box, b);
                    Component newChild = b.child() != null
                            ? (box.child() != null ? update(box.child(), b.child()) : build(b.child()))
                            : null;
                    box.setChild(newChild);
                    yield box;
                }
                yield build(cmd);
            }
            case GuiCmd.Scrollable sc -> {
                if (existing instanceof Scrollable scroll) {
                    Component newChild = sc.child() != null
                            ? (scroll.child() != null ? update(scroll.child(), sc.child()) : build(sc.child()))
                            : null;
                    scroll.setChild(newChild);
                    yield scroll;
                }
                yield build(cmd);
            }
            case GuiCmd.Canvas c -> {
                if (existing instanceof Canvas canvas) {
                    canvas.setSize(c.w(), c.h()).setCmds(c.cmds());
                    yield canvas;
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

    private void bindSliderHandlers(Slider slider, Map<SpnSymbol, CallTarget> handlers) {
        CallTarget change = lookup(handlers, "change");
        if (change != null) {
            CallTarget ct = change;
            slider.onChange(v -> ct.call(v));
        } else {
            slider.onChange(v -> {});
        }
    }

    private void bindDialHandlers(Dial dial, Map<SpnSymbol, CallTarget> handlers) {
        CallTarget change = lookup(handlers, "change");
        if (change != null) {
            CallTarget ct = change;
            dial.onChange(v -> ct.call(v));
        } else {
            dial.onChange(v -> {});
        }
    }

    private void bindTextHandlers(Text txt, Map<SpnSymbol, CallTarget> handlers) {
        CallTarget change = lookup(handlers, "change");
        if (change != null) {
            CallTarget ct = change;
            txt.onChange(v -> ct.call(v));
        } else {
            txt.onChange(v -> {});
        }
    }

    private void bindTabsHandlers(Tabs tabs, Map<SpnSymbol, CallTarget> handlers) {
        CallTarget select = lookup(handlers, "select");
        if (select != null) {
            CallTarget ct = select;
            tabs.setOnSelect(i -> ct.call((long) i));
        } else {
            tabs.setOnSelect(i -> {});
        }
    }

    private static CallTarget lookup(Map<SpnSymbol, CallTarget> handlers, String name) {
        for (var e : handlers.entrySet()) {
            if (e.getKey().name().equals(name)) return e.getValue();
        }
        return null;
    }

    @FunctionalInterface interface ReplaceFn { void replace(int idx, Component c); }
    @FunctionalInterface interface AddFn { void add(Component c); }
}
