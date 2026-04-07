package spn.stdui.window;

/**
 * Platform-neutral clipboard access. Implementations bridge to the
 * platform clipboard (e.g., GLFW in spn-gui).
 */
public interface Clipboard {
    void set(String text);
    String get();
}
