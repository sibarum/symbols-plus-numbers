package spn.gui;

import spn.claude.ClaudeSettings;
import spn.claude.ClaudeSettingsStore;
import spn.fonts.SdfFontRenderer;

import java.io.IOException;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Form mode for editing Claude settings. Up/Down navigate between fields;
 * typing edits the current field; Space toggles the checkbox; Ctrl+S saves
 * and closes; Esc cancels without saving.
 *
 * <p>Edits are made on a working copy and only flushed to {@link ClaudeSettings}
 * (in-memory) and {@link ClaudeSettingsStore} (disk) on Save.
 */
class ClaudeSettingsMode implements Mode {

    private static final float FONT_SCALE = 0.32f;
    private static final float SMALL_SCALE = 0.22f;
    private static final float ROW_GAP = 14f;
    private static final float FIELD_GAP = 6f;

    private static final float BG_R = 0.10f, BG_G = 0.10f, BG_B = 0.12f;
    private static final float TITLE_R = 0.85f, TITLE_G = 0.85f, TITLE_B = 0.90f;
    private static final float LABEL_R = 0.60f, LABEL_G = 0.60f, LABEL_B = 0.65f;
    private static final float VALUE_R = 0.85f, VALUE_G = 0.85f, VALUE_B = 0.85f;
    private static final float ACTIVE_BG_R = 0.18f, ACTIVE_BG_G = 0.22f, ACTIVE_BG_B = 0.32f;
    private static final float CURSOR_R = 0.90f, CURSOR_G = 0.90f, CURSOR_B = 0.30f;

    private enum FieldKind { TEXT, MASKED, CHOICE, CHECKBOX, TEXTAREA }

    /** A single editable form field. */
    private static final class Field {
        final String label;
        final FieldKind kind;
        final StringBuilder value;
        final String[] choices;
        boolean bool;
        int cursor;        // for TEXT/MASKED/TEXTAREA
        int choiceIndex;   // for CHOICE

        Field(String label, FieldKind kind, String initial) {
            this.label = label;
            this.kind = kind;
            this.value = new StringBuilder(initial == null ? "" : initial);
            this.cursor = this.value.length();
            this.choices = null;
        }

        Field(String label, String[] choices, String initial) {
            this.label = label;
            this.kind = FieldKind.CHOICE;
            this.value = new StringBuilder(initial == null ? "" : initial);
            this.choices = choices;
            this.choiceIndex = indexOf(choices, initial);
        }

        Field(String label, boolean initial) {
            this.label = label;
            this.kind = FieldKind.CHECKBOX;
            this.value = new StringBuilder();
            this.choices = null;
            this.bool = initial;
        }

        private static int indexOf(String[] choices, String value) {
            for (int i = 0; i < choices.length; i++) if (choices[i].equals(value)) return i;
            return 0;
        }
    }

    private static final String[] MODELS = {
            "claude-opus-4-7", "claude-opus-4-6", "claude-sonnet-4-6", "claude-haiku-4-5"
    };
    private static final String[] EFFORTS = {"low", "medium", "high", "xhigh", "max"};

    private final EditorWindow window;
    private final SdfFontRenderer font;
    private final ClaudeSettings settings;
    private final ClaudeSettingsStore store;
    private final Field[] fields;
    private int activeField;
    private String message = "";

    ClaudeSettingsMode(EditorWindow window, ClaudeSettings settings, ClaudeSettingsStore store) {
        this.window = window;
        this.font = window.getFont();
        this.settings = settings;
        this.store = store;
        this.fields = new Field[] {
                new Field("API Key",            FieldKind.MASKED,   settings.apiKey()),
                new Field("Model",              MODELS,             settings.model()),
                new Field("Effort",             EFFORTS,            settings.effort()),
                new Field("System Prompt",      FieldKind.TEXTAREA, settings.systemPrompt()),
                new Field("Allow IDE Mutations", settings.allowIdeMutations()),
        };
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return true;
        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

        if (ctrl && key == GLFW_KEY_S) { saveAndClose(); return true; }
        if (key == GLFW_KEY_ESCAPE) { window.popMode(); return true; }

        if (key == GLFW_KEY_DOWN || key == GLFW_KEY_TAB) {
            activeField = (activeField + 1) % fields.length;
            return true;
        }
        if (key == GLFW_KEY_UP) {
            activeField = (activeField - 1 + fields.length) % fields.length;
            return true;
        }

        Field f = fields[activeField];
        switch (f.kind) {
            case TEXT, MASKED -> {
                if (key == GLFW_KEY_BACKSPACE && f.cursor > 0) {
                    f.value.deleteCharAt(f.cursor - 1);
                    f.cursor--;
                } else if (key == GLFW_KEY_DELETE && f.cursor < f.value.length()) {
                    f.value.deleteCharAt(f.cursor);
                } else if (key == GLFW_KEY_LEFT && f.cursor > 0) {
                    f.cursor--;
                } else if (key == GLFW_KEY_RIGHT && f.cursor < f.value.length()) {
                    f.cursor++;
                } else if (key == GLFW_KEY_HOME) {
                    f.cursor = 0;
                } else if (key == GLFW_KEY_END) {
                    f.cursor = f.value.length();
                } else if (ctrl && key == GLFW_KEY_V) {
                    String clip = window.getClipboardText();
                    if (clip != null && !clip.isEmpty()) {
                        // Single-line fields: collapse newlines to spaces
                        clip = clip.replace("\r", "").replace('\n', ' ');
                        f.value.insert(f.cursor, clip);
                        f.cursor += clip.length();
                    }
                }
            }
            case TEXTAREA -> {
                if (key == GLFW_KEY_BACKSPACE && f.cursor > 0) {
                    f.value.deleteCharAt(f.cursor - 1);
                    f.cursor--;
                } else if (key == GLFW_KEY_DELETE && f.cursor < f.value.length()) {
                    f.value.deleteCharAt(f.cursor);
                } else if (key == GLFW_KEY_LEFT && f.cursor > 0) {
                    f.cursor--;
                } else if (key == GLFW_KEY_RIGHT && f.cursor < f.value.length()) {
                    f.cursor++;
                } else if (key == GLFW_KEY_HOME) {
                    f.cursor = 0;
                } else if (key == GLFW_KEY_END) {
                    f.cursor = f.value.length();
                } else if (key == GLFW_KEY_ENTER) {
                    f.value.insert(f.cursor, '\n');
                    f.cursor++;
                } else if (ctrl && key == GLFW_KEY_V) {
                    String clip = window.getClipboardText();
                    if (clip != null && !clip.isEmpty()) {
                        clip = clip.replace("\r\n", "\n");
                        f.value.insert(f.cursor, clip);
                        f.cursor += clip.length();
                    }
                }
            }
            case CHOICE -> {
                if (key == GLFW_KEY_LEFT) {
                    f.choiceIndex = (f.choiceIndex - 1 + f.choices.length) % f.choices.length;
                    f.value.setLength(0);
                    f.value.append(f.choices[f.choiceIndex]);
                } else if (key == GLFW_KEY_RIGHT || key == GLFW_KEY_SPACE) {
                    f.choiceIndex = (f.choiceIndex + 1) % f.choices.length;
                    f.value.setLength(0);
                    f.value.append(f.choices[f.choiceIndex]);
                }
            }
            case CHECKBOX -> {
                if (key == GLFW_KEY_SPACE || key == GLFW_KEY_ENTER) {
                    f.bool = !f.bool;
                }
            }
        }
        return true;
    }

    @Override
    public boolean onChar(int codepoint) {
        Field f = fields[activeField];
        if (f.kind == FieldKind.TEXT || f.kind == FieldKind.MASKED || f.kind == FieldKind.TEXTAREA) {
            char[] chars = Character.toChars(codepoint);
            f.value.insert(f.cursor, chars);
            f.cursor += chars.length;
        }
        return true;
    }

    @Override public boolean onMouseButton(int button, int action, int mods, double mx, double my) { return true; }
    @Override public boolean onCursorPos(double mx, double my) { return true; }
    @Override public boolean onScroll(double xoff, double yoff) { return true; }

    @Override
    public void render(float width, float height) {
        font.drawRect(0, 0, width, height, BG_R, BG_G, BG_B);

        float titleScale = FONT_SCALE * 1.2f;
        float titleY = 60f;
        font.drawText("Claude Settings", 60f, titleY, titleScale, TITLE_R, TITLE_G, TITLE_B);

        float y = titleY + font.getLineHeight(titleScale) * 1.4f;
        float labelW = 220f;
        float fieldX = 60f + labelW;
        float fieldW = width - fieldX - 60f;

        for (int i = 0; i < fields.length; i++) {
            Field f = fields[i];
            float rowH = (f.kind == FieldKind.TEXTAREA) ? font.getLineHeight(FONT_SCALE) * 5f
                                                        : font.getLineHeight(FONT_SCALE) * 1.2f;
            if (i == activeField) {
                font.drawRect(60f - 8f, y - 4f, width - 120f + 16f, rowH + 8f,
                        ACTIVE_BG_R, ACTIVE_BG_G, ACTIVE_BG_B);
            }
            font.drawText(f.label, 60f, y + font.getLineHeight(FONT_SCALE) - 2f,
                    FONT_SCALE, LABEL_R, LABEL_G, LABEL_B);
            renderFieldValue(f, fieldX, y, fieldW, rowH, i == activeField);
            y += rowH + ROW_GAP;
        }

        y += FIELD_GAP;
        font.drawText("Settings file: " + store.settingsPath(),
                60f, y, SMALL_SCALE, LABEL_R, LABEL_G, LABEL_B);
        y += font.getLineHeight(SMALL_SCALE) * 1.4f;
        if (!message.isEmpty()) {
            font.drawText(message, 60f, y, SMALL_SCALE, 0.55f, 0.85f, 0.55f);
        }
    }

    private void renderFieldValue(Field f, float x, float y, float w, float rowH, boolean active) {
        float ty = y + font.getLineHeight(FONT_SCALE) - 2f;
        switch (f.kind) {
            case TEXT -> renderInline(f.value.toString(), f.cursor, x, ty, active);
            case MASKED -> {
                String masked = "•".repeat(f.value.length());
                renderInline(masked, f.cursor, x, ty, active);
            }
            case CHOICE -> {
                String txt = "◀  " + f.value.toString() + "  ▶";
                font.drawText(txt, x, ty, FONT_SCALE, VALUE_R, VALUE_G, VALUE_B);
            }
            case CHECKBOX -> {
                String txt = (f.bool ? "[x]" : "[ ]") + "  (Space to toggle)";
                font.drawText(txt, x, ty, FONT_SCALE, VALUE_R, VALUE_G, VALUE_B);
            }
            case TEXTAREA -> {
                String[] lines = f.value.toString().split("\n", -1);
                float lineH = font.getLineHeight(FONT_SCALE);
                int maxLines = Math.max(1, (int) (rowH / lineH));
                // Render last `maxLines` lines so the cursor area is visible.
                int start = Math.max(0, lines.length - maxLines);
                int cursorLine = lineForCursor(f.value.toString(), f.cursor) - start;
                int cursorCol = colForCursor(f.value.toString(), f.cursor);
                for (int i = start; i < lines.length; i++) {
                    float ly = y + (i - start + 1) * lineH - 2f;
                    font.drawText(lines[i], x, ly, FONT_SCALE, VALUE_R, VALUE_G, VALUE_B);
                    if (active && (i - start) == cursorLine) {
                        String prefix = lines[i].substring(0, Math.min(cursorCol, lines[i].length()));
                        float cx = x + font.getTextWidth(prefix, FONT_SCALE);
                        font.drawText("│", cx, ly, FONT_SCALE, CURSOR_R, CURSOR_G, CURSOR_B);
                    }
                }
            }
        }
    }

    private void renderInline(String text, int cursor, float x, float y, boolean active) {
        font.drawText(text, x, y, FONT_SCALE, VALUE_R, VALUE_G, VALUE_B);
        if (active) {
            String prefix = text.substring(0, Math.min(cursor, text.length()));
            float cx = x + font.getTextWidth(prefix, FONT_SCALE);
            font.drawText("│", cx, y, FONT_SCALE, CURSOR_R, CURSOR_G, CURSOR_B);
        }
    }

    private static int lineForCursor(String text, int cursor) {
        int n = 0;
        for (int i = 0; i < cursor && i < text.length(); i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    private static int colForCursor(String text, int cursor) {
        int last = text.lastIndexOf('\n', Math.max(0, cursor - 1));
        return cursor - (last + 1);
    }

    @Override
    public String hudText() {
        return "Claude Settings | Up/Down Field | Ctrl+S Save | Esc Cancel";
    }

    @Override
    public float[] hudBackground() {
        return EditorWindow.HUD_TAKEOVER_TINT;
    }

    private void saveAndClose() {
        settings.setApiKey(fields[0].value.toString().trim());
        settings.setModel(fields[1].value.toString());
        settings.setEffort(fields[2].value.toString());
        settings.setSystemPrompt(fields[3].value.toString());
        settings.setAllowIdeMutations(fields[4].bool);
        try {
            store.save(settings);
            window.popMode();
            window.flash("Claude settings saved", false);
        } catch (IOException e) {
            message = "Save failed: " + e.getMessage();
        }
    }
}
