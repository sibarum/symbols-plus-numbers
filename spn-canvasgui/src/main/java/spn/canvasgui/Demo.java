package spn.canvasgui;

import org.lwjgl.glfw.GLFWErrorCallback;
import spn.canvasgui.layout.HBox;
import spn.canvasgui.loop.GuiLoop;
import spn.canvasgui.loop.GuiWindow;
import spn.canvasgui.theme.Theme;
import spn.canvasgui.widget.Button;
import spn.fonts.SdfFontRenderer;

import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwSetErrorCallback;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Phase 0 spike: HBox of two buttons in a window. Clicks print to stdout.
 * Proves the full pipeline (input → tree → GuiCommand → DrawCommand → GL).
 */
public final class Demo {

    public static void main(String[] args) {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        GuiWindow window = new GuiWindow();
        // Open the window first so we have a GL context for font init
        SdfFontRenderer font = new SdfFontRenderer();
        // Placeholder renderer passed to open() — we re-init after context is current.
        window.open("spn-canvasgui demo", 640, 360, NULL, font, 16f);
        window.makeContextCurrent();
        font.init("C:/Windows/Fonts/consola.ttf", 64f);

        Theme theme = new Theme();
        HBox row = new HBox()
                .setGapRem(0.5f)
                .add(new Button("Alpha", theme).onClick(() -> System.out.println("Alpha clicked")))
                .add(new Button("Beta",  theme).onClick(() -> System.out.println("Beta clicked")));
        window.root().setContent(row);

        new GuiLoop().setTargetFps(60).run(window);

        font.dispose();
        window.close();
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }

    private Demo() {}
}
