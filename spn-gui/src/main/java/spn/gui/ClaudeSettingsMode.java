package spn.gui;

import spn.claude.ClaudeSettings;
import spn.claude.ClaudeSettingsStore;
import spn.fonts.SdfFontRenderer;
import spn.stdui.widget.ScrollbarTheme;

import java.io.IOException;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Form mode for editing Claude settings. Tab / Shift+Tab navigate between
 * fields; Up/Down navigate when the active field is single-line / non-text;
 * Ctrl+S saves and closes; Esc cancels without saving.
 *
 * <p>Editable text fields delegate to {@link TextArea} so they get the same
 * cursor handling, selection, clipboard, and undo as the rest of the IDE.
 *
 * <p>Note: the API key is displayed in plaintext during entry. The settings
 * file ({@code ~/.spn/claude.settings}) stores it in plaintext too, so
 * masking would be cosmetic only.
 */
class ClaudeSettingsMode implements Mode {

    private static final float FONT_SCALE = 0.32f;
    private static final float SMALL_SCALE = 0.22f;
    private static final float ROW_GAP = 14f;
    // Tight enough to hug the rendered text + cursor without obvious top/bottom
    // padding. Combined with TextArea.setPadding(4f) below, this gives
    // visibleRows >= 1 with the cursor's 9px-below-baseline extension fitting
    // inside the field box.
    private static final float SINGLE_LINE_H = 38f;
    private static final float MULTI_LINE_H = 160f;
    private static final float COMPACT_PAD = 4f;

    private static final float BG_R = 0.10f, BG_G = 0.10f, BG_B = 0.12f;
    private static final float TITLE_R = 0.85f, TITLE_G = 0.85f, TITLE_B = 0.90f;
    private static final float LABEL_R = 0.60f, LABEL_G = 0.60f, LABEL_B = 0.65f;
    private static final float VALUE_R = 0.85f, VALUE_G = 0.85f, VALUE_B = 0.85f;
    private static final float ACTIVE_BG_R = 0.18f, ACTIVE_BG_G = 0.22f, ACTIVE_BG_B = 0.32f;
    private static final float FIELD_BG_R = 0.06f, FIELD_BG_G = 0.06f, FIELD_BG_B = 0.08f;

    private static final int FIELD_API_KEY = 0;
    private static final int FIELD_MODEL = 1;
    private static final int FIELD_EFFORT = 2;
    private static final int FIELD_SYSTEM_PROMPT = 3;
    private static final int FIELD_ALLOW_IDE_MUT = 4;
    private static final int FIELD_COUNT = 5;

    private static final String[] MODELS = {
            "claude-opus-4-7", "claude-opus-4-6", "claude-sonnet-4-6", "claude-haiku-4-5"
    };
    private static final String[] EFFORTS = {"low", "medium", "high", "xhigh", "max"};

    private final EditorWindow window;
    private final SdfFontRenderer font;
    private final ClaudeSettings settings;
    private final ClaudeSettingsStore store;

    private final TextArea apiKeyArea;
    private final TextArea systemPromptArea;
    private final Scrollbar systemPromptVScroll;
    private final Scrollbar systemPromptHScroll;
    private int modelIndex;
    private int effortIndex;
    private boolean allowIdeMutations;

    private int activeField;
    private String message = "";

    /** Switch active field and update which TextArea shows its caret. */
    private void setActiveField(int field) {
        this.activeField = field;
        apiKeyArea.setCursorEnabled(field == FIELD_API_KEY);
        systemPromptArea.setCursorEnabled(field == FIELD_SYSTEM_PROMPT);
    }

    // Last-rendered field bounds [x, y, w, h], used for click hit-testing.
    // Populated each render frame; entries default to (0,0,0,0).
    private final float[][] fieldBounds = new float[FIELD_COUNT][4];

    ClaudeSettingsMode(EditorWindow window, ClaudeSettings settings, ClaudeSettingsStore store) {
        this.window = window;
        this.font = window.getFont();
        this.settings = settings;
        this.store = store;

        this.apiKeyArea = makeTextArea(window);
        this.apiKeyArea.setPlainTextMode(true);
        this.apiKeyArea.setPadding(COMPACT_PAD);
        this.apiKeyArea.setText(settings.apiKey());

        this.systemPromptArea = makeTextArea(window);
        this.systemPromptArea.setPlainTextMode(true);
        this.systemPromptArea.setPadding(COMPACT_PAD);
        this.systemPromptArea.setText(settings.systemPrompt());

        // Initial focus state: only the API key field shows a caret.
        this.apiKeyArea.setCursorEnabled(true);
        this.systemPromptArea.setCursorEnabled(false);

        ScrollbarTheme sbTheme = ScrollbarTheme.dark();
        this.systemPromptVScroll = new Scrollbar(font, Scrollbar.Orientation.VERTICAL);
        this.systemPromptVScroll.setTheme(sbTheme);
        this.systemPromptVScroll.setOnChange(v -> systemPromptArea.setScrollRow(v));
        this.systemPromptHScroll = new Scrollbar(font, Scrollbar.Orientation.HORIZONTAL);
        this.systemPromptHScroll.setTheme(sbTheme);
        this.systemPromptHScroll.setOnChange(v -> systemPromptArea.setScrollCol(v));

        this.modelIndex = indexOf(MODELS, settings.model());
        this.effortIndex = indexOf(EFFORTS, settings.effort());
        this.allowIdeMutations = settings.allowIdeMutations();
    }

    private static TextArea makeTextArea(EditorWindow window) {
        TextArea ta = new TextArea(window.getFont());
        ta.setClipboard(new TextArea.ClipboardHandler() {
            @Override public void set(String text) { glfwSetClipboardString(window.getHandle(), text); }
            @Override public String get()          { return glfwGetClipboardString(window.getHandle()); }
        });
        return ta;
    }

    private static int indexOf(String[] choices, String v) {
        for (int i = 0; i < choices.length; i++) if (choices[i].equals(v)) return i;
        return 0;
    }

    private boolean isMultilineField(int field) {
        return field == FIELD_SYSTEM_PROMPT;
    }

    private boolean isTextField(int field) {
        return field == FIELD_API_KEY || field == FIELD_SYSTEM_PROMPT;
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return true;
        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;
        boolean shift = (mods & GLFW_MOD_SHIFT) != 0;

        if (ctrl && key == GLFW_KEY_S) { saveAndClose(); return true; }
        if (key == GLFW_KEY_ESCAPE) { window.popMode(); return true; }

        // Tab navigates fields universally.
        if (key == GLFW_KEY_TAB) {
            int dir = shift ? -1 : 1;
            setActiveField((activeField + dir + FIELD_COUNT) % FIELD_COUNT);
            return true;
        }

        // Up/Down navigate fields only when the current field doesn't use them
        // for cursor movement (i.e. not a multi-line text area).
        if (!isMultilineField(activeField) && (key == GLFW_KEY_UP || key == GLFW_KEY_DOWN)) {
            int dir = key == GLFW_KEY_DOWN ? 1 : -1;
            setActiveField((activeField + dir + FIELD_COUNT) % FIELD_COUNT);
            return true;
        }

        switch (activeField) {
            case FIELD_API_KEY -> {
                // Single-line: drop Enter so it can't insert a newline.
                if (key == GLFW_KEY_ENTER) return true;
                apiKeyArea.onKey(key, mods);
            }
            case FIELD_MODEL -> {
                if (key == GLFW_KEY_LEFT) {
                    modelIndex = (modelIndex - 1 + MODELS.length) % MODELS.length;
                } else if (key == GLFW_KEY_RIGHT || key == GLFW_KEY_SPACE) {
                    modelIndex = (modelIndex + 1) % MODELS.length;
                }
            }
            case FIELD_EFFORT -> {
                if (key == GLFW_KEY_LEFT) {
                    effortIndex = (effortIndex - 1 + EFFORTS.length) % EFFORTS.length;
                } else if (key == GLFW_KEY_RIGHT || key == GLFW_KEY_SPACE) {
                    effortIndex = (effortIndex + 1) % EFFORTS.length;
                }
            }
            case FIELD_SYSTEM_PROMPT -> systemPromptArea.onKey(key, mods);
            case FIELD_ALLOW_IDE_MUT -> {
                if (key == GLFW_KEY_SPACE || key == GLFW_KEY_ENTER) {
                    allowIdeMutations = !allowIdeMutations;
                }
            }
        }
        return true;
    }

    @Override
    public boolean onChar(int codepoint) {
        if (activeField == FIELD_API_KEY) apiKeyArea.onCharInput(codepoint);
        else if (activeField == FIELD_SYSTEM_PROMPT) systemPromptArea.onCharInput(codepoint);
        return true;
    }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        // Click into a field's bounds (including its scrollbars) to focus it.
        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
            if (inFieldBounds(FIELD_API_KEY, mx, my)) {
                setActiveField(FIELD_API_KEY);
                apiKeyArea.onMouseButton(button, action, mods, mx, my);
                return true;
            }
            if (inFieldBounds(FIELD_SYSTEM_PROMPT, mx, my)) {
                setActiveField(FIELD_SYSTEM_PROMPT);
                ScrollableTab.dispatchMousePressToScrolled(
                        systemPromptArea, systemPromptVScroll, systemPromptHScroll,
                        button, action, mods, mx, my);
                return true;
            }
        }
        // Forward release/move events to whichever area is active so drag-select
        // and scrollbar drags clear cleanly.
        if (activeField == FIELD_API_KEY) {
            apiKeyArea.onMouseButton(button, action, mods, mx, my);
        } else if (activeField == FIELD_SYSTEM_PROMPT) {
            systemPromptVScroll.onMouseButton(button, action, mods, mx, my);
            systemPromptHScroll.onMouseButton(button, action, mods, mx, my);
            systemPromptArea.onMouseButton(button, action, mods, mx, my);
        }
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) {
        if (activeField == FIELD_API_KEY) {
            apiKeyArea.onCursorPos(mx, my);
        } else if (activeField == FIELD_SYSTEM_PROMPT) {
            ScrollableTab.dispatchCursorToScrolled(
                    systemPromptArea, systemPromptVScroll, systemPromptHScroll, mx, my);
        }
        return true;
    }

    @Override
    public boolean onScroll(double xoff, double yoff) {
        if (activeField == FIELD_SYSTEM_PROMPT) systemPromptArea.onScroll(xoff, yoff);
        return true;
    }

    private boolean inFieldBounds(int field, double mx, double my) {
        float[] r = fieldBounds[field];
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    @Override
    public void render(float width, float height) {
        font.drawRect(0, 0, width, height, BG_R, BG_G, BG_B);

        float titleScale = FONT_SCALE * 1.2f;
        float titleY = 60f;
        font.drawText("Claude Settings", 60f, titleY, titleScale, TITLE_R, TITLE_G, TITLE_B);

        float y = titleY + font.getLineHeight(titleScale) * 1.4f;
        float labelX = 60f;
        float labelW = 220f;
        float fieldX = labelX + labelW;
        float fieldW = width - fieldX - 60f;

        y = renderField(FIELD_API_KEY, "API Key", labelX, y, fieldX, fieldW, SINGLE_LINE_H, width);
        y = renderField(FIELD_MODEL, "Model", labelX, y, fieldX, fieldW, SINGLE_LINE_H, width);
        y = renderField(FIELD_EFFORT, "Effort", labelX, y, fieldX, fieldW, SINGLE_LINE_H, width);
        y = renderField(FIELD_SYSTEM_PROMPT, "System Prompt", labelX, y, fieldX, fieldW, MULTI_LINE_H, width);
        y = renderField(FIELD_ALLOW_IDE_MUT, "Allow IDE Mutations", labelX, y, fieldX, fieldW, SINGLE_LINE_H, width);

        y += 12f;
        font.drawText("Settings file: " + store.settingsPath(),
                labelX, y, SMALL_SCALE, LABEL_R, LABEL_G, LABEL_B);
        y += font.getLineHeight(SMALL_SCALE) * 1.4f;
        if (!message.isEmpty()) {
            font.drawText(message, labelX, y, SMALL_SCALE, 0.55f, 0.85f, 0.55f);
        }
    }

    private float renderField(int field, String label, float labelX, float y,
                              float fieldX, float fieldW, float fieldH, float width) {
        boolean active = (field == activeField);
        float rowH = fieldH;

        if (active) {
            font.drawRect(labelX - 8f, y - 4f, width - 120f + 16f, rowH + 8f,
                    ACTIVE_BG_R, ACTIVE_BG_G, ACTIVE_BG_B);
        }
        font.drawText(label, labelX, y + font.getLineHeight(FONT_SCALE) - 2f,
                FONT_SCALE, LABEL_R, LABEL_G, LABEL_B);

        // Record this field's value-area bounds for click hit-testing.
        fieldBounds[field][0] = fieldX;
        fieldBounds[field][1] = y;
        fieldBounds[field][2] = fieldW;
        fieldBounds[field][3] = fieldH;

        switch (field) {
            case FIELD_API_KEY -> renderTextAreaField(apiKeyArea, fieldX, y, fieldW, fieldH);
            case FIELD_MODEL -> renderChoice(MODELS[modelIndex], fieldX, y, fieldH);
            case FIELD_EFFORT -> renderChoice(EFFORTS[effortIndex], fieldX, y, fieldH);
            case FIELD_SYSTEM_PROMPT -> renderScrolledTextAreaField(
                    systemPromptArea, systemPromptVScroll, systemPromptHScroll,
                    fieldX, y, fieldW, fieldH);
            case FIELD_ALLOW_IDE_MUT -> renderCheckbox(allowIdeMutations, fieldX, y, fieldH);
        }

        return y + rowH + ROW_GAP;
    }

    private void renderTextAreaField(TextArea ta, float x, float y, float w, float h) {
        font.drawRect(x, y, w, h, FIELD_BG_R, FIELD_BG_G, FIELD_BG_B);
        ta.setBounds(x + 2f, y + 2f, w - 4f, h - 4f);
        ta.render();
    }

    private void renderScrolledTextAreaField(TextArea ta, Scrollbar v, Scrollbar h,
                                              float x, float y, float w, float fieldH) {
        font.drawRect(x, y, w, fieldH, FIELD_BG_R, FIELD_BG_G, FIELD_BG_B);
        ScrollableTab.renderWithScrollbars(ta, v, h, x + 2f, y + 2f, w - 4f, fieldH - 4f, 0);
    }

    private void renderChoice(String value, float x, float y, float h) {
        float ty = y + font.getLineHeight(FONT_SCALE) - 2f;
        // The SDF font doesn't include U+25C0/U+25B6 (◀ ▶), so use ASCII brackets.
        String txt = "<  " + value + "  >";
        font.drawText(txt, x, ty, FONT_SCALE, VALUE_R, VALUE_G, VALUE_B);
    }

    private void renderCheckbox(boolean checked, float x, float y, float h) {
        float ty = y + font.getLineHeight(FONT_SCALE) - 2f;
        String txt = (checked ? "[x]" : "[ ]") + "  (Space to toggle)";
        font.drawText(txt, x, ty, FONT_SCALE, VALUE_R, VALUE_G, VALUE_B);
    }

    @Override
    public String hudText() {
        return "Claude Settings | Tab Field | Ctrl+S Save | Esc Cancel";
    }

    @Override
    public float[] hudBackground() {
        return EditorWindow.HUD_TAKEOVER_TINT;
    }

    private void saveAndClose() {
        settings.setApiKey(apiKeyArea.getText().trim());
        settings.setModel(MODELS[modelIndex]);
        settings.setEffort(EFFORTS[effortIndex]);
        settings.setSystemPrompt(systemPromptArea.getText());
        settings.setAllowIdeMutations(allowIdeMutations);
        try {
            store.save(settings);
            window.popMode();
            window.flash("Claude settings saved", false);
        } catch (IOException e) {
            message = "Save failed: " + e.getMessage();
        }
    }
}
