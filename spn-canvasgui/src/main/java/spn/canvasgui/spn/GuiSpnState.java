package spn.canvasgui.spn;

import com.oracle.truffle.api.CallTarget;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    // Host-side resources: the shared GL context handle that guiRun uses
    // to open its window, plus enter/exit callbacks for the IDE to swap
    // out its own input routing while the GUI loop is running.
    // EditorWindow populates these before running SPN so that guiRun
    // (which runs INLINE and blocks until the window closes) has
    // everything it needs. Fonts are loaded per-run by GuiHost itself.
    private long shareWith;
    private Runnable onRunEnter;
    private Runnable onRunExit;

    public long shareWith() { return shareWith; }
    public Runnable onRunEnter() { return onRunEnter; }
    public Runnable onRunExit() { return onRunExit; }

    public void setHostResources(long shareWith,
                                 Runnable onRunEnter, Runnable onRunExit) {
        this.shareWith = shareWith;
        this.onRunEnter = onRunEnter;
        this.onRunExit = onRunExit;
    }

    // ── Module-relative resource loading ───────────────────────────────
    // The module root of the .spn file that's currently running. Populated
    // by EditorWindow before SPN execution; used to resolve relative paths
    // passed to `guiLoadFont(...)` and similar builtins.
    private Path moduleRoot;
    public Path moduleRoot() { return moduleRoot; }
    public void setModuleRoot(Path p) { this.moduleRoot = p; }

    // ── Deferred font loads ────────────────────────────────────────────
    // `guiLoadFont(:sym, "path")` queues a load request while SPN is running
    // (no GL context available yet). The host processes these after opening
    // the window, before the first frame.
    public record PendingFontLoad(String symbolName, String path) {}
    private final List<PendingFontLoad> pendingFontLoads = new ArrayList<>();

    public void queueFontLoad(String symbolName, String path) {
        pendingFontLoads.add(new PendingFontLoad(symbolName, path));
    }
    public List<PendingFontLoad> pendingFontLoads() { return pendingFontLoads; }

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
