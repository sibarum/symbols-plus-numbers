package spn.gui;

import spn.gui.lang.HighlightCache;
import spn.stdui.buffer.UndoManager;
import spn.stdui.widget.ScrollbarTheme;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.glfw.GLFW.*;

/**
 * A stacked file editor pushed onto the mode stack when opening a file
 * from the module browser. Has its own TextArea (independent buffer).
 * Escape or Ctrl+W pops it, returning to the previous file.
 */
class FileViewMode implements Mode {

    private static final float SCROLLBAR_SIZE = 12f;

    private final EditorWindow window;
    private final TextArea textArea;
    private final Scrollbar vScroll;
    private final Scrollbar hScroll;
    private final Path filePath;
    private String savedContent;

    FileViewMode(EditorWindow window, Path filePath, String content) {
        this.window = window;
        this.filePath = filePath;
        this.savedContent = content;

        // Create independent TextArea with SPN highlighting
        this.textArea = new TextArea(window.getFont());
        this.textArea.setClipboard(new TextArea.ClipboardHandler() {
            @Override public void set(String text) {
                org.lwjgl.glfw.GLFW.glfwSetClipboardString(window.getHandle(), text);
            }
            @Override public String get() {
                return org.lwjgl.glfw.GLFW.glfwGetClipboardString(window.getHandle());
            }
        });
        this.textArea.setText(content);

        ScrollbarTheme theme = ScrollbarTheme.dark();
        this.vScroll = new Scrollbar(window.getFont(), Scrollbar.Orientation.VERTICAL);
        vScroll.setTheme(theme);
        vScroll.setOnChange(v -> textArea.setScrollRow(v));

        this.hScroll = new Scrollbar(window.getFont(), Scrollbar.Orientation.HORIZONTAL);
        hScroll.setTheme(theme);
        hScroll.setOnChange(v -> textArea.setScrollCol(v));
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return false;

        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

        // Close this stacked editor
        if (key == GLFW_KEY_ESCAPE || (ctrl && key == GLFW_KEY_W)) {
            if (isDirty()) {
                // Push confirm exit for this file
                window.pushLegacyMode(new ConfirmFileCloseMode(window, this));
            } else {
                window.popMode();
            }
            return true;
        }

        // Save
        if (ctrl && key == GLFW_KEY_S && action == GLFW_PRESS) {
            saveFile((mods & GLFW_MOD_SHIFT) != 0);
            return true;
        }

        // Module browser
        if (ctrl && key == GLFW_KEY_M && action == GLFW_PRESS) {
            ModuleContext ctx = window.getModuleContext();
            if (ctx != null) {
                window.pushLegacyMode(new ModuleMode(window, ctx));
            } else {
                window.flash("No module loaded", true);
            }
            return true;
        }

        // Logs
        if (ctrl && key == GLFW_KEY_G && action == GLFW_PRESS) {
            window.pushLegacyMode(new LogViewMode(window));
            return true;
        }

        // Run
        if ((key == GLFW_KEY_F5 || (ctrl && key == GLFW_KEY_R)) && action == GLFW_PRESS) {
            window.flash("Run not available in stacked view", true);
            return true;
        }

        // Action menu
        if (ctrl && key == GLFW_KEY_P && action == GLFW_PRESS) {
            window.pushLegacyMode(new ActionMenuMode(window, window.getActionRegistry()));
            return true;
        }

        // Undo branch switching
        if (ctrl && key == GLFW_KEY_LEFT_BRACKET && action == GLFW_PRESS) {
            textArea.switchUndoBranch(-1);
            return true;
        }
        if (ctrl && key == GLFW_KEY_RIGHT_BRACKET && action == GLFW_PRESS) {
            textArea.switchUndoBranch(1);
            return true;
        }

        textArea.onKey(key, mods);
        return true;
    }

    @Override
    public boolean onChar(int codepoint) {
        textArea.onCharInput(codepoint);
        return true;
    }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        vScroll.onMouseButton(button, action, mods, mx, my);
        hScroll.onMouseButton(button, action, mods, mx, my);
        if (!vScroll.isDragging() && !hScroll.isDragging()) {
            textArea.onMouseButton(button, action, mods, mx, my);
        }
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        vScroll.onCursorPos(mx, my);
        hScroll.onCursorPos(mx, my);
        if (!vScroll.isDragging() && !hScroll.isDragging()) {
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
    public void onCursorEnter(boolean entered) {
        textArea.onCursorEnter(entered);
    }

    @Override
    public void render(float width, float height) {
        float hudH = window.getHudHeight();
        float bottomBar = hudH + SCROLLBAR_SIZE;

        textArea.setBounds(0, 0, width - SCROLLBAR_SIZE, height - bottomBar);
        vScroll.setBounds(width - SCROLLBAR_SIZE, 0, SCROLLBAR_SIZE, height - bottomBar);
        hScroll.setBounds(0, height - SCROLLBAR_SIZE, width - SCROLLBAR_SIZE, SCROLLBAR_SIZE);

        textArea.render();

        vScroll.setContent(textArea.getContentRows(), textArea.getVisibleRows());
        vScroll.setValue(textArea.getScrollRow());
        hScroll.setContent(textArea.getContentCols(), textArea.getVisibleCols());
        hScroll.setValue(textArea.getScrollCol());

        vScroll.render();
        hScroll.render();
    }

    @Override
    public String hudText() {
        String name = filePath.getFileName().toString();
        String dirtyMark = isDirty() ? " [modified]" : "";
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(dirtyMark);
        sb.append(" | Esc Close | Ctrl+M Module | Ctrl+S Save");
        return sb.toString();
    }

    boolean isDirty() {
        return !textArea.getText().equals(savedContent);
    }

    Path getFilePath() { return filePath; }

    private void saveFile(boolean saveAs) {
        Path target = filePath;
        if (saveAs) {
            String path = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_saveFileDialog(
                    "Save File", target.toString(), Main.SPN_FILTER, "SPN / Text files");
            if (path == null) return;
            target = Path.of(path);
        }
        try {
            String content = textArea.getText();
            Files.writeString(target, content);
            savedContent = content;
            window.flash("Saved: " + target.getFileName(), false);
        } catch (IOException e) {
            window.flash("Error: " + e.getMessage(), true);
        }
    }

    /**
     * Simple confirmation when closing a dirty stacked file.
     */
    static class ConfirmFileCloseMode implements Mode {
        private static final float BG_R = 0.10f, BG_G = 0.10f, BG_B = 0.12f;
        private static final float TEXT_R = 0.85f, TEXT_G = 0.85f, TEXT_B = 0.85f;
        private static final float FONT_SCALE = 0.35f;

        private final EditorWindow window;
        private final FileViewMode fileMode;

        ConfirmFileCloseMode(EditorWindow window, FileViewMode fileMode) {
            this.window = window;
            this.fileMode = fileMode;
        }

        @Override
        public boolean onKey(int key, int scancode, int action, int mods) {
            if (action != GLFW_PRESS) return true;
            switch (key) {
                case GLFW_KEY_Y, GLFW_KEY_ENTER -> {
                    window.popMode(); // pop confirm
                    window.popMode(); // pop FileViewMode
                }
                case GLFW_KEY_N, GLFW_KEY_ESCAPE -> window.popMode(); // just pop confirm
            }
            return true;
        }

        @Override public boolean onChar(int codepoint) { return true; }
        @Override public boolean onMouseButton(int button, int action, int mods, double mx, double my) { return true; }
        @Override public boolean onCursorPos(double mx, double my) { return true; }
        @Override public boolean onScroll(double xoff, double yoff) { return true; }

        @Override
        public void render(float width, float height) {
            window.getFont().drawRect(0, 0, width, height, BG_R, BG_G, BG_B);
            float y = height * 0.4f;
            float x = 40f;
            window.getFont().drawText(
                    "Unsaved changes in " + fileMode.getFilePath().getFileName()
                    + ". Close anyway?",
                    x, y, FONT_SCALE, TEXT_R, TEXT_G, TEXT_B);
        }

        @Override
        public String hudText() {
            return "Y/Enter Discard | N/Esc Cancel";
        }
    }
}
