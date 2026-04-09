package spn.gui;

import spn.stdui.buffer.UndoManager;

import static org.lwjgl.glfw.GLFW.*;

/**
 * The default editing mode: text editing with scrollbars, syntax highlighting,
 * and file/run/sample key bindings.
 */
public class EditorMode implements Mode {

    private static final float SCROLLBAR_SIZE = 12f;

    static final String BASE_SHORTCUTS =
            "F5 Run | Ctrl+N New | Ctrl+O Open | Ctrl+S Save | Ctrl+G Logs";

    private final EditorWindow window;
    private final TextArea textArea;
    private final Scrollbar vScroll;
    private final Scrollbar hScroll;

    EditorMode(EditorWindow window, TextArea textArea,
               Scrollbar vScroll, Scrollbar hScroll) {
        this.window = window;
        this.textArea = textArea;
        this.vScroll = vScroll;
        this.hScroll = hScroll;
    }

    // ---- Mode interface -------------------------------------------------

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return false;

        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

        // Sample shortcuts (F1, F2, ...)
        if (!ctrl && action == GLFW_PRESS) {
            for (EditorWindow.Sample s : EditorWindow.SAMPLES) {
                if (key == s.key()) { window.openSample(s); return true; }
            }
        }

        if (ctrl && key == GLFW_KEY_N && action == GLFW_PRESS) {
            window.pushLegacyMode(new NewMenuMode(window));
            return true;
        }
        if (ctrl && key == GLFW_KEY_O && action == GLFW_PRESS) {
            window.openFile();
            return true;
        }
        if (ctrl && key == GLFW_KEY_S && action == GLFW_PRESS) {
            window.saveFile((mods & GLFW_MOD_SHIFT) != 0);
            return true;
        }
        if ((key == GLFW_KEY_F5 || (ctrl && key == GLFW_KEY_R)) && action == GLFW_PRESS) {
            window.runCurrentFile();
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
        // Import
        if (ctrl && key == GLFW_KEY_I && action == GLFW_PRESS) {
            window.pushLegacyMode(new ImportMode(window));
            return true;
        }
        // Logs
        if (ctrl && key == GLFW_KEY_G && action == GLFW_PRESS) {
            window.pushLegacyMode(new LogViewMode(window));
            return true;
        }
        // Module browser
        if (ctrl && key == GLFW_KEY_M && action == GLFW_PRESS) {
            ModuleContext ctx = window.getModuleContext();
            if (ctx != null) {
                window.pushLegacyMode(new ModuleMode(window, ctx));
            } else {
                window.flash(
                        "No module loaded \u2014 cannot find module.spn", true);
            }
            return true;
        }
        // Action menu
        if (ctrl && key == GLFW_KEY_P && action == GLFW_PRESS) {
            window.pushLegacyMode(new ActionMenuMode(window, window.getActionRegistry()));
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
        StringBuilder sb = new StringBuilder();

        // Promoted hints
        if (textArea.getText().isBlank()) {
            sb.append("*Ctrl+N New | ");
        }
        if (window.getModuleContext() != null) {
            sb.append("*Ctrl+M Module Explorer | ");
        }

        // Undo state summary
        UndoManager.Info info = textArea.getUndoInfo();
        if (info.depth() > 0 || info.branches() > 0) {
            sb.append("History depth ").append(info.depth());
            if (info.branches() > 1) {
                sb.append(" | Branch ").append(info.activeBranch())
                  .append(" of ").append(info.branches())
                  .append(" | Ctrl+[ Prev | Ctrl+] Next");
            } else if (info.branches() == 1) {
                sb.append(" | Ctrl+Z Undo | Ctrl+Y Redo");
            }
            if (!info.canUndo()) {
                sb.append(" | (at root)");
            }
            sb.append(" | ").append(BASE_SHORTCUTS);
        } else {
            sb.append(BASE_SHORTCUTS);
        }

        return sb.toString();
    }
}
