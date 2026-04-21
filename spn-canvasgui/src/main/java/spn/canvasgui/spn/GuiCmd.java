package spn.canvasgui.spn;

import com.oracle.truffle.api.CallTarget;
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

    private static Map<SpnSymbol, CallTarget> merge(Map<SpnSymbol, CallTarget> base,
                                                    SpnSymbol ev, CallTarget h) {
        Map<SpnSymbol, CallTarget> m = new LinkedHashMap<>(base);
        m.put(ev, h);
        return Map.copyOf(m);
    }
}
