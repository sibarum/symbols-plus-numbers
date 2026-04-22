package spn.canvasgui.spn;

import com.oracle.truffle.api.CallTarget;
import spn.canvasgui.component.Insets;
import spn.type.SpnSymbol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable GUI descriptor tree built each frame by pure SPN code.
 * The reconciler diffs this against the live {@code Component} tree.
 *
 * <p>Every variant carries a {@code handlers} map: event symbol →
 * {@link CallTarget}. {@code .on(:sym, fn)} returns a new instance with
 * the handler merged in (last-wins for the same key).
 */
public sealed interface GuiCmd {

    /** Event handlers attached by {@code .on(:event, fn)}. */
    Map<SpnSymbol, CallTarget> handlers();

    /** Return a copy with the given handler added (or replaced). */
    GuiCmd withHandler(SpnSymbol event, CallTarget handler);

    record Button(String label, boolean selected, Map<SpnSymbol, CallTarget> handlers) implements GuiCmd {
        public Button(String label) { this(label, false, Map.of()); }
        public Button withSelected(boolean s) {
            return new Button(label, s, handlers);
        }
        @Override
        public GuiCmd withHandler(SpnSymbol event, CallTarget handler) {
            return new Button(label, selected, merge(handlers, event, handler));
        }
    }

    record Text(String content, boolean editable, boolean selectable,
                boolean multiline, boolean wordWrap, SpnSymbol font,
                boolean bold, boolean italic, float lineHeight,
                Map<SpnSymbol, CallTarget> handlers) implements GuiCmd {
        public Text(String content) {
            this(content, false, false, false, false, null, false, false, 1.0f, Map.of());
        }
        public Text withEditable(boolean e) {
            return new Text(content, e, selectable, multiline, wordWrap, font, bold, italic, lineHeight, handlers);
        }
        public Text withSelectable(boolean s) {
            return new Text(content, editable, s, multiline, wordWrap, font, bold, italic, lineHeight, handlers);
        }
        public Text withMultiline(boolean m) {
            return new Text(content, editable, selectable, m, wordWrap, font, bold, italic, lineHeight, handlers);
        }
        public Text withWordWrap(boolean w) {
            return new Text(content, editable, selectable, multiline, w, font, bold, italic, lineHeight, handlers);
        }
        public Text withFont(SpnSymbol f) {
            return new Text(content, editable, selectable, multiline, wordWrap, f, bold, italic, lineHeight, handlers);
        }
        public Text withBold(boolean b) {
            return new Text(content, editable, selectable, multiline, wordWrap, font, b, italic, lineHeight, handlers);
        }
        public Text withItalic(boolean i) {
            return new Text(content, editable, selectable, multiline, wordWrap, font, bold, i, lineHeight, handlers);
        }
        public Text withLineHeight(float h) {
            return new Text(content, editable, selectable, multiline, wordWrap, font, bold, italic, h, handlers);
        }
        @Override
        public GuiCmd withHandler(SpnSymbol event, CallTarget handler) {
            return new Text(content, editable, selectable, multiline, wordWrap, font, bold, italic, lineHeight,
                    merge(handlers, event, handler));
        }
    }

    record HBox(List<GuiCmd> children, Map<SpnSymbol, CallTarget> handlers) implements GuiCmd {
        public HBox(List<GuiCmd> children) { this(children, Map.of()); }
        @Override
        public GuiCmd withHandler(SpnSymbol event, CallTarget handler) {
            return new HBox(children, merge(handlers, event, handler));
        }
    }

    record VBox(List<GuiCmd> children, Map<SpnSymbol, CallTarget> handlers) implements GuiCmd {
        public VBox(List<GuiCmd> children) { this(children, Map.of()); }
        @Override
        public GuiCmd withHandler(SpnSymbol event, CallTarget handler) {
            return new VBox(children, merge(handlers, event, handler));
        }
    }

    record Grid(int rows, int cols, List<GuiCmd> children,
                Map<SpnSymbol, CallTarget> handlers) implements GuiCmd {
        public Grid(int rows, int cols, List<GuiCmd> children) { this(rows, cols, children, Map.of()); }
        @Override
        public GuiCmd withHandler(SpnSymbol event, CallTarget handler) {
            return new Grid(rows, cols, children, merge(handlers, event, handler));
        }
    }

    record Spacer(Map<SpnSymbol, CallTarget> handlers) implements GuiCmd {
        public Spacer() { this(Map.of()); }
        @Override
        public GuiCmd withHandler(SpnSymbol event, CallTarget handler) {
            return new Spacer(merge(handlers, event, handler));
        }
    }

    record Slider(double min, double max, double value,
                  Map<SpnSymbol, CallTarget> handlers) implements GuiCmd {
        public Slider(double min, double max, double value) { this(min, max, value, Map.of()); }
        @Override
        public GuiCmd withHandler(SpnSymbol event, CallTarget handler) {
            return new Slider(min, max, value, merge(handlers, event, handler));
        }
    }

    record Dial(double min, double max, double value,
                Map<SpnSymbol, CallTarget> handlers) implements GuiCmd {
        public Dial(double min, double max, double value) { this(min, max, value, Map.of()); }
        @Override
        public GuiCmd withHandler(SpnSymbol event, CallTarget handler) {
            return new Dial(min, max, value, merge(handlers, event, handler));
        }
    }

    record Tabs(int activeIndex, List<String> labels, List<GuiCmd> pages,
                Map<SpnSymbol, CallTarget> handlers) implements GuiCmd {
        public Tabs(int activeIndex, List<String> labels, List<GuiCmd> pages) {
            this(activeIndex, labels, pages, Map.of());
        }
        @Override
        public GuiCmd withHandler(SpnSymbol event, CallTarget handler) {
            return new Tabs(activeIndex, labels, pages, merge(handlers, event, handler));
        }
    }

    record Scrollable(GuiCmd child, Map<SpnSymbol, CallTarget> handlers) implements GuiCmd {
        public Scrollable(GuiCmd child) { this(child, Map.of()); }
        @Override
        public GuiCmd withHandler(SpnSymbol event, CallTarget handler) {
            // Routes to child; Scrollable's own scroll handling is internal
            // and doesn't surface as an SPN-level event.
            return new Scrollable(child == null ? null : child.withHandler(event, handler),
                    handlers);
        }
    }

    record Mask(GuiCmd child, float widthRem, float heightRem,
                Map<SpnSymbol, CallTarget> handlers) implements GuiCmd {
        public Mask(GuiCmd child, float w, float h) { this(child, w, h, Map.of()); }
        @Override
        public GuiCmd withHandler(SpnSymbol event, CallTarget handler) {
            // Mask is a clip wrapper with no semantic events — route to child.
            return new Mask(child == null ? null : child.withHandler(event, handler),
                    widthRem, heightRem, handlers);
        }
    }

    /**
     * Single-child decorator with margin / padding / border / background.
     * Created lazily by {@code .margin / .padding / .border / .bg} chained
     * onto any GuiCmd (those methods wrap a non-Box child in a fresh Box).
     */
    record Box(GuiCmd child, Insets marginRem, Insets paddingRem,
               float borderRem, float borderR, float borderG, float borderB, boolean hasBorder,
               float bgR, float bgG, float bgB, boolean hasBg,
               Map<SpnSymbol, CallTarget> handlers) implements GuiCmd {

        public static Box wrap(GuiCmd child) {
            return new Box(child, Insets.ZERO, Insets.ZERO,
                    0, 0, 0, 0, false,
                    0, 0, 0, false,
                    Map.of());
        }

        public Box withMarginRem(Insets m) {
            return new Box(child, m, paddingRem, borderRem,
                    borderR, borderG, borderB, hasBorder,
                    bgR, bgG, bgB, hasBg, handlers);
        }

        public Box withPaddingRem(Insets p) {
            return new Box(child, marginRem, p, borderRem,
                    borderR, borderG, borderB, hasBorder,
                    bgR, bgG, bgB, hasBg, handlers);
        }

        public Box withBorder(float widthRem, float r, float g, float b) {
            return new Box(child, marginRem, paddingRem, widthRem,
                    r, g, b, true,
                    bgR, bgG, bgB, hasBg, handlers);
        }

        public Box withBg(float r, float g, float b) {
            return new Box(child, marginRem, paddingRem, borderRem,
                    borderR, borderG, borderB, hasBorder,
                    r, g, b, true, handlers);
        }

        @Override
        public GuiCmd withHandler(SpnSymbol event, CallTarget handler) {
            // Box is a pure style wrapper; route handlers to the wrapped
            // widget so `guiText(...).padding(0.5).on(:change, h)` attaches
            // `:change` to the Text, not the Box.
            return new Box(child == null ? null : child.withHandler(event, handler),
                    marginRem, paddingRem, borderRem,
                    borderR, borderG, borderB, hasBorder,
                    bgR, bgG, bgB, hasBg, handlers);
        }
    }

    /** Merge styling onto {@code cmd}: if it's already a Box, update it; else wrap fresh. */
    static Box ensureBox(GuiCmd cmd) {
        return cmd instanceof Box b ? b : Box.wrap(cmd);
    }

    private static Map<SpnSymbol, CallTarget> merge(Map<SpnSymbol, CallTarget> base,
                                                    SpnSymbol ev, CallTarget h) {
        Map<SpnSymbol, CallTarget> m = new LinkedHashMap<>(base);
        m.put(ev, h);
        return Map.copyOf(m);
    }
}
