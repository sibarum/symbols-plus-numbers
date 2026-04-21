package spn.canvasgui.loop;

import spn.canvas.CanvasRenderer;
import spn.canvasgui.cmd.GuiCommand;
import spn.canvasgui.cmd.GuiPaintRenderer;
import spn.canvasgui.component.Bounds;
import spn.canvasgui.component.Component;
import spn.canvasgui.component.Constraints;
import spn.canvasgui.font.FontRegistry;
import spn.canvasgui.input.FocusManager;
import spn.canvasgui.input.InputRouter;
import spn.canvasgui.unit.GuiContext;
import spn.stdui.input.InputEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Owns the component tree for a single window. Orchestrates layout, paint,
 * and input dispatch each frame.
 */
public final class GuiRoot {

    private final GuiContext ctx;
    private final FocusManager focus = new FocusManager();
    private final InputRouter router = new InputRouter(focus);
    private final GuiPaintRenderer paintRenderer;
    private final Queue<InputEvent> pendingInputs = new ConcurrentLinkedQueue<>();
    private Component content;
    private boolean layoutValid;

    public GuiRoot(FontRegistry fonts, float remPx, CanvasRenderer canvasRenderer) {
        this.ctx = new GuiContext(fonts, remPx);
        this.paintRenderer = new GuiPaintRenderer(canvasRenderer, fonts);
    }

    public GuiContext ctx() { return ctx; }

    public FocusManager focus() { return focus; }

    public GuiRoot setContent(Component c) {
        this.content = c;
        this.layoutValid = false;
        return this;
    }

    public Component content() { return content; }

    public void postInput(InputEvent e) { pendingInputs.add(e); }

    public void dispatchInputs() {
        if (content == null) return;
        InputEvent e;
        while ((e = pendingInputs.poll()) != null) {
            router.dispatch(content, e);
        }
    }

    public void layout(int viewportW, int viewportH) {
        if (content == null) return;
        ctx.setViewport(viewportW, viewportH);
        if (!layoutValid || content.dirty()) {
            content.measure(Constraints.loose(viewportW, viewportH), ctx);
            content.arrange(new Bounds(0, 0, viewportW, viewportH), ctx);
            layoutValid = true;
        }
    }

    public void invalidateLayout() {
        layoutValid = false;
        if (content != null) content.invalidate();
    }

    /** Paint the tree via the multi-font paint renderer. */
    public void paint(int viewportW, int viewportH) {
        if (content == null) return;
        List<GuiCommand> gui = new ArrayList<>(64);
        content.paint(gui, ctx);
        paintRenderer.render(gui, viewportW, viewportH);
    }
}
