package spn.canvasgui.loop;

import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.opengl.GL;
import spn.canvas.CanvasRenderer;
import spn.fonts.SdfFontRenderer;
import spn.stdui.input.InputEvent;
import spn.stdui.input.Key;
import spn.stdui.input.Mod;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * GLFW window that hosts a {@link GuiRoot}. Shares its GL context with an
 * existing window when {@code shareWith} is non-NULL so the font atlas and
 * other shared resources work across windows.
 */
public final class GuiWindow {

    private long handle = NULL;
    private int width;
    private int height;
    private GuiRoot root;
    private CanvasRenderer canvasRenderer;

    public long handle() { return handle; }

    public int width() { return width; }

    public int height() { return height; }

    public GuiRoot root() { return root; }

    /**
     * Open the window. {@code font} must be initialized on a shared context so
     * this window can use it after becoming current.
     */
    public void open(String title, int width, int height, long shareWith,
                     SdfFontRenderer font, float remPx) {
        this.width = width;
        this.height = height;

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_COMPAT_PROFILE);

        handle = glfwCreateWindow(width, height, title, NULL, shareWith);
        if (handle == NULL) throw new RuntimeException("Failed to create gui window");

        glfwMakeContextCurrent(handle);
        GL.createCapabilities();

        glClearColor(0f, 0f, 0f, 1f);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        canvasRenderer = new CanvasRenderer();
        canvasRenderer.init();

        root = new GuiRoot(font, remPx, canvasRenderer);

        installCallbacks();
        glfwShowWindow(handle);
    }

    private void installCallbacks() {
        glfwSetCursorPosCallback(handle, GLFWCursorPosCallback.create((win, x, y) ->
                root.postInput(new InputEvent.MouseMove(x, y))));

        glfwSetMouseButtonCallback(handle, GLFWMouseButtonCallback.create((win, button, action, mods) -> {
            double[] mx = new double[1], my = new double[1];
            glfwGetCursorPos(win, mx, my);
            int pmods = Mod.fromGlfw(mods);
            if (action == GLFW_PRESS) root.postInput(new InputEvent.MousePress(button, pmods, mx[0], my[0]));
            else if (action == GLFW_RELEASE) root.postInput(new InputEvent.MouseRelease(button, pmods, mx[0], my[0]));
        }));

        glfwSetScrollCallback(handle, GLFWScrollCallback.create((win, xOff, yOff) ->
                root.postInput(new InputEvent.MouseScroll(xOff, yOff))));

        glfwSetKeyCallback(handle, GLFWKeyCallback.create((win, key, scan, action, mods) -> {
            Key k = Key.fromGlfw(key);
            int pmods = Mod.fromGlfw(mods);
            switch (action) {
                case GLFW_PRESS -> root.postInput(new InputEvent.KeyPress(k, pmods));
                case GLFW_REPEAT -> root.postInput(new InputEvent.KeyRepeat(k, pmods));
                case GLFW_RELEASE -> root.postInput(new InputEvent.KeyRelease(k, pmods));
            }
        }));

        glfwSetCharCallback(handle, GLFWCharCallback.create((win, cp) ->
                root.postInput(new InputEvent.CharInput(cp))));
    }

    public boolean shouldClose() { return glfwWindowShouldClose(handle); }

    public void makeContextCurrent() { glfwMakeContextCurrent(handle); }

    /** Query framebuffer size; may differ from window size on high-DPI. */
    public int[] framebufferSize() {
        int[] w = new int[1], h = new int[1];
        glfwGetFramebufferSize(handle, w, h);
        return new int[] { w[0], h[0] };
    }

    public void swap() { glfwSwapBuffers(handle); }

    public void close() {
        if (canvasRenderer != null) canvasRenderer.dispose();
        if (handle != NULL) glfwDestroyWindow(handle);
        handle = NULL;
    }
}
