package spn.canvasgui.spn;

import spn.canvasgui.component.Component;
import spn.canvasgui.font.FontLoader;
import spn.canvasgui.font.FontRegistry;
import spn.canvasgui.loop.GuiWindow;
import spn.canvasgui.theme.Theme;
import spn.fonts.SdfFontRenderer;
import spn.type.SpnSymbolTable;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * Drives the GUI main loop after SPN execution returns. Reads configuration
 * from {@link GuiSpnState}, opens a {@link GuiWindow}, and each frame invokes
 * the user's render function, reconciles the returned tree, and paints.
 *
 * <p>Builds a per-window {@link FontRegistry} with the registered builtins
 * ({@code :mono / :serif / :sans}). All renderers are created fresh against
 * the window's GL context and fully disposed when the loop exits, so no
 * font state is shared across runs.
 * Must be invoked on the thread that owns the shared GL context.
 */
public final class GuiHost {

    /**
     * @param state      post-SPN configuration (must have {@code isRunRequested() == true})
     * @param shareWith  GLFW window handle whose GL context to share (NULL for none)
     */
    public static void run(GuiSpnState state, long shareWith) {
        if (!state.isRunRequested()) return;

        Theme theme = new Theme();
        Reconciler reconciler = new Reconciler(theme);

        SpnSymbolTable symbols = new SpnSymbolTable();
        FontRegistry fonts = new FontRegistry(symbols);

        GuiWindow window = new GuiWindow();
        float remPx = 16f;
        window.open(state.title(), state.width(), state.height(), shareWith, fonts, remPx);
        window.makeContextCurrent();
        window.root().ctx().setWindowHandle(window.handle());

        // Bundled builtin families from spn-fonts/resources. Each family has
        // regular / bold / italic variants; missing variants degrade to the
        // closest available at render time.
        boolean monoOk = FontLoader.tryLoadBundledFamily(fonts, "mono",
                "fonts/mono/ubuntusansmono-regular.ttf",
                "fonts/mono/ubuntusansmono-bold.ttf",
                "fonts/mono/ubuntusansmono-italic.ttf", 64f);
        FontLoader.tryLoadBundledFamily(fonts, "serif",
                "fonts/serif/notoserif-regular.ttf",
                "fonts/serif/notoserif-bold.ttf",
                "fonts/serif/notoserif-italic.ttf", 64f);
        FontLoader.tryLoadBundledFamily(fonts, "sans",
                "fonts/sans/notosans-regular.ttf",
                "fonts/sans/notosans-bold.ttf",
                "fonts/sans/notosans-italic.ttf", 64f);

        // If bundled :mono couldn't load, fall back to a fresh renderer
        // built from the default bundled resource path. Owned by `fonts`
        // and disposed along with everything else below.
        if (!monoOk) {
            SdfFontRenderer fallback = SdfFontRenderer.loadBundled(
                    "fonts/mono/ubuntusansmono-regular.ttf", 64f);
            fonts.registerRegular("mono", fallback);
            fonts.setDefault("mono");
        }

        // Also: if the user's SPN code has queued deferred-load requests
        // via guiLoadFont(...), process them now that the GL context is live.
        for (GuiSpnState.PendingFontLoad p : state.pendingFontLoads()) {
            FontLoader.loadFromPath(fonts, p.symbolName(), p.path(), 64f);
        }

        Component live = null;
        double frameDuration = 1.0 / Math.max(1.0, state.targetFps());
        glfwSwapInterval(0);

        try {
            while (!window.shouldClose()) {
                double frameStart = glfwGetTime();
                window.root().ctx().setTime(frameStart);

                glfwPollEvents();
                window.root().dispatchInputs();

                state.renderFn().call();

                GuiCmd tree = state.takeTree();
                if (tree != null) {
                    Component next = live == null
                            ? reconciler.build(tree)
                            : reconciler.update(live, tree);
                    if (next != live) {
                        live = next;
                        window.root().setContent(live);
                    }
                }

                int[] fb = window.framebufferSize();
                glViewport(0, 0, fb[0], fb[1]);
                glClear(GL_COLOR_BUFFER_BIT);
                window.root().layout(fb[0], fb[1]);
                window.root().paint(fb[0], fb[1]);
                window.swap();

                double elapsed = glfwGetTime() - frameStart;
                if (elapsed < frameDuration) {
                    try { Thread.sleep((long) ((frameDuration - elapsed) * 1000)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
        } finally {
            fonts.dispose();
            window.close();
        }
    }

    private GuiHost() {}
}
