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

    record Button(String label, Map<SpnSymbol, CallTarget> handlers) implements GuiCmd {
        public Button(String label) { this(label, Map.of()); }
        @Override
        public GuiCmd withHandler(SpnSymbol event, CallTarget handler) {
            return new Button(label, merge(handlers, event, handler));
        }
    }

    record Text(String content, Map<SpnSymbol, CallTarget> handlers) implements GuiCmd {
        public Text(String content) { this(content, Map.of()); }
        @Override
        public GuiCmd withHandler(SpnSymbol event, CallTarget handler) {
            return new Text(content, merge(handlers, event, handler));
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

    record Mask(GuiCmd child, float widthRem, float heightRem,
                Map<SpnSymbol, CallTarget> handlers) implements GuiCmd {
        public Mask(GuiCmd child, float w, float h) { this(child, w, h, Map.of()); }
        @Override
        public GuiCmd withHandler(SpnSymbol event, CallTarget handler) {
            return new Mask(child, widthRem, heightRem, merge(handlers, event, handler));
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
            return new Box(child, marginRem, paddingRem, borderRem,
                    borderR, borderG, borderB, hasBorder,
                    bgR, bgG, bgB, hasBg,
                    merge(handlers, event, handler));
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
