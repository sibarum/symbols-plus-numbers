package spn.gui.spoon;

import spn.fonts.SdfFontRenderer;
import spn.gui.EditorWindow;
import spn.gui.Mode;
import spn.gui.Scrollbar;
import spn.gui.ScrollbarTheme;
import spn.gui.TextArea;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Read-only error display mode. Shown when a .spoon command handler fails.
 *
 * <p>Mode stack at this point: {@code EditorMode → CommandMode → ErrorMode}.
 * <ul>
 *   <li><b>Ctrl+Space</b> — Retry: pops ErrorMode, returns to CommandMode
 *       with the user's .spoon text intact.</li>
 *   <li><b>Ctrl+Backspace</b> — Cancel: pops both ErrorMode and CommandMode,
 *       returning to the editor.</li>
 * </ul>
 */
public class ErrorMode implements Mode {

    private static final float SCROLLBAR_SIZE = 12f;

    private final EditorWindow window;
    private final TextArea textArea;
    private final Scrollbar vScroll;

    /**
     * @param window   the editor window
     * @param command  the command that failed (for display purposes)
     * @param spoonText the .spoon text that was submitted (preserved for retry)
     * @param error    the exception thrown by the handler
     */
    ErrorMode(EditorWindow window, SpoonCommand command, String spoonText, Exception error) {
        this.window = window;

        SdfFontRenderer font = window.getFont();
        this.textArea = new TextArea(font);
        this.textArea.setClipboard(window.createClipboard());

        this.vScroll = new Scrollbar(font, Scrollbar.Orientation.VERTICAL);
        this.vScroll.setTheme(ScrollbarTheme.dark());
        this.vScroll.setOnChange(v -> textArea.setScrollRow(v));

        // Format error for display
        StringBuilder sb = new StringBuilder();
        sb.append("# Error: ").append(command.name()).append("\n");
        sb.append("#\n");
        sb.append("# Ctrl+Space to retry, Ctrl+Backspace to cancel.\n");
        sb.append("\n");

        String message = error.getMessage();
        if (message != null && !message.isEmpty()) {
            sb.append(message).append("\n");
        } else {
            sb.append(error.getClass().getSimpleName()).append("\n");
        }

        // Append stacktrace
        sb.append("\n");
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        sb.append(sw);

        textArea.setText(sb.toString());
    }

    // ---- Mode interface -------------------------------------------------

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS) return true;

        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

        if (ctrl && key == GLFW_KEY_SPACE) {
            // Retry: pop ErrorMode, return to CommandMode with text intact
            window.popMode();
            return true;
        }
        if (ctrl && key == GLFW_KEY_BACKSPACE) {
            // Cancel: pop both ErrorMode and CommandMode
            window.popMode(); // pop ErrorMode
            window.popMode(); // pop CommandMode
            return true;
        }

        // Allow navigation but no editing
        if (key == GLFW_KEY_UP || key == GLFW_KEY_DOWN
                || key == GLFW_KEY_PAGE_UP || key == GLFW_KEY_PAGE_DOWN
                || key == GLFW_KEY_HOME || key == GLFW_KEY_END) {
            textArea.onKey(key, mods);
        }

        return true;
    }

    @Override
    public boolean onChar(int codepoint) {
        return true; // swallow — read-only
    }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        vScroll.onMouseButton(button, action, mods, mx, my);
        if (!vScroll.isDragging()) {
            textArea.onMouseButton(button, action, mods, mx, my);
        }
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        vScroll.onCursorPos(mx, my);
        if (!vScroll.isDragging()) {
            textArea.onCursorPos(mx, my);
        }
        return true;
    }

    @Override
    public boolean onScroll(double xoff, double yoff) {
        textArea.onScroll(xoff, yoff);
        return true;
    }

    @Override
    public void render(float width, float height) {
        float hudH = window.getHud().preferredHeight();
        float bottomBar = hudH + SCROLLBAR_SIZE;

        textArea.setBounds(0, 0, width - SCROLLBAR_SIZE, height - bottomBar);
        vScroll.setBounds(width - SCROLLBAR_SIZE, 0, SCROLLBAR_SIZE, height - bottomBar);

        textArea.render();

        vScroll.setContent(textArea.getContentRows(), textArea.getVisibleRows());
        vScroll.setValue(textArea.getScrollRow());
        vScroll.render();
    }

    @Override
    public String hudText() {
        return "Ctrl+Space Retry | Ctrl+Bksp Cancel";
    }
}
