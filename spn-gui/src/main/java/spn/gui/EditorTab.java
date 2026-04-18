package spn.gui;

import spn.gui.diagnostic.ChangeOverlay;
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
            "F5 Run | Ctrl+N New | Ctrl+O Open | Ctrl+G Logs | Ctrl+/ Help";

    private Path filePath;
    private String savedContent = "";
    private ModuleContext moduleContext;

    // Real-time diagnostics
    private final DiagnosticOverlay diagnosticOverlay = new DiagnosticOverlay();
    private final DiagnosticEngine diagnosticEngine = new DiagnosticEngine(diagnosticOverlay);
    private final ChangeOverlay changeOverlay = new ChangeOverlay();

    // Find/replace state — active when findActive == true.
    // In replace mode, editingReplace == true and keystrokes go to the replace field.
    private boolean findActive;
    private boolean editingReplace;
    private final StringBuilder findQuery = new StringBuilder();
    private final StringBuilder replaceQuery = new StringBuilder();

    // Type-info HUD mode: Ctrl+T shows dispatch info for the current line.
    // Dismissed by any subsequent keystroke.
    private boolean typeInfoActive;

    EditorTab(EditorWindow window) {
        super(window);

        // Connect overlays to TextArea
        textArea.setDiagnosticOverlay(diagnosticOverlay);
        textArea.setChangeOverlay(changeOverlay);
        textArea.setOnEditCallback(() ->
                diagnosticEngine.notifyEdit(glfwGetTime()));
    }

    // ── File state ─────────────────────────────────────────────────────

    public Path getFilePath() { return filePath; }

    public void setFilePath(Path path) { this.filePath = path; }

    public String getSavedContent() { return savedContent; }

    public void setSavedContent(String content) {
        // Normalize line endings to LF so the snapshot matches TextArea.getText(),
        // which joins lines with \n regardless of source encoding. Without this,
        // a CRLF file loaded on Windows would read back as dirty immediately.
        String normalized = normalizeNewlines(content);
        this.savedContent = normalized;
        changeOverlay.snapshotSaved(normalized);
    }

    public ModuleContext getModuleContext() { return moduleContext; }

    public void setModuleContext(ModuleContext ctx) {
        this.moduleContext = ctx;
        if (ctx != null) {
            diagnosticEngine.setModuleRoot(ctx.getRoot(), ctx.getNamespace());
        }
    }

    public TextArea getTextArea() { return textArea; }

    public void loadContent(String content) {
        String normalized = normalizeNewlines(content);
        textArea.setText(normalized);
        savedContent = normalized;
        changeOverlay.snapshotSaved(normalized);
    }

    private static String normalizeNewlines(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace("\r", "\n");
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
        String source = textArea.getText();
        String fileName = filePath != null ? filePath.getFileName().toString() : "untitled";
        diagnosticEngine.update(now, source, fileName);

        // Update change indicators (modified-since-save highlighting)
        changeOverlay.update(source);

        layoutAndRender(x, y, width, height);
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return false;

        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;
        boolean shift = (mods & GLFW_MOD_SHIFT) != 0;

        // Find mode intercepts most input
        if (findActive) {
            if (key == GLFW_KEY_ESCAPE) { closeFind(); return true; }
            if (ctrl && key == GLFW_KEY_H && action == GLFW_PRESS) {
                // Toggle into replace mode (or focus replace field)
                editingReplace = true;
                return true;
            }
            if (key == GLFW_KEY_TAB && action == GLFW_PRESS) {
                editingReplace = !editingReplace;
                return true;
            }
            if (key == GLFW_KEY_BACKSPACE) {
                StringBuilder q = editingReplace ? replaceQuery : findQuery;
                if (q.length() > 0) {
                    q.deleteCharAt(q.length() - 1);
                    if (!editingReplace) textArea.setSearchTerm(q.toString());
                }
                return true;
            }
            if (key == GLFW_KEY_ENTER) {
                if (editingReplace && !replaceQuery.isEmpty()) {
                    if (shift) {
                        // Shift+Enter in replace mode: replace all
                        int n = textArea.replaceAllMatches(replaceQuery.toString());
                        window.flash("Replaced " + n + " occurrence(s)", false);
                    } else {
                        textArea.replaceCurrentMatch(replaceQuery.toString());
                    }
                } else {
                    // Navigate matches
                    if (shift) textArea.jumpToPrevMatch();
                    else textArea.jumpToNextMatch();
                }
                return true;
            }
            // Swallow all other keys while in find mode (except pure modifier presses)
            return true;
        }

        // Ctrl+T: type-info HUD (shows dispatch for current line)
        if (ctrl && key == GLFW_KEY_T && action == GLFW_PRESS) {
            typeInfoActive = true;
            return true;
        }
        // Any other key dismisses type-info mode
        if (typeInfoActive) {
            typeInfoActive = false;
            // Don't consume — let the key propagate to its normal handler
        }

        // Ctrl+F opens find mode
        if (ctrl && key == GLFW_KEY_F && action == GLFW_PRESS) {
            openFind(false);
            return true;
        }
        if (ctrl && key == GLFW_KEY_H && action == GLFW_PRESS) {
            openFind(true);
            return true;
        }

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
        if (key == GLFW_KEY_F5 && action == GLFW_PRESS) {
            if (shift) window.runWithTrace();
            else window.runCurrentFile();
            return true;
        }
        if (ctrl && key == GLFW_KEY_R && action == GLFW_PRESS) {
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
        // Escape: don't consume — let TabViewMode handle tab close
        if (key == GLFW_KEY_ESCAPE) return false;
        textArea.onKey(key, mods);
        return true;
    }

    @Override
    public boolean onChar(int codepoint) {
        if (findActive) {
            StringBuilder q = editingReplace ? replaceQuery : findQuery;
            q.appendCodePoint(codepoint);
            if (!editingReplace) textArea.setSearchTerm(findQuery.toString());
            return true;
        }
        textArea.onCharInput(codepoint);
        return true;
    }

    // ── Find / replace ─────────────────────────────────────────────────

    void openFind(boolean replaceMode) {
        findActive = true;
        editingReplace = replaceMode;
        findQuery.setLength(0);
        replaceQuery.setLength(0);
        // Seed the find query with the current selection or word under cursor
        String seed = textArea.getSelectedText();
        if (seed == null || seed.isEmpty()) seed = textArea.wordAtCursor();
        if (seed != null && !seed.isEmpty() && !seed.contains("\n")) {
            findQuery.append(seed);
            textArea.setSearchTerm(seed);
        }
    }

    private void closeFind() {
        findActive = false;
        editingReplace = false;
        findQuery.setLength(0);
        replaceQuery.setLength(0);
        textArea.setSearchTerm(null);
    }

    /** Expose for HUD rendering. */
    public boolean isFindActive() { return findActive; }

    /** Clear all diagnostic and module caches, force a full re-parse. */
    void invalidateDiagnostics() {
        diagnosticEngine.invalidateAll();
        diagnosticOverlay.setMarks(java.util.List.of());
    }

    /** Evict a module from this tab's diagnostic cache so it gets re-loaded fresh. */
    void invalidateModule(String namespace) {
        diagnosticEngine.invalidateModule(namespace);
        diagnosticEngine.notifyEdit(org.lwjgl.glfw.GLFW.glfwGetTime());
    }

    /** HUD content while find mode is active. */
    private String findHudText() {
        StringBuilder sb = new StringBuilder();
        int total = textArea.getSearchMatchCount();
        int current = textArea.getCurrentSearchIndex();

        // Show the find field (cursor shown as │ when that field is active)
        String findShown = findQuery.toString() + (editingReplace ? "" : "\u2502");
        sb.append("Find: ").append(findShown.isEmpty() ? " " : findShown);

        if (total > 0) {
            sb.append(" | ").append(current + 1).append("/").append(total);
        } else if (!findQuery.isEmpty()) {
            sb.append(" | no matches");
        }

        String replaceShown = replaceQuery.toString() + (editingReplace ? "\u2502" : "");
        sb.append(" | Replace: ").append(replaceShown.isEmpty() ? " " : replaceShown);

        sb.append(" | Enter ").append(editingReplace ? "Replace" : "Next");
        sb.append(" | Shift+Enter ").append(editingReplace ? "ReplaceAll" : "Prev");
        sb.append(" | Tab ").append(editingReplace ? "Find" : "Replace");
        sb.append(" | Esc Close");
        return sb.toString();
    }

    @Override
    public String hudText() {
        // Find/replace mode takes over the HUD
        if (findActive) return findHudText();

        // Type-info mode: Ctrl+T shows all dispatches on the current line
        if (typeInfoActive) {
            String info = diagnosticEngine.dispatchesOnLine(textArea.getCursorRow());
            if (info != null) {
                return "Types: " + info;
            }
            return "Types: (no dispatches on this line)";
        }

        StringBuilder sb = new StringBuilder();

        // Change and error summary
        int changedLines = changeOverlay.changedLineCount();
        int errorCount = (int) diagnosticOverlay.getMarks().stream()
                .filter(DiagnosticMark::isActive).count();

        if (changedLines > 0 || errorCount > 0) {
            if (changedLines > 0) {
                sb.append("+").append(changedLines).append(" changed");
            }
            if (errorCount > 0) {
                if (!sb.isEmpty()) sb.append(" | ");
                sb.append(errorCount).append(" error").append(errorCount > 1 ? "s" : "");
            }
            sb.append(" | ");
        }

        // Save hint: show only when the buffer needs saving — either it's
        // dirty relative to disk, or it hasn't been saved to a file yet.
        boolean needsSave = isDirty() || filePath == null
                || !java.nio.file.Files.exists(filePath);
        if (needsSave) {
            sb.append("Ctrl+S Save | ");
        }

        // If the cursor is inside an active error region, surface its message.
        if (errorCount > 0) {
            DiagnosticMark atCursor = diagnosticOverlay.findAt(
                    textArea.getCursorRow(), textArea.getCursorCol());
            if (atCursor != null) {
                // Sanitise: HUD splits segments on " | ", so collapse any pipes.
                String msg = atCursor.diagnostic().message().replace('|', '/');
                sb.append("Error: ").append(msg).append(" | ");
            }
        }

        // Hints
        if (textArea.getText().isBlank()) {
            sb.append("*Ctrl+N New | ");
        }
        if (moduleContext != null) {
            sb.append("*Ctrl+M Module ").append(moduleContext.getNamespace()).append(" | ");
        }

        // Undo branch info (compact)
        UndoManager.Info info = textArea.getUndoInfo();
        if (info.branches() > 1) {
            sb.append("Branch ").append(info.activeBranch())
              .append("/").append(info.branches())
              .append(" | Ctrl+[ ] | ");
        }

        sb.append(BASE_SHORTCUTS);
        return sb.toString();
    }
}
