package spn.gui;

import spn.gui.lang.ContextDetector;
import spn.gui.lang.EditorContext;
import spn.gui.lang.Suggestion;
import spn.gui.lang.SuggestionProvider;
import spn.gui.template.TemplateCatalog;
import spn.gui.template.TemplateDef;
import spn.gui.template.TemplateOverlay;
import spn.gui.template.TemplateReconstructor;

import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * The default editing mode: text editing with scrollbars, syntax highlighting,
 * contextual suggestions, template overlay, and all file/run/sample key bindings.
 *
 * <p>This mode wraps the existing {@link TextArea} and {@link Scrollbar}
 * components, delegating input and rendering to them. When a template is
 * active, input is routed to the {@link TemplateOverlay} first.
 */
public class EditorMode implements Mode {

    private static final float SCROLLBAR_SIZE = 12f;

    static final String BASE_SHORTCUTS =
            "F1 Shapes | F2 Plot | F5 Run | Ctrl+N New | Ctrl+O Open | Ctrl+S Save";

    private final EditorWindow window;
    private final TextArea textArea;
    private final Scrollbar vScroll;
    private final Scrollbar hScroll;
    private final ContextDetector contextDetector;
    private final SuggestionProvider suggestionProvider;

    private List<Suggestion> currentSuggestions = List.of();

    // Active template overlay (null when not in template mode)
    private TemplateOverlay activeTemplate;

    EditorMode(EditorWindow window, TextArea textArea,
               Scrollbar vScroll, Scrollbar hScroll) {
        this.window = window;
        this.textArea = textArea;
        this.vScroll = vScroll;
        this.hScroll = hScroll;
        this.contextDetector = new ContextDetector();
        this.suggestionProvider = new SuggestionProvider();
    }

    // ---- Mode interface -------------------------------------------------

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return false;

        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

        // If template is active, route input there first
        if (activeTemplate != null) {
            TemplateOverlay.Result result = activeTemplate.onKey(key, mods);
            switch (result) {
                case CONSUMED -> { return true; }
                case COMMIT -> { commitTemplate(); return true; }
                case CANCEL -> { cancelTemplate(); return true; }
                case NOT_HANDLED -> {
                    // Fall through to normal key handling
                }
            }
        }

        // Sample shortcuts (F1, F2, ...)
        if (!ctrl && action == GLFW_PRESS) {
            for (EditorWindow.Sample s : EditorWindow.SAMPLES) {
                if (key == s.key()) { window.openSample(s); return true; }
            }
        }

        if (ctrl && key == GLFW_KEY_N && action == GLFW_PRESS) {
            Main.instance.spawnWindow();
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
        // Template mode (Ctrl+Comma)
        if (ctrl && key == GLFW_KEY_COMMA && action == GLFW_PRESS) {
            activateTemplate();
            return true;
        }
        // Contextual snippets (Ctrl+1..9 on blank line)
        if (ctrl && action == GLFW_PRESS
                && textArea.isCurrentLineBlank()
                && !textArea.hasSelection()
                && tryContextShortcut(key)) {
            return true;
        }
        // Action menu
        if (ctrl && key == GLFW_KEY_P && action == GLFW_PRESS) {
            window.pushMode(new ActionMenuMode(window, window.getActionRegistry()));
            return true;
        }

        textArea.onKey(key, mods);
        return true;
    }

    @Override
    public boolean onChar(int codepoint) {
        if (activeTemplate != null) {
            activeTemplate.onChar(codepoint);
            return true;
        }
        textArea.onCharInput(codepoint);
        return true;
    }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        // Clicking while template active → commit the template first
        if (activeTemplate != null) {
            commitTemplate();
        }
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
        float hudH = window.getHud().preferredHeight();
        float bottomBar = hudH + SCROLLBAR_SIZE;

        textArea.setBounds(0, 0, width - SCROLLBAR_SIZE, height - bottomBar);
        vScroll.setBounds(width - SCROLLBAR_SIZE, 0, SCROLLBAR_SIZE, height - bottomBar);
        hScroll.setBounds(0, height - SCROLLBAR_SIZE, width - SCROLLBAR_SIZE, SCROLLBAR_SIZE);

        textArea.render();

        // Render template overlay on top of text
        if (activeTemplate != null) {
            activeTemplate.render(window.getFont(), textArea);
        }

        vScroll.setContent(textArea.getContentRows(), textArea.getVisibleRows());
        vScroll.setValue(textArea.getScrollRow());
        hScroll.setContent(textArea.getContentCols(), textArea.getVisibleCols());
        hScroll.setValue(textArea.getScrollCol());

        vScroll.render();
        hScroll.render();
    }

    @Override
    public String hudText() {
        // Template mode HUD
        if (activeTemplate != null) {
            return "Tab Next | Shift+Tab Prev | Enter Commit | Esc Cancel | Arrows leave";
        }

        // Detect semantic context and update available suggestions
        EditorContext ctx = contextDetector.detect(
                textArea.getBuffer(), textArea.getHighlightCache(), textArea.getCursorRow());
        currentSuggestions = suggestionProvider.getSuggestions(ctx);

        StringBuilder sb = new StringBuilder();

        // Show numbered contextual suggestions on blank lines
        if (textArea.isCurrentLineBlank() && !textArea.hasSelection()
                && !currentSuggestions.isEmpty()) {
            for (int i = 0; i < currentSuggestions.size(); i++) {
                if (i > 0) sb.append(" | ");
                sb.append(i + 1).append(' ').append(currentSuggestions.get(i).label());
            }
            sb.append(" | ").append(BASE_SHORTCUTS);
            return sb.toString();
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

    // ---- Template mode --------------------------------------------------

    private void activateTemplate() {
        int cursorRow = textArea.getCursorRow();
        int cursorCol = textArea.getCursorCol();
        String word = textArea.wordAtCursor();

        // Case 1: Cursor is on a keyword that matches a template → new template
        if (word != null && !word.isEmpty()) {
            TemplateDef def = TemplateCatalog.forKeyword(word);
            if (def != null) {
                activateNewTemplate(def, cursorRow, cursorCol);
                return;
            }
        }

        // Case 2: Try to reconstruct from existing code on this line
        TemplateReconstructor.Reconstruction recon = TemplateReconstructor.tryReconstruct(
                textArea.getBuffer(), textArea.getHighlightCache(), cursorRow);
        if (recon != null) {
            activateReconstructedTemplate(recon, cursorRow, cursorCol);
            return;
        }

        window.getHud().flash("No template available here", true);
    }

    /**
     * Activate a template for a specific keyword. Called from the action menu
     * after the keyword has been inserted at the cursor.
     */
    void activateTemplateForKeyword(String keyword) {
        TemplateDef def = TemplateCatalog.forKeyword(keyword);
        if (def == null) return;
        activateNewTemplate(def, textArea.getCursorRow(), textArea.getCursorCol());
    }

    private void activateNewTemplate(TemplateDef def, int cursorRow, int cursorCol) {
        String line = textArea.getBuffer().getLine(cursorRow);

        // Find the word boundaries and delete the keyword
        int wordStart = cursorCol;
        while (wordStart > 0 && isWordChar(line.charAt(wordStart - 1))) wordStart--;
        int wordEnd = cursorCol;
        while (wordEnd < line.length() && isWordChar(line.charAt(wordEnd))) wordEnd++;

        String removedText = line.substring(wordStart, wordEnd);

        // Delete the keyword from the buffer
        textArea.getBuffer().deleteRange(cursorRow, wordStart, cursorRow, wordEnd);

        // Create the template overlay
        activeTemplate = new TemplateOverlay(def, cursorRow, wordStart,
                removedText, cursorRow, cursorCol);
    }

    private void activateReconstructedTemplate(TemplateReconstructor.Reconstruction recon,
                                                int cursorRow, int cursorCol) {
        // Capture the original text that will be replaced
        TextBuffer buffer = textArea.getBuffer();
        StringBuilder original = new StringBuilder();
        for (int r = recon.startRow(); r <= recon.endRow(); r++) {
            if (r > recon.startRow()) original.append("\n");
            original.append(buffer.getLine(r));
        }
        String originalText = original.toString();

        // Delete the existing construct from the buffer
        buffer.deleteRange(recon.startRow(), recon.startCol(), recon.endRow(), recon.endCol());

        // Create the template overlay with pre-populated fields
        activeTemplate = new TemplateOverlay(recon.def(), recon.startRow(), recon.startCol(),
                originalText, cursorRow, cursorCol);
        activeTemplate.setFieldValues(recon.fieldValues());
    }

    private void commitTemplate() {
        if (activeTemplate == null) return;
        int[] end = activeTemplate.commit(textArea.getBuffer(), textArea.getUndoManager());
        textArea.setCursorPosition(end[0], end[1]);
        activeTemplate = null;
    }

    private void cancelTemplate() {
        if (activeTemplate == null) return;
        int[] pos = activeTemplate.cancel(textArea.getBuffer());
        textArea.setCursorPosition(pos[0], pos[1]);
        activeTemplate = null;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
    }

    // ---- Internal -------------------------------------------------------

    private boolean tryContextShortcut(int key) {
        int index = key - GLFW_KEY_1;
        if (index < 0 || index > 8) return false;
        if (index >= currentSuggestions.size()) return false;
        textArea.insertSnippet(currentSuggestions.get(index).snippet());
        return true;
    }
}
