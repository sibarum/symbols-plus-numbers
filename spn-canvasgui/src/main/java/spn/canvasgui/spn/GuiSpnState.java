package spn.canvasgui.spn;

import com.oracle.truffle.api.CallTarget;
import spn.fonts.SdfFontRenderer;

/**
 * Thread-local accumulator for GUI state during SPN execution.
 *
 * <p>Mirrors the pattern of {@code spn.canvas.CanvasState}: SPN-invoked
 * builtins (gui_window, gui_render, gui_run) stash configuration here;
 * the host reads it after SPN returns and drives the actual main loop.
 */
public final class GuiSpnState {

    private static final ThreadLocal<GuiSpnState> CURRENT = new ThreadLocal<>();

    public static GuiSpnState get() { return CURRENT.get(); }
    public static void set(GuiSpnState s) { CURRENT.set(s); }
    public static void clear() { CURRENT.remove(); }

    private int width = 640;
    private int height = 360;
    private String title = "SPN GUI";
    private boolean windowRequested;

    private double targetFps = 30.0;
    private CallTarget renderFn;
    private boolean runRequested;

    /** Latest GUI tree submitted via gui_render, picked up after each frame call. */
    private GuiCmd pendingTree;

    // Host-side resources: the shared font renderer + GL context handle that
    // guiRun uses to open its window. EditorWindow populates these before
    // running SPN so that guiRun (which runs INLINE and blocks until the
    // window closes) has everything it needs.
    private SdfFontRenderer font;
    private long shareWith;
    private Runnable onRunEnter;
    private Runnable onRunExit;

    public SdfFontRenderer font() { return font; }
    public long shareWith() { return shareWith; }
    public Runnable onRunEnter() { return onRunEnter; }
    public Runnable onRunExit() { return onRunExit; }

    public void setHostResources(SdfFontRenderer font, long shareWith,
                                 Runnable onRunEnter, Runnable onRunExit) {
        this.font = font;
        this.shareWith = shareWith;
        this.onRunEnter = onRunEnter;
        this.onRunExit = onRunExit;
    }

    // ── window ─────────────────────────────────────────────────────────
    public void requestWindow(int w, int h, String title) {
        this.width = w;
        this.height = h;
        this.title = title;
        this.windowRequested = true;
    }
    public boolean isWindowRequested() { return windowRequested; }
    public int width() { return width; }
    public int height() { return height; }
    public String title() { return title; }

    // ── run ────────────────────────────────────────────────────────────
    public void requestRun(double fps, CallTarget renderFn) {
        this.targetFps = fps;
        this.renderFn = renderFn;
        this.runRequested = true;
    }
    public boolean isRunRequested() { return runRequested; }
    public double targetFps() { return targetFps; }
    public CallTarget renderFn() { return renderFn; }

    // ── render ─────────────────────────────────────────────────────────
    public void submitTree(GuiCmd tree) { this.pendingTree = tree; }
    public GuiCmd takeTree() {
        GuiCmd t = pendingTree;
        pendingTree = null;
        return t;
    }
}
