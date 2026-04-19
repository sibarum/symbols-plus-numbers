package spn.gui.template;

import spn.fonts.SdfFontRenderer;
import spn.gui.EditorWindow;
import spn.gui.Mode;
import spn.gui.TextArea;
import spn.stdui.buffer.TextBuffer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Template instantiation mode: the user tabs through placeholder fields,
 * fills them in, and submits to produce the final text.
 *
 * <p>Name fields with the same label are linked — editing one updates all
 * occurrences. String fields produce quoted output. Number fields are validated.
 */
public class TemplateInstantiationMode implements Mode {

    private static final float FIELD_BG_R = 0.20f, FIELD_BG_G = 0.25f, FIELD_BG_B = 0.35f;
    private static final float ACTIVE_BG_R = 0.25f, ACTIVE_BG_G = 0.35f, ACTIVE_BG_B = 0.55f;

    /** A unique field the user fills in. May map to multiple positions in the text. */
    private static class Field {
        final String type;   // "name", "string", "number"
        final String label;
        String value;
        // Positions in the buffer: each is [row, startCol, endCol]
        final List<int[]> positions = new ArrayList<>();

        Field(String type, String label) {
            this.type = type;
            this.label = label;
            this.value = label; // initial display value = label
        }
    }

    private final EditorWindow window;
    private final SdfFontRenderer font;
    private final TextArea textArea;
    private final List<Field> fields;
    private int activeIndex = 0;

    public TemplateInstantiationMode(EditorWindow window, String templateText) {
        this.window = window;
        this.font = window.getFont();
        this.textArea = window.getTextArea();

        // Parse placeholders
        List<SpntParser.Placeholder> placeholders = SpntParser.parse(templateText);
        String editableText = SpntParser.toEditableText(templateText, placeholders);

        // Load into editor
        textArea.setText(editableText);

        // Build unique field list and compute positions in the editable text
        Map<String, Field> fieldMap = new LinkedHashMap<>();
        // Track offset shift as we go from template coords to editable coords
        int shift = 0;
        for (SpntParser.Placeholder p : placeholders) {
            String key = p.type() + ":" + p.label();
            Field field = fieldMap.computeIfAbsent(key,
                    k -> new Field(p.type(), p.label()));

            // Position in editable text: original start minus cumulative shift
            int editStart = p.start() - shift;
            int editEnd = editStart + p.label().length();
            shift += (p.end() - p.start()) - p.label().length();

            // Convert character offset to (row, col)
            int[] pos = offsetToRowCol(editableText, editStart, editEnd);
            field.positions.add(pos);
        }
        this.fields = new ArrayList<>(fieldMap.values());

        // Focus first field
        if (!fields.isEmpty()) {
            selectField(0);
        }
    }

    @Override
    public boolean onKey(int key, int scancode, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_REPEAT) return true;

        boolean shift = (mods & GLFW_MOD_SHIFT) != 0;

        boolean ctrl = (mods & GLFW_MOD_CONTROL) != 0;

        // File operations pass through
        if (ctrl && key == GLFW_KEY_S) {
            window.saveFile((mods & GLFW_MOD_SHIFT) != 0);
            return true;
        }

        // Ctrl+V — paste clipboard into the active field
        if (ctrl && key == GLFW_KEY_V) {
            String clip = window.getClipboardText();
            if (!clip.isEmpty()) {
                Field f = fields.get(activeIndex);
                updateField(activeIndex, f.value + clip);
            }
            return true;
        }

        switch (key) {
            case GLFW_KEY_TAB -> {
                if (shift) {
                    if (activeIndex > 0) selectField(activeIndex - 1);
                } else {
                    if (activeIndex < fields.size() - 1) {
                        selectField(activeIndex + 1);
                    } else {
                        // Tab past last field → done, pop mode, keep text
                        window.popMode();
                    }
                }
                return true;
            }
            case GLFW_KEY_ENTER, GLFW_KEY_ESCAPE -> {
                // Done — pop mode, text stays in editor ready to save
                window.popMode();
                return true;
            }
            case GLFW_KEY_BACKSPACE -> {
                Field f = fields.get(activeIndex);
                if (!f.value.isEmpty()) {
                    String newVal = f.value.substring(0, f.value.length() - 1);
                    updateField(activeIndex, newVal);
                }
                return true;
            }
        }
        return true; // swallow other keys
    }

    @Override
    public boolean onChar(int codepoint) {
        String ch = new String(Character.toChars(codepoint));
        Field f = fields.get(activeIndex);
        // If still showing the label placeholder, clear it on first keystroke
        if (f.value.equals(f.label)) {
            updateField(activeIndex, ch);
        } else {
            updateField(activeIndex, f.value + ch);
        }
        return true;
    }

    @Override
    public boolean onMouseButton(int button, int action, int mods, double mx, double my) {
        return true;
    }

    @Override
    public boolean onCursorPos(double mx, double my) { return true; }

    @Override
    public boolean onScroll(double xoff, double yoff) {
        textArea.onScroll(xoff, yoff);
        return true;
    }

    @Override
    public void render(float width, float height) {
        float hudH = window.getHudHeight();
        float scrollbarSize = 12f;
        float bottomBar = hudH + scrollbarSize;

        textArea.setBounds(0, 0, width, height - bottomBar);

        // Draw field highlights BEFORE text so text renders on top
        // (need to call setBounds first so metrics are correct)
        float cellW = textArea.getCellWidth();
        float cellH = textArea.getCellHeight();
        float originX = textArea.getTextOriginX();
        float originY = textArea.getTextOriginY();
        float hlOffset = textArea.getHighlightOffsetY();
        int scrollRow = textArea.getScrollRowVal();

        for (int fi = 0; fi < fields.size(); fi++) {
            Field f = fields.get(fi);
            boolean isActive = (fi == activeIndex);
            float bgR = isActive ? ACTIVE_BG_R : FIELD_BG_R;
            float bgG = isActive ? ACTIVE_BG_G : FIELD_BG_G;
            float bgB = isActive ? ACTIVE_BG_B : FIELD_BG_B;

            for (int[] pos : f.positions) {
                int row = pos[0], startCol = pos[1], endCol = pos[2];
                int visRow = row - scrollRow;
                if (visRow < 0 || visRow >= textArea.getVisibleRows()) continue;
                float rx = originX + startCol * cellW;
                float ry = originY + visRow * cellH + hlOffset;
                float rw = (endCol - startCol) * cellW;
                font.drawRect(rx, ry, Math.max(rw, cellW), cellH, bgR, bgG, bgB);
            }
        }

        // Text renders on top of field highlights
        textArea.render();
    }

    @Override
    public String hudText() {
        if (fields.isEmpty()) return "Enter Done | Ctrl+S Save";
        Field f = fields.get(activeIndex);
        return "Tab Next | Shift+Tab Prev | " + f.type + ": " + f.label
                + " (" + (activeIndex + 1) + "/" + fields.size() + ")"
                + " | Enter Done | Ctrl+S Save";
    }

    // ---- Field updates (with linked name sync) ----

    private void updateField(int index, String newValue) {
        Field f = fields.get(index);
        String oldValue = f.value;
        f.value = newValue;
        int lenDelta = newValue.length() - oldValue.length();

        // Update all positions for this field in the buffer
        TextBuffer buffer = textArea.getBuffer();
        // Process positions in reverse order to avoid offset shifting issues
        List<int[]> allPositions = new ArrayList<>(f.positions);
        allPositions.sort((a, b) -> {
            int cmp = Integer.compare(b[0], a[0]); // reverse row
            return cmp != 0 ? cmp : Integer.compare(b[1], a[1]); // reverse col
        });

        for (int[] pos : allPositions) {
            int row = pos[0], startCol = pos[1], endCol = pos[2];
            buffer.deleteRange(row, startCol, row, endCol);
            if (!newValue.isEmpty()) {
                buffer.insertText(row, startCol, newValue);
            }
        }

        // Update position endCols for this field
        for (int[] pos : f.positions) {
            pos[2] = pos[1] + newValue.length();
        }

        // Shift positions of subsequent fields on the same row
        for (int i = 0; i < fields.size(); i++) {
            if (i == index) continue;
            for (int[] pos : fields.get(i).positions) {
                for (int[] updatedPos : f.positions) {
                    if (pos[0] == updatedPos[0] && pos[1] > updatedPos[1]) {
                        pos[1] += lenDelta;
                        pos[2] += lenDelta;
                    }
                }
            }
        }

        // Place cursor at end of the first position of the active field
        if (!f.positions.isEmpty()) {
            int[] first = f.positions.getFirst();
            textArea.setCursorPosition(first[0], first[1] + newValue.length());
        }
    }

    private void selectField(int index) {
        activeIndex = index;
        Field f = fields.get(index);
        if (!f.positions.isEmpty()) {
            int[] first = f.positions.getFirst();
            textArea.setCursorPosition(first[0], first[1]);
        }
    }

    // ---- Helpers ----

    /** Convert a character offset range to [row, startCol, endCol]. */
    private static int[] offsetToRowCol(String text, int startOffset, int endOffset) {
        int row = 0, col = 0;
        int startRow = 0, startCol = 0;
        for (int i = 0; i < endOffset && i < text.length(); i++) {
            if (i == startOffset) {
                startRow = row;
                startCol = col;
            }
            if (text.charAt(i) == '\n') {
                row++;
                col = 0;
            } else {
                col++;
            }
        }
        if (startOffset >= text.length()) {
            startRow = row;
            startCol = col;
        }
        return new int[]{ startRow, startCol, startCol + (endOffset - startOffset) };
    }
}
