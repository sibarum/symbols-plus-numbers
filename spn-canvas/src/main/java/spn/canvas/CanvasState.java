package spn.canvas;

import com.oracle.truffle.api.CallTarget;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-local accumulator for canvas state during SPN execution.
 *
 * Drawing nodes append {@link DrawCommand}s here. After SPN execution returns,
 * the host reads back the buffered state to create the canvas window and replay
 * the commands into OpenGL.
 */
public final class CanvasState {

    private static final ThreadLocal<CanvasState> CURRENT = new ThreadLocal<>();

    public static CanvasState get() { return CURRENT.get(); }
    public static void set(CanvasState state) { CURRENT.set(state); }
    public static void clear() { CURRENT.remove(); }

    // Window dimensions
    private int width;
    private int height;
    private boolean canvasRequested;

    // Current drawing state (tracked for command generation)
    private float fillR = 1f, fillG = 1f, fillB = 1f;
    private float strokeR = 1f, strokeG = 1f, strokeB = 1f;
    private float strokeWeight = 1f;

    // Buffered commands
    private final List<DrawCommand> commands = new ArrayList<>();

    // Animation
    private CallTarget animateCallback;
    private double animateFps;

    // ── Canvas setup ──────────────────────────────────────────────────────

    public void requestCanvas(int width, int height) {
        this.width = width;
        this.height = height;
        this.canvasRequested = true;
    }

    public boolean isCanvasRequested() { return canvasRequested; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }

    // ── Command buffering ────────────────────────────────────────────────

    public void addCommand(DrawCommand cmd) { commands.add(cmd); }
    public List<DrawCommand> getCommands() { return commands; }
    public void clearCommands() { commands.clear(); }

    // ── Drawing state accessors ──────────────────────────────────────────

    public float getFillR() { return fillR; }
    public float getFillG() { return fillG; }
    public float getFillB() { return fillB; }

    public void setFill(float r, float g, float b) {
        fillR = r; fillG = g; fillB = b;
        addCommand(new DrawCommand.SetFill(r, g, b));
    }

    public void setStroke(float r, float g, float b) {
        strokeR = r; strokeG = g; strokeB = b;
        addCommand(new DrawCommand.SetStroke(r, g, b));
    }

    public void setStrokeWeight(float w) {
        strokeWeight = w;
        addCommand(new DrawCommand.SetStrokeWeight(w));
    }

    // ── Animation ────────────────────────────────────────────────────────

    public void setAnimateCallback(CallTarget callback, double fps) {
        this.animateCallback = callback;
        this.animateFps = fps;
    }

    public CallTarget getAnimateCallback() { return animateCallback; }
    public double getAnimateFps() { return animateFps; }
}
