package spn.gui.spoon;

import spn.fonts.SdfFontRenderer;
import spn.gui.EditorWindow;
import spn.gui.Main;
import spn.gui.Mode;
import spn.gui.Scrollbar;
import spn.gui.ScrollbarTheme;
import spn.gui.TextArea;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Unified command mode: loads a .spoon template into a dedicated TextArea,
 * lets the user edit it as plain text, then parses and submits on Ctrl+Space.
 *
 * <p>Three internal sub-states:
 * <ul>
 *   <li><b>EDITING</b> — normal .spoon text editing</li>
 *   <li><b>SOURCE_VIEW</b> — read-only view of the source code (Ctrl+L)</li>
 *   <li><b>HELP</b> — read-only help overlay listing available actions</li>
 * </ul>
 */
public class CommandMode implements Mode {

    private static final float SCROLLBAR_SIZE = 12f;

    private enum State { EDITING, SOURCE_VIEW, HELP }

    private final EditorWindow window;
    private final SpoonCommand command;
    private final SdfFontRenderer font;

    // Dedicated text area for .spoon editing (not the editor's source TextArea)
    private final TextArea textArea;
    private final Scrollbar vScroll;

    private State state = State.EDITING;

    // Help text (built once)
    private final String helpText;

    /**
     * Create a command mode for the given spoon command.
     * Loads the template from classpath resources.
     */
    public CommandMode(EditorWindow window, SpoonCommand command) {
        this.window = window;
        this.command = command;
        this.font = window.getFont();

        // Create a private TextArea for .spoon editing
        this.textArea = new TextArea(font);
        this.textArea.setClipboard(window.createClipboard());

        this.vScroll = new Scrollbar(font, Scrollbar.Orientation.VERTICAL);
        this.vScroll.setTheme(ScrollbarTheme.dark());
        this.vScroll.setOnChange(v -> textArea.setScrollRow(v));

        // Load template
        String template = loadTemplate(command.templateResource());
        textArea.setText(template);

        // Position cursor on the first empty value field
        positionCursorOnFirstEmptyValue();

        // Build help text
        this.helpText = buildHelpText();
    }

    /**
     * Create a command mode with pre-filled text (for retry from ErrorMode).
     */
    CommandMode(EditorWindow window, SpoonCommand command, String restoredText) {
        this(window, command);
        textArea.setText(restoredText);
    }

    // ---- Mode interface -------------------------------------------------

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return true;

        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

        // --- Global command bindings (all states) ---
        if (ctrl && key == GLFW_KEY_SPACE && action == GLFW_PRESS) {
            submit();
            return true;
        }
        if (ctrl && key == GLFW_KEY_BACKSPACE && action == GLFW_PRESS) {
            cancel();
            return true;
        }

        // --- State-specific handling ---
        switch (state) {
            case HELP -> {
                // Any key exits help
                state = State.EDITING;
                return true;
            }
            case SOURCE_VIEW -> {
                if (ctrl && key == GLFW_KEY_L) {
                    state = State.EDITING;
                    return true;
                }
                // In source view, allow scrolling with arrow keys but no editing
                TextArea sourceTA = window.getTextArea();
                if (key == GLFW_KEY_UP || key == GLFW_KEY_DOWN
                        || key == GLFW_KEY_PAGE_UP || key == GLFW_KEY_PAGE_DOWN) {
                    sourceTA.onKey(key, mods);
                }
                return true;
            }
            case EDITING -> {
                if (ctrl && key == GLFW_KEY_SLASH) {
                    state = State.HELP;
                    return true;
                }
                if (ctrl && key == GLFW_KEY_L) {
                    state = State.SOURCE_VIEW;
                    return true;
                }
                if (ctrl && key == GLFW_KEY_N && action == GLFW_PRESS) {
                    detachToNewWindow();
                    return true;
                }
                if (ctrl && key == GLFW_KEY_O && action == GLFW_PRESS) {
                    browseFolder();
                    return true;
                }

                // Check command-specific context actions
                for (ContextAction ca : command.contextActions()) {
                    if (key == ca.glfwKey() && (mods & ca.requiredMods()) == ca.requiredMods()) {
                        ca.action().accept(textArea);
                        return true;
                    }
                }

                // Delegate to TextArea for normal editing
                textArea.onKey(key, mods);
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean onChar(int codepoint) {
        if (state == State.EDITING) {
            textArea.onCharInput(codepoint);
        }
        if (state == State.HELP) {
            state = State.EDITING;
        }
        return true;
    }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        if (state == State.EDITING) {
            vScroll.onMouseButton(button, action, mods, mx, my);
            if (!vScroll.isDragging()) {
                textArea.onMouseButton(button, action, mods, mx, my);
            }
        }
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        if (state == State.EDITING) {
            vScroll.onCursorPos(mx, my);
            if (!vScroll.isDragging()) {
                textArea.onCursorPos(mx, my);
            }
        }
        return true;
    }

    @Override
    public boolean onScroll(double xoff, double yoff) {
        if (state == State.EDITING) {
            textArea.onScroll(xoff, yoff);
        } else if (state == State.SOURCE_VIEW) {
            window.getTextArea().onScroll(xoff, yoff);
        }
        return true;
    }

    @Override
    public void onCursorEnter(boolean entered) {
        if (state == State.EDITING) {
            textArea.onCursorEnter(entered);
        }
    }

    @Override
    public void render(float width, float height) {
        float hudH = window.getHud().preferredHeight();
        float bottomBar = hudH + SCROLLBAR_SIZE;

        switch (state) {
            case EDITING -> {
                textArea.setBounds(0, 0, width - SCROLLBAR_SIZE, height - bottomBar);
                vScroll.setBounds(width - SCROLLBAR_SIZE, 0, SCROLLBAR_SIZE, height - bottomBar);

                textArea.render();

                vScroll.setContent(textArea.getContentRows(), textArea.getVisibleRows());
                vScroll.setValue(textArea.getScrollRow());
                vScroll.render();
            }
            case SOURCE_VIEW -> {
                // Render the editor's source TextArea (read-only view)
                TextArea sourceTA = window.getTextArea();
                sourceTA.setBounds(0, 0, width - SCROLLBAR_SIZE, height - bottomBar);
                sourceTA.render();

                // Dim overlay to indicate read-only
                font.drawRect(0, 0, width, height - bottomBar,
                        0.0f, 0.0f, 0.05f); // subtle blue tint
            }
            case HELP -> {
                renderHelp(width, height, bottomBar);
            }
        }
    }

    @Override
    public String hudText() {
        return switch (state) {
            case EDITING ->
                    "Ctrl+Space Submit | Ctrl+Bksp Cancel | Ctrl+O Browse | Ctrl+/ Help | Ctrl+L Source";
            case SOURCE_VIEW ->
                    "Ctrl+L Back | Ctrl+Space Submit | Ctrl+Bksp Cancel";
            case HELP ->
                    "Any key to return";
        };
    }

    // ---- Submit / Cancel ------------------------------------------------

    private void submit() {
        String text = textArea.getText();
        try {
            Map<String, String> fields = SpoonParser.parse(text);
            command.handler().handle(fields, window);
            window.getHud().flash(command.name() + " completed", false);
            window.popMode();
        } catch (Exception e) {
            // Push ErrorMode on top (CommandMode stays on stack for retry)
            window.pushMode(new ErrorMode(window, command, text, e));
        }
    }

    private void cancel() {
        window.popMode();
    }

    // ---- Folder browsing ------------------------------------------------

    private void browseFolder() {
        String cwd = System.getProperty("user.dir", "");
        String selected = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_selectFolderDialog(
                "Select Folder", cwd);
        if (selected != null) {
            textArea.insertSnippet(selected);
        }
    }

    // ---- Detach to new window -------------------------------------------

    private void detachToNewWindow() {
        String currentText = textArea.getText();
        window.popMode(); // remove this CommandMode from current window

        EditorWindow newWin = Main.instance.spawnWindow();
        CommandMode newMode = new CommandMode(newWin, command, currentText);
        newWin.pushMode(newMode);
    }

    // ---- Help rendering -------------------------------------------------

    private void renderHelp(float width, float height, float bottomBar) {
        // Full dark background
        font.drawRect(0, 0, width, height - bottomBar, 0.10f, 0.10f, 0.12f);

        float scale = 0.30f;
        float lineH = font.getLineHeight(scale) * 1.4f;
        float x = 40f;
        float y = 40f + lineH;

        for (String line : helpText.split("\n")) {
            font.drawText(line, x, y, scale, 0.70f, 0.70f, 0.75f);
            y += lineH;
        }
    }

    private String buildHelpText() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Help -- ").append(command.name()).append("\n");
        sb.append("#\n");
        sb.append("# Global actions:\n");
        sb.append("\n");
        sb.append("  Ctrl+Space      Submit\n");
        sb.append("  Ctrl+Backspace  Cancel\n");
        sb.append("  Ctrl+O          Browse folder (pastes at cursor)\n");
        sb.append("  Ctrl+/          This help\n");
        sb.append("  Ctrl+L          View source code\n");
        sb.append("  Ctrl+N          Open in new window\n");

        if (!command.contextActions().isEmpty()) {
            sb.append("\n");
            sb.append("# Command actions:\n");
            sb.append("\n");
            for (ContextAction ca : command.contextActions()) {
                sb.append("  ").append(padRight(ca.shortcutHint(), 16))
                  .append(ca.label()).append("\n");
            }
        }

        return sb.toString();
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    // ---- Template loading -----------------------------------------------

    private String loadTemplate(String resource) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) return "# Template not found: " + resource + "\n";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "# Error loading template: " + e.getMessage() + "\n";
        }
    }

    // ---- Cursor positioning ---------------------------------------------

    /**
     * Position the cursor on the first field with an empty value.
     * Scans for lines matching "key:" or "key: " with no value after the colon.
     */
    private void positionCursorOnFirstEmptyValue() {
        var buffer = textArea.getBuffer();
        for (int row = 0; row < buffer.lineCount(); row++) {
            String line = buffer.getLine(row);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            int colon = trimmed.indexOf(':');
            if (colon < 0) continue;

            String value = trimmed.substring(colon + 1).trim();
            if (value.isEmpty()) {
                // Position cursor after the colon (and space if present)
                int lineColon = line.indexOf(':');
                int col = lineColon + 1;
                if (col < line.length() && line.charAt(col) == ' ') col++;
                textArea.setCursorPosition(row, col);
                return;
            }
        }
    }
}
