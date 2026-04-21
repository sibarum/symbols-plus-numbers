package spn.canvasgui;

import org.lwjgl.glfw.GLFWErrorCallback;
import spn.canvasgui.font.FontRegistry;
import spn.canvasgui.layout.HBox;
import spn.canvasgui.loop.GuiLoop;
import spn.canvasgui.loop.GuiWindow;
import spn.canvasgui.theme.Theme;
import spn.canvasgui.widget.Button;
import spn.fonts.SdfFontRenderer;
import spn.type.SpnSymbolTable;

import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwSetErrorCallback;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Phase 0 spike: HBox of two buttons in a window. Clicks print to stdout.
 * Proves the full pipeline (input → tree → GuiCommand → renderer → GL).
 */
public final class Demo {

    public static void main(String[] args) {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        SpnSymbolTable symbols = new SpnSymbolTable();
        FontRegistry fonts = new FontRegistry(symbols);

        GuiWindow window = new GuiWindow();
        window.open("spn-canvasgui demo", 640, 360, NULL, fonts, 16f);
        window.makeContextCurrent();

        SdfFontRenderer mono = new SdfFontRenderer();
        mono.init("C:/Windows/Fonts/consola.ttf", 64f);
        fonts.registerRegular("mono", mono);

        Theme theme = new Theme();
        HBox row = new HBox()
                .setGapRem(0.5f)
                .add(new Button("Alpha", theme).onClick(() -> System.out.println("Alpha clicked")))
                .add(new Button("Beta",  theme).onClick(() -> System.out.println("Beta clicked")));
        window.root().setContent(row);

        new GuiLoop().setTargetFps(60).run(window);

        fonts.dispose();
        window.close();
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }

    private Demo() {}
}
