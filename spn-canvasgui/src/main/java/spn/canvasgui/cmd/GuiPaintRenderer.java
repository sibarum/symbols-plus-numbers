package spn.canvasgui.cmd;

import spn.canvas.CanvasRenderer;
import spn.canvas.DrawCommand;
import spn.canvasgui.font.FontRegistry;
import spn.fonts.SdfFontRenderer;
import spn.type.SpnSymbol;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glScissor;

/**
 * Drives the full GUI paint pipeline: interprets a {@link GuiCommand} list,
 * dispatches geometric primitives to {@link CanvasRenderer}, and renders
 * text runs directly through their per-run {@link SdfFontRenderer} picked
 * from the {@link FontRegistry}. Clipping is mapped to {@code glScissor}.
 *
 * <p>Replaces {@code Painter.lowerFlat} in {@code GuiRoot.paint}: the flat
 * approach could only ever bind one font, which isn't enough for widgets
 * that mix scripts/weights/faces.
 */
public final class GuiPaintRenderer {

    private final CanvasRenderer canvasRenderer;
    private final FontRegistry fonts;
    private final List<DrawCommand> pendingGeom = new ArrayList<>();

    public GuiPaintRenderer(CanvasRenderer canvasRenderer, FontRegistry fonts) {
        this.canvasRenderer = canvasRenderer;
        this.fonts = fonts;
    }

    public void render(List<GuiCommand> in, int viewportW, int viewportH) {
        OffsetStack offsets = new OffsetStack();
        ClipStack clips = new ClipStack(viewportW, viewportH);
        clips.enable();

        for (GuiCommand cmd : in) {
            switch (cmd) {
                case GuiCommand.Draw d ->
                        pendingGeom.add(translate(d.inner(), offsets.x(), offsets.y()));
                case GuiCommand.PushOffset po -> offsets.push(po.dx(), po.dy());
                case GuiCommand.PopOffset ignored -> offsets.pop();
                case GuiCommand.PushClip pc -> {
                    flushGeom(viewportW, viewportH);
                    clips.push(offsets.x() + pc.x(), offsets.y() + pc.y(),
                            pc.w(), pc.h());
                }
                case GuiCommand.PopClip ignored -> {
                    flushGeom(viewportW, viewportH);
                    clips.pop();
                }
                case GuiCommand.TextRun t -> {
                    flushGeom(viewportW, viewportH);
                    renderTextRun(t, offsets.x(), offsets.y(), viewportW, viewportH);
                }
            }
        }
        flushGeom(viewportW, viewportH);
        clips.disable();
    }

    private void renderTextRun(GuiCommand.TextRun t, float ox, float oy, int vw, int vh) {
        SdfFontRenderer font = t.font();
        if (font == null && fonts != null) font = fonts.getDefault();
        if (font == null) return;

        font.beginText(vw, vh);
        font.drawText(t.text(), ox + t.x(), oy + t.y(), t.scale(),
                t.r(), t.g(), t.b());
        font.endText();

        // beginText/endText flips GL state; restore what geometry expects.
        glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    private void flushGeom(int vw, int vh) {
        if (pendingGeom.isEmpty()) return;
        // Pass font=null: this pipeline renders text via FontRegistry directly,
        // bypassing CanvasRenderer's text path. Any DrawCommand.Text in the
        // geometry list would silently drop, but widgets never emit those —
        // they all go through GuiCommand.TextRun.
        canvasRenderer.replay(pendingGeom, vw, vh, null);
        pendingGeom.clear();
    }

    private static DrawCommand translate(DrawCommand dc, float dx, float dy) {
        return switch (dc) {
            case DrawCommand.FillRect r ->
                    new DrawCommand.FillRect(r.x() + dx, r.y() + dy, r.w(), r.h());
            case DrawCommand.FillCircle c ->
                    new DrawCommand.FillCircle(c.cx() + dx, c.cy() + dy, c.r());
            case DrawCommand.StrokeLine l ->
                    new DrawCommand.StrokeLine(l.x1() + dx, l.y1() + dy,
                            l.x2() + dx, l.y2() + dy);
            case DrawCommand.Text t ->
                    new DrawCommand.Text(t.x() + dx, t.y() + dy, t.text(), t.scale());
            case DrawCommand.Clear c -> c;
            case DrawCommand.SetFill f -> f;
            case DrawCommand.SetStroke s -> s;
            case DrawCommand.SetStrokeWeight w -> w;
        };
    }

    private static final class OffsetStack {
        private final float[] xs = new float[64];
        private final float[] ys = new float[64];
        private int top = 0;
        void push(float dx, float dy) {
            xs[top + 1] = xs[top] + dx;
            ys[top + 1] = ys[top] + dy;
            top++;
        }
        void pop() { if (top > 0) top--; }
        float x() { return xs[top]; }
        float y() { return ys[top]; }
    }

    private static final class ClipStack {
        private final int vw, vh;
        private final float[] xs = new float[32], ys = new float[32],
                ws = new float[32], hs = new float[32];
        private int top = 0;

        ClipStack(int vw, int vh) { this.vw = vw; this.vh = vh; }

        void enable() { glEnable(GL_SCISSOR_TEST); applyFullViewport(); }
        void disable() { glDisable(GL_SCISSOR_TEST); }

        void push(float x, float y, float w, float h) {
            xs[top] = x; ys[top] = y; ws[top] = w; hs[top] = h;
            top++;
            apply();
        }
        void pop() {
            if (top > 0) top--;
            if (top == 0) applyFullViewport(); else apply();
        }

        private void applyFullViewport() { glScissor(0, 0, vw, vh); }

        private void apply() {
            float x0 = 0, y0 = 0, x1 = vw, y1 = vh;
            for (int i = 0; i < top; i++) {
                x0 = Math.max(x0, xs[i]);
                y0 = Math.max(y0, ys[i]);
                x1 = Math.min(x1, xs[i] + ws[i]);
                y1 = Math.min(y1, ys[i] + hs[i]);
            }
            int sx = (int) Math.max(0, Math.floor(x0));
            int sw = (int) Math.max(0, Math.ceil(x1 - x0));
            int sh = (int) Math.max(0, Math.ceil(y1 - y0));
            int sy = Math.max(0, vh - (int) Math.ceil(y1));
            glScissor(sx, sy, sw, sh);
        }
    }
}
