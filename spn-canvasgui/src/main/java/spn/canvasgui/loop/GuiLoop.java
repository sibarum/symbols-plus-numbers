package spn.canvasgui.loop;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/**
 * Frame loop with configurable target FPS. Single-threaded: all rendering and
 * input dispatch happens on the thread that owns the GL context. Async tasks
 * post back by enqueueing input via {@link GuiRoot#postInput} (thread-safe).
 */
public final class GuiLoop {

    private int targetFps = 60;
    private volatile boolean running;

    public GuiLoop setTargetFps(int fps) {
        this.targetFps = Math.max(1, fps);
        return this;
    }

    public int targetFps() { return targetFps; }

    public void stop() { running = false; }

    public void run(GuiWindow window) {
        running = true;
        window.makeContextCurrent();
        glfwSwapInterval(0);

        double frameDuration = 1.0 / targetFps;
        while (running && !window.shouldClose()) {
            double frameStart = glfwGetTime();

            glfwPollEvents();
            window.root().dispatchInputs();

            int[] fb = window.framebufferSize();
            glViewport(0, 0, fb[0], fb[1]);
            glClear(GL_COLOR_BUFFER_BIT);

            window.root().layout(fb[0], fb[1]);
            window.root().paint(fb[0], fb[1]);

            window.swap();

            double elapsed = glfwGetTime() - frameStart;
            if (elapsed < frameDuration) {
                try {
                    Thread.sleep((long) ((frameDuration - elapsed) * 1000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        running = false;
    }
}
