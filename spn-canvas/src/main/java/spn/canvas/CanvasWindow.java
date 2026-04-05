package spn.canvas;

import com.oracle.truffle.api.CallTarget;
import org.lwjgl.opengl.GL;
import spn.fonts.SdfFontRenderer;

import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

/**
 * A standalone GLFW window that displays canvas drawing output.
 * Supports both one-shot (static) and animated (frame loop) modes.
 */
public final class CanvasWindow {

    private long handle;
    private CanvasRenderer renderer;
    private SdfFontRenderer font;
    private int width;
    private int height;

    /**
     * Creates and shows the canvas window.
     *
     * @param width       canvas width in pixels
     * @param height      canvas height in pixels
     * @param shareWith   GLFW window handle to share GL context with, or NULL
     * @param font        shared font renderer for text commands (may be null)
     */
    public void open(int width, int height, long shareWith, SdfFontRenderer font) {
        this.width = width;
        this.height = height;
        this.font = font;

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_COMPAT_PROFILE);

        handle = glfwCreateWindow(width, height, "SPN Canvas", NULL, shareWith);
        if (handle == NULL) throw new RuntimeException("Failed to create canvas window");

        glfwMakeContextCurrent(handle);
        GL.createCapabilities();

        glClearColor(0f, 0f, 0f, 1f);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        renderer = new CanvasRenderer();
        renderer.init();

        glfwShowWindow(handle);
    }

    /**
     * One-shot mode: replay the command buffer once and wait until the window is closed.
     */
    public void showStatic(List<DrawCommand> commands) {
        // Render once
        glViewport(0, 0, width, height);
        glClear(GL_COLOR_BUFFER_BIT);
        renderer.replay(commands, width, height, font);
        glfwSwapBuffers(handle);

        // Wait loop — re-render on expose/resize
        glfwSetWindowRefreshCallback(handle, win -> {
            glfwMakeContextCurrent(win);
            int[] w = new int[1], h = new int[1];
            glfwGetFramebufferSize(win, w, h);
            glViewport(0, 0, w[0], h[0]);
            glClear(GL_COLOR_BUFFER_BIT);
            renderer.replay(commands, width, height, font);
            glfwSwapBuffers(win);
        });

        while (!glfwWindowShouldClose(handle)) {
            glfwWaitEvents();
        }
    }

    /**
     * Animated mode: call the draw callback each frame at the specified FPS.
     */
    public void showAnimated(double fps, CallTarget drawCallback) {
        double frameDuration = 1.0 / fps;
        double startTime = glfwGetTime();

        glfwSwapInterval(0); // disable vsync, we manage timing ourselves

        while (!glfwWindowShouldClose(handle)) {
            double frameStart = glfwGetTime();
            double elapsed = frameStart - startTime;

            // Set up fresh canvas state for this frame
            CanvasState state = CanvasState.get();
            state.clearCommands();

            // Call the SPN draw function with elapsed time
            drawCallback.call(elapsed);

            // Render
            int[] w = new int[1], h = new int[1];
            glfwGetFramebufferSize(handle, w, h);
            glViewport(0, 0, w[0], h[0]);
            glClear(GL_COLOR_BUFFER_BIT);
            renderer.replay(state.getCommands(), width, height, font);
            glfwSwapBuffers(handle);

            glfwPollEvents();

            // Frame pacing
            double elapsed2 = glfwGetTime() - frameStart;
            if (elapsed2 < frameDuration) {
                try {
                    Thread.sleep((long) ((frameDuration - elapsed2) * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public void close() {
        if (renderer != null) renderer.dispose();
        if (handle != NULL) glfwDestroyWindow(handle);
        handle = NULL;
    }
}
