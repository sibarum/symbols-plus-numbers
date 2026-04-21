package spn.canvasgui.spn;

import spn.canvasgui.component.Component;
import spn.canvasgui.loop.GuiWindow;
import spn.canvasgui.theme.Theme;
import spn.fonts.SdfFontRenderer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * Drives the GUI main loop after SPN execution returns. Reads configuration
 * from {@link GuiSpnState}, opens a {@link GuiWindow}, and on each frame
 * calls the user's frame function with {@code (state, events)}, reconciles
 * the returned {@code GuiCmd} tree against the live component tree, and
 * renders.
 *
 * <p>The host must be invoked from the same thread that initialized the
 * shared GL context (the font renderer relies on context sharing).
 */
public final class GuiHost {

    /**
     * Run the GUI loop using the configuration stashed in the given state.
     * Blocks until the window is closed.
     *
     * @param state  the post-SPN state (must have isRunRequested() == true)
     * @param font   shared font renderer (already initialized on a shared GL context)
     * @param shareWith a GLFW window handle to share GL context with, or NULL
     */
    public static void run(GuiSpnState state, SdfFontRenderer font, long shareWith) {
        if (!state.isRunRequested()) return;

        Theme theme = new Theme();
        Reconciler reconciler = new Reconciler(theme);

        GuiWindow window = new GuiWindow();
        float remPx = 16f;
        window.open(state.title(), state.width(), state.height(), shareWith, font, remPx);
        window.makeContextCurrent();

        Component live = null;

        double frameDuration = 1.0 / Math.max(1.0, state.targetFps());
        glfwSwapInterval(0);

        while (!window.shouldClose()) {
            double frameStart = glfwGetTime();

            glfwPollEvents();
            window.root().dispatchInputs();

            // Invoke the user's render function (takes no args; mutates state
            // via do() closures bound to the stateful instance, and calls
            // guiRender(tree) to submit the next UI snapshot).
            state.renderFn().call();

            // Pick up any guiRender(cmd) submitted during the frame
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

        window.close();
    }

    private GuiHost() {}
}
