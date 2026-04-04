package spn.gui;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.*;

public class Main {

    static Main instance;
    static final PointerBuffer SPN_FILTER = createFilterPatterns("*.spn", "*.txt");

    private SdfFontRenderer font;
    private final List<EditorWindow> windows = new ArrayList<>();
    private final List<EditorWindow> pendingWindows = new ArrayList<>();
    private boolean anyWindowFocused = true;

    private static PointerBuffer createFilterPatterns(String... patterns) {
        PointerBuffer buf = PointerBuffer.allocateDirect(patterns.length);
        for (String p : patterns) buf.put(memUTF8(p));
        buf.flip();
        return buf;
    }

    public static void main(String[] args) {
        instance = new Main();
        instance.run();
    }

    private void run() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        // Create the first window and initialise GL
        EditorWindow first = new EditorWindow(NULL);
        first.makeCurrent();
        GL.createCapabilities();

        font = new SdfFontRenderer();
        font.init("C:/Windows/Fonts/consola.ttf", 64f);

        first.initComponents(font);
        centerOnScreen(first.getHandle());
        glClearColor(0.12f, 0.12f, 0.14f, 1.0f);
        first.show();
        windows.add(first);

        // Main loop — render all open windows, remove closed ones
        while (!windows.isEmpty()) {
            if (anyWindowFocused) {
                glfwWaitEventsTimeout(1.0/30);
            } else {
                glfwWaitEventsTimeout(0.5);
            }

            // Add any windows spawned during this frame
            if (!pendingWindows.isEmpty()) {
                windows.addAll(pendingWindows);
                pendingWindows.clear();
            }

            windows.removeIf(w -> {
                if (w.shouldClose()) {
                    w.destroy();
                    return true;
                }
                w.makeCurrent();
                w.render();
                return false;
            });
        }

        font.dispose();
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }

    void onWindowFocusChanged() {
        anyWindowFocused = false;
        for (EditorWindow w : windows) {
            if (w.isFocused()) { anyWindowFocused = true; return; }
        }
    }

    void spawnWindow() {
        long shareWith = windows.isEmpty() ? NULL : windows.getFirst().getHandle();
        EditorWindow w = new EditorWindow(shareWith);
        w.initComponents(font);

        // Offset slightly from the current window position
        int[] px = new int[1], py = new int[1];
        glfwGetWindowPos(windows.getLast().getHandle(), px, py);
        glfwSetWindowPos(w.getHandle(), px[0] + 30, py[0] + 30);

        w.makeCurrent();
        GL.createCapabilities();
        glClearColor(0.12f, 0.12f, 0.14f, 1.0f);

        w.show();
        pendingWindows.add(w);
    }

    private static void centerOnScreen(long window) {
        GLFWVidMode vidMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (vidMode != null) {
            glfwSetWindowPos(window,
                    (vidMode.width() - 1280) / 2,
                    (vidMode.height() - 720) / 2);
        }
    }
}
