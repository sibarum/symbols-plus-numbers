package spn.canvasgui.loop;

import spn.canvas.CanvasRenderer;
import spn.canvas.DrawCommand;
import spn.canvasgui.cmd.GuiCommand;
import spn.canvasgui.cmd.Painter;
import spn.canvasgui.component.Bounds;
import spn.canvasgui.component.Component;
import spn.canvasgui.component.Constraints;
import spn.canvasgui.input.FocusManager;
import spn.canvasgui.input.InputRouter;
import spn.canvasgui.unit.GuiContext;
import spn.fonts.SdfFontRenderer;
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
    private final CanvasRenderer canvasRenderer;
    private final Queue<InputEvent> pendingInputs = new ConcurrentLinkedQueue<>();
    private Component content;
    private boolean layoutValid;

    public GuiRoot(SdfFontRenderer font, float remPx, CanvasRenderer canvasRenderer) {
        this.ctx = new GuiContext(font, remPx);
        this.canvasRenderer = canvasRenderer;
    }

    public GuiContext ctx() { return ctx; }

    public FocusManager focus() { return focus; }

    public GuiRoot setContent(Component c) {
        this.content = c;
        this.layoutValid = false;
        return this;
    }

    public Component content() { return content; }

    /** Enqueue an input event from any thread; drained on the render thread each frame. */
    public void postInput(InputEvent e) {
        pendingInputs.add(e);
    }

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

    /** Paint the tree, lower to DrawCommands, and replay via CanvasRenderer. */
    public void paint(int viewportW, int viewportH) {
        if (content == null) return;
        List<GuiCommand> gui = new ArrayList<>(64);
        content.paint(gui, ctx);
        List<DrawCommand> draw = Painter.lowerFlat(gui, viewportW, viewportH);
        canvasRenderer.replay(draw, viewportW, viewportH, ctx.font());
        Painter.disableScissor();
    }
}
