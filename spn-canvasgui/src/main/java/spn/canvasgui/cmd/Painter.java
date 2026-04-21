package spn.canvasgui.cmd;

import spn.canvas.DrawCommand;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glScissor;

/**
 * Lowers {@link GuiCommand}s to {@link DrawCommand}s plus direct GL scissor
 * calls for clipping.
 *
 * <p>Lowering is split in two: {@link #lower} produces the {@link DrawCommand}
 * list with all coordinates translated by the cumulative offset stack;
 * {@link #applyClips} drives GL scissor state as the command stream is replayed
 * alongside the draw commands by the caller.
 *
 * <p>For Phase 0 the caller uses {@link #lowerAndReplay} which interleaves
 * clip state changes with deferred draw-command replay.
 */
public final class Painter {

    /**
     * Walk the gui command list and translate it to draw commands, while also
     * managing GL scissor state for clips. This is a single-pass combined driver
     * so scissor rects are active at the correct draw points.
     *
     * <p>The caller is responsible for calling {@code CanvasRenderer.replay} on
     * the returned segments, OR for a simpler model the caller can use
     * {@link #lowerFlat} and accept coarser clipping.
     *
     * <p>Phase 0 uses the simpler {@link #lowerFlat}: all clips resolve to a
     * single GL scissor rectangle covering the intersection, applied once for
     * the duration of the returned draw list. Fine-grained per-command scissor
     * changes can be added later.
     */
    public static List<DrawCommand> lowerFlat(List<GuiCommand> in,
                                              int viewportW, int viewportH) {
        List<DrawCommand> out = new ArrayList<>(in.size());
        OffsetStack offsets = new OffsetStack();
        ClipStack clips = new ClipStack(viewportW, viewportH);
        clips.bind();
        for (GuiCommand cmd : in) {
            switch (cmd) {
                case GuiCommand.PushOffset po -> offsets.push(po.dx(), po.dy());
                case GuiCommand.PopOffset ignored -> offsets.pop();
                case GuiCommand.PushClip pc ->
                        clips.push(offsets.x() + pc.x(), offsets.y() + pc.y(), pc.w(), pc.h());
                case GuiCommand.PopClip ignored -> clips.pop();
                case GuiCommand.Draw d -> out.add(translate(d.inner(), offsets.x(), offsets.y()));
                case GuiCommand.TextRun t -> {
                    out.add(new DrawCommand.SetFill(t.r(), t.g(), t.b()));
                    out.add(new DrawCommand.Text(offsets.x() + t.x(), offsets.y() + t.y(),
                            t.text(), t.scale()));
                }
            }
        }
        return out;
    }

    /** Disable GL scissor test. Call after a frame that used clipping. */
    public static void disableScissor() {
        glDisable(GL_SCISSOR_TEST);
    }

    private static DrawCommand translate(DrawCommand dc, float dx, float dy) {
        return switch (dc) {
            case DrawCommand.FillRect r -> new DrawCommand.FillRect(r.x() + dx, r.y() + dy, r.w(), r.h());
            case DrawCommand.FillCircle c -> new DrawCommand.FillCircle(c.cx() + dx, c.cy() + dy, c.r());
            case DrawCommand.StrokeLine l -> new DrawCommand.StrokeLine(
                    l.x1() + dx, l.y1() + dy, l.x2() + dx, l.y2() + dy);
            case DrawCommand.Text t -> new DrawCommand.Text(t.x() + dx, t.y() + dy, t.text(), t.scale());
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

    /**
     * Clip rectangles in GL scissor coordinates (origin = bottom-left).
     * The viewport height is used to flip Y from the GUI coordinate system.
     * On each push/pop we re-apply the intersection of the current stack.
     */
    private static final class ClipStack {
        private final int vw, vh;
        private final float[] xs = new float[32], ys = new float[32], ws = new float[32], hs = new float[32];
        private int top = 0;

        ClipStack(int vw, int vh) { this.vw = vw; this.vh = vh; }

        void bind() {
            glEnable(GL_SCISSOR_TEST);
            applyFullViewport();
        }

        void push(float x, float y, float w, float h) {
            xs[top] = x; ys[top] = y; ws[top] = w; hs[top] = h;
            top++;
            apply();
        }

        void pop() {
            if (top > 0) top--;
            if (top == 0) applyFullViewport(); else apply();
        }

        private void applyFullViewport() {
            glScissor(0, 0, vw, vh);
        }

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
            // Flip Y: GL scissor origin is bottom-left
            int sy = Math.max(0, vh - (int) Math.ceil(y1));
            glScissor(sx, sy, sw, sh);
        }
    }

    private Painter() {}
}
