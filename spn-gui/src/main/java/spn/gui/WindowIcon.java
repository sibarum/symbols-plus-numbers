package spn.gui;

import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryStack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.lwjgl.glfw.GLFW.glfwSetWindowIcon;

/**
 * Loads pre-extracted SPN icons and applies them to GLFW windows.
 * Icons are stored as individual PNGs on the classpath (icon-16.png, etc.).
 */
public final class WindowIcon {

    private static final String[] ICON_RESOURCES = {
            "/icon-16.png", "/icon-32.png", "/icon-48.png"
    };

    private WindowIcon() {}

    /** Load icons from the classpath and apply them to the given GLFW window. */
    public static void apply(long windowHandle) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int count = 0;
            for (String res : ICON_RESOURCES) {
                if (WindowIcon.class.getResource(res) != null) count++;
            }
            if (count == 0) return;

            GLFWImage.Buffer icons = GLFWImage.malloc(count, stack);
            int idx = 0;

            for (String res : ICON_RESOURCES) {
                try (InputStream in = WindowIcon.class.getResourceAsStream(res)) {
                    if (in == null) continue;
                    BufferedImage img = ImageIO.read(in);
                    ByteBuffer pixels = imageToRGBA(img);
                    icons.get(idx++).set(img.getWidth(), img.getHeight(), pixels);
                }
            }

            glfwSetWindowIcon(windowHandle, icons);
        } catch (Exception e) {
            System.err.println("Failed to load window icon: " + e.getMessage());
        }
    }

    private static ByteBuffer imageToRGBA(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(w * h * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = image.getRGB(x, y);
                buffer.put((byte) ((argb >> 16) & 0xFF)); // R
                buffer.put((byte) ((argb >> 8) & 0xFF));  // G
                buffer.put((byte) (argb & 0xFF));          // B
                buffer.put((byte) ((argb >> 24) & 0xFF)); // A
            }
        }
        buffer.flip();
        return buffer;
    }
}
