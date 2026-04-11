package spn.gui;

import spn.gui.diagnostic.DiagnosticEngine;
import spn.gui.diagnostic.DiagnosticMark;
import spn.gui.diagnostic.DiagnosticOverlay;
import spn.stdui.buffer.UndoManager;

import java.nio.file.Path;

import static org.lwjgl.glfw.GLFW.*;

/**
 * A file editor tab. Each EditorTab owns its own TextArea, scrollbars,
 * file path, and saved content snapshot for dirty detection.
 */
public class EditorTab extends ScrollableTab {

    static final String BASE_SHORTCUTS =
            "F5 Run | Ctrl+N New | Ctrl+O Open | Ctrl+S Save | Ctrl+G Logs | Ctrl+/ Help";

    private Path filePath;
    private String savedContent = "";
    private ModuleContext moduleContext;

    // Real-time diagnostics
    private final DiagnosticOverlay diagnosticOverlay = new DiagnosticOverlay();
    private final DiagnosticEngine diagnosticEngine = new DiagnosticEngine(diagnosticOverlay);

    EditorTab(EditorWindow window) {
        super(window);

        // Connect diagnostic overlay to TextArea
        textArea.setDiagnosticOverlay(diagnosticOverlay);
        textArea.setOnEditCallback(() ->
                diagnosticEngine.notifyEdit(glfwGetTime()));
    }

    // ── File state ─────────────────────────────────────────────────────

    public Path getFilePath() { return filePath; }

    public void setFilePath(Path path) { this.filePath = path; }

    public String getSavedContent() { return savedContent; }

    public void setSavedContent(String content) { this.savedContent = content; }

    public ModuleContext getModuleContext() { return moduleContext; }

    public void setModuleContext(ModuleContext ctx) { this.moduleContext = ctx; }

    public TextArea getTextArea() { return textArea; }

    public void loadContent(String content) {
        textArea.setText(content);
        savedContent = content;
    }

    // ── Tab interface ──────────────────────────────────────────────────

    @Override
    public String label() {
        if (filePath == null) return "untitled";
        String name = filePath.getFileName().toString();
        return isDirty() ? name + " *" : name;
    }

    @Override
    public boolean isDirty() {
        return !textArea.getText().equals(savedContent);
    }

    @Override
    public void render(float x, float y, float width, float height) {
        // Tick the diagnostic engine (debounced re-parse)
        double now = glfwGetTime();
        String fileName = filePath != null ? filePath.getFileName().toString() : "untitled";
        diagnosticEngine.update(now, textArea.getText(), fileName);

        layoutAndRender(x, y, width, height);
    }

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
            window.openNewTab();
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
        if (ctrl && key == GLFW_KEY_LEFT_BRACKET && action == GLFW_PRESS) {
            textArea.switchUndoBranch(-1);
            return true;
        }
        if (ctrl && key == GLFW_KEY_RIGHT_BRACKET && action == GLFW_PRESS) {
            textArea.switchUndoBranch(1);
            return true;
        }
        if (ctrl && key == GLFW_KEY_I && action == GLFW_PRESS) {
            window.pushLegacyMode(new ImportMode(window));
            return true;
        }
        if (ctrl && key == GLFW_KEY_G && action == GLFW_PRESS) {
            window.openLogTab();
            return true;
        }
        if (ctrl && key == GLFW_KEY_M && action == GLFW_PRESS) {
            if (moduleContext != null) {
                window.pushLegacyMode(new ModuleMode(window, moduleContext));
            } else {
                window.flash("No module loaded \u2014 cannot find module.spn", true);
            }
            return true;
        }
        if (ctrl && key == GLFW_KEY_P && action == GLFW_PRESS) {
            window.pushLegacyMode(new ActionMenuMode(window, window.getActionRegistry()));
            return true;
        }
        if (ctrl && key == GLFW_KEY_SLASH && action == GLFW_PRESS) {
            window.pushLegacyMode(new HelpMode(window, window.getActionRegistry()));
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
    public String hudText() {
        DiagnosticMark mark = diagnosticOverlay.findOnRow(textArea.getCursorRow());
        if (mark != null && mark.isActive()) {
            return "Error: " + mark.diagnostic().message();
        }

        StringBuilder sb = new StringBuilder();

        if (textArea.getText().isBlank()) {
            sb.append("*Ctrl+N New | ");
        }
        if (moduleContext != null) {
            sb.append("*Ctrl+M Module Explorer | ");
        }

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
