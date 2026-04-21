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

    /**
     * Checks whether raw file content read from disk matches the normalized
     * baseline we cached at load/save time. Used by the refresh-from-disk
     * path to decide between silent reload, conflict prompt, and no-op.
     */
    public boolean diskContentMatchesBaseline(String rawDiskContent) {
        return normalizeNewlines(rawDiskContent).equals(savedContent);
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
        // Pass the absolute path so TypeGraph nodes from this file carry the
        // same string that cross-file imports do — needed for go-to-def.
        String fileName = filePath != null
                ? filePath.toAbsolutePath().normalize().toString()
                : "untitled";
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
            window.reloadActiveTabFromDisk();
            return true;
        }
        if (ctrl && key == GLFW_KEY_E && action == GLFW_PRESS) {
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

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        if (button == GLFW_MOUSE_BUTTON_LEFT
                && action == GLFW_PRESS
                && (mods & GLFW_MOD_CONTROL) != 0
                && clickIsInsideText(mx, my)) {
            goToDefinition(mx, my);
            return true;
        }
        return super.onMouseButton(button, action, mods, mx, my);
    }

    private boolean clickIsInsideText(double mx, double my) {
        float x = textArea.getBoundsX();
        float y = textArea.getBoundsY();
        float w = textArea.getBoundsW();
        float h = textArea.getBoundsH();
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ── Go to definition ───────────────────────────────────────────────
    //
    // Ctrl+left-click resolves the identifier under the cursor to its
    // declaration using the compile-time dispatch info, then navigates:
    //   (1) declaration is in this file        → scroll and select in place
    //   (2) declaration is elsewhere in module → open/switch tab and select
    //   (3) name imported from a builtin/other → scroll to the `import` line
    //   (4) unresolved                          → Ctrl+T the clicked row
    //
    // Type references are handled by the same flow via
    // {@link spn.lang.IncrementalParser.TypeRefAnnotation}s emitted by the
    // parser at every named-type use site. Cross-module type navigation uses
    // the annotation's importRange to jump directly to the import statement
    // that brought the type into scope.

    private void goToDefinition(double mx, double my) {
        int[] pos = textArea.screenToDocPos(mx, my);
        int row = pos[0], col = pos[1];
        String name = textArea.wordAt(row, col);

        // (1)/(2): compile-time dispatch is authoritative when the click
        // falls inside a recorded call site — this covers operators like
        // `+` where there's no word under the cursor. Use the overload the
        // parser chose, not a name-only TypeGraph lookup.
        var disp = diagnosticEngine.dispatchAnnotationAt(row, col);
        if (disp != null && disp.targetFile() != null && disp.targetRange() != null) {
            var tr = disp.targetRange();
            if (navigateToDeclaration(disp.targetFile(), tr.startLine(), tr.startCol(),
                    name != null ? name : extractOpFromDescription(disp.description()))) {
                return;
            }
        }

        // Type reference: the parser records every named-type use site with
        // the resolved declaration (when available in the current parse) and
        // the import statement that brought the name into scope (when
        // imported). This covers cross-file and cross-module type navigation
        // that the name-only fallback below can't handle.
        var typeRef = diagnosticEngine.typeReferenceAt(row, col);
        if (typeRef != null) {
            if (typeRef.targetFile() != null && typeRef.targetRange() != null) {
                var tr = typeRef.targetRange();
                if (navigateToDeclaration(typeRef.targetFile(),
                        tr.startLine(), tr.startCol(), typeRef.typeName())) {
                    return;
                }
            }
            // Outside the current module (or no source available): jump to the
            // import that brought the type in. Select the type name within the
            // import line if it's listed (selective import), else select the
            // import keyword so the user sees which statement it was.
            if (typeRef.importRange() != null) {
                int importLine = typeRef.importRange().startLine();
                if (!selectWordOnLine(importLine, typeRef.typeName())) {
                    var ir = typeRef.importRange();
                    textArea.selectRange(ir.startLine(), ir.startCol(),
                            ir.endLine(), ir.endCol());
                }
                return;
            }
        }

        // No call-site context. If the click isn't on a word either,
        // nothing further to do — fall through to the type-info hint.
        if (name == null || name.isEmpty()) {
            activateTypeInfoAt(row);
            return;
        }

        // Local scope first: `let NAME = ...`, tuple destructuring, or a
        // function parameter within the enclosing declaration. Locals live
        // in the TypeGraph (kind LOCAL_BINDING / PARAMETER), so the lookup
        // uses parser-verified positions and stops at scope boundaries.
        spn.lang.TypeGraph tg = diagnosticEngine.getTypeGraph();
        String absFile = filePath != null
                ? filePath.toAbsolutePath().normalize().toString() : null;
        if (tg != null && absFile != null) {
            // Parser is 1-based line, editor is 0-based: convert.
            spn.lang.TypeGraph.Node local = tg.findLocalInScope(
                    absFile, row + 1, col, name);
            if (local != null && local.nameRange().isKnown()) {
                var r = local.nameRange().toEditorCoords();
                selectNameAt(textArea, r.startLine(), r.startCol(), name);
                return;
            }
        }

        // Fall back to TypeGraph by name. Covers type annotations, bare
        // references, factory names, etc.
        if (tg != null) {
            spn.lang.TypeGraph.Node chosen = pickDeclarationNode(tg.lookup(name));
            if (chosen != null && chosen.file() != null && chosen.nameRange().isKnown()) {
                var editorRange = chosen.nameRange().toEditorCoords();
                if (navigateToDeclaration(chosen.file(),
                        editorRange.startLine(), editorRange.startCol(), name)) {
                    return;
                }
            }
        }

        // (3): name resolved via an `import` but has no source declaration
        // (Java-implemented builtin, or any import line the user may want
        // to see). Scroll to the import statement that brought it in.
        if (scrollToImportFor(name)) return;

        // (4): give up on navigation; show type info for the clicked row
        // so the user still learns something from the click.
        activateTypeInfoAt(row);
    }

    /** Pull the operator/method name out of a dispatch description like
     *  "+(Rational, Rational)" or "Rational.neg()" for use when selecting
     *  at the target site. Falls back to null, which makes selectNameAt
     *  highlight a single character. */
    private static String extractOpFromDescription(String desc) {
        if (desc == null) return null;
        int paren = desc.indexOf('(');
        int dot = desc.indexOf('.');
        int cut = paren < 0 ? dot : (dot < 0 ? paren : Math.min(paren, dot));
        if (cut <= 0) return null;
        return desc.substring(0, cut);
    }

    /**
     * Prefer nodes in the current file, then same-module files, then any
     * remaining nodes. Same-name overloads are disambiguated at call sites
     * by {@link #goToDefinition} via the dispatch annotation; this ordering
     * only applies when the click has no call-site context.
     */
    private spn.lang.TypeGraph.Node pickDeclarationNode(java.util.List<spn.lang.TypeGraph.Node> candidates) {
        if (candidates.isEmpty()) return null;
        String currentFileStr = filePath != null ? filePath.toAbsolutePath().toString() : null;
        spn.lang.TypeGraph.Node sameFile = null;
        spn.lang.TypeGraph.Node sameModule = null;
        for (var n : candidates) {
            if (n.file() == null) continue;
            if (currentFileStr != null && n.file().equals(currentFileStr)) {
                sameFile = n;
                break;
            }
            if (sameModule == null && isInsideCurrentModule(n.file())) {
                sameModule = n;
            }
        }
        if (sameFile != null) return sameFile;
        if (sameModule != null) return sameModule;
        for (var n : candidates) if (n.file() != null) return n;
        return null;
    }

    private boolean isInsideCurrentModule(String fileStr) {
        if (moduleContext == null) return false;
        try {
            java.nio.file.Path candidate = java.nio.file.Path.of(fileStr).toAbsolutePath().normalize();
            java.nio.file.Path root = moduleContext.getRoot().toAbsolutePath().normalize();
            return candidate.startsWith(root);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean navigateToDeclaration(String fileStr, int line, int col, String name) {
        java.nio.file.Path target;
        try {
            target = java.nio.file.Path.of(fileStr).toAbsolutePath().normalize();
        } catch (Exception e) {
            return false;
        }

        java.nio.file.Path current = filePath != null ? filePath.toAbsolutePath().normalize() : null;
        boolean sameFile = current != null && current.equals(target);

        if (sameFile) {
            selectNameAt(textArea, line, col, name);
            return true;
        }

        // Cross-file: only navigate if the target is inside the current
        // module. Other modules (stdlib source we don't ship, external
        // dependencies) fall through to the import-scroll path.
        if (!isInsideCurrentModule(fileStr)) return false;

        try {
            window.loadFile(target);
        } catch (java.io.IOException e) {
            window.flash("Could not open " + target.getFileName() + ": " + e.getMessage(), true);
            return false;
        }
        EditorTab landed = window.getActiveEditorTab();
        if (landed == null) return false;
        selectNameAt(landed.getTextArea(), line, col, name);
        return true;
    }

    private static void selectNameAt(TextArea ta, int row, int col, String name) {
        int[] bounds = ta.wordBoundsPublic(row, col);
        if (bounds == null) {
            // Declaration position didn't land on a word char (e.g. an
            // operator). Select a single character at the recorded col.
            ta.selectRange(row, col, row, Math.max(col + 1, col));
            return;
        }
        ta.selectRange(row, bounds[0], row, bounds[1]);
    }

    private boolean scrollToImportFor(String name) {
        int lineCount = textArea.getBuffer().lineCount();
        for (int row = 0; row < lineCount; row++) {
            String line = textArea.getBuffer().getLine(row);
            String trimmed = line.stripLeading();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
            if (!trimmed.startsWith("import ") && !trimmed.equals("import")) {
                // Imports live in a prefix block; once we see real code,
                // stop scanning.
                break;
            }
            if (selectWordOnLine(row, name)) return true;
        }
        return false;
    }

    /** Select {@code name} on {@code row} if it appears there as a whole word.
     *  Returns false if the row is out of range or the word isn't present. */
    private boolean selectWordOnLine(int row, String name) {
        if (row < 0 || row >= textArea.getBuffer().lineCount()) return false;
        String line = textArea.getBuffer().getLine(row);
        int idx = findWholeWord(line, name);
        if (idx < 0) return false;
        textArea.selectRange(row, idx, row, idx + name.length());
        return true;
    }

    private static int findWholeWord(String line, String name) {
        int from = 0;
        while (true) {
            int idx = line.indexOf(name, from);
            if (idx < 0) return -1;
            boolean leftOk = idx == 0 || !isIdentChar(line.charAt(idx - 1));
            int after = idx + name.length();
            boolean rightOk = after >= line.length() || !isIdentChar(line.charAt(after));
            if (leftOk && rightOk) return idx;
            from = idx + 1;
        }
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private void activateTypeInfoAt(int row) {
        textArea.setCursorPosition(row, textArea.getCursorCol());
        typeInfoActive = true;
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
