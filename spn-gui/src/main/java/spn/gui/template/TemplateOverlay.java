package spn.gui.template;

import spn.fonts.SdfFontRenderer;
import spn.gui.TextArea;
import spn.gui.TextBuffer;
import spn.gui.UndoManager;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Runtime state of an active template overlay. Renders a grid-aligned form
 * over the editor, handles field editing and Tab cycling, and commits or
 * cancels the template back into the text buffer.
 *
 * <p>While active, the template edits its own field-value array — the
 * underlying text buffer is not touched until commit. This keeps undo clean:
 * a single entry records the entire template operation.
 */
public class TemplateOverlay {

    // Colors
    private static final float FIELD_BG_R = 0.18f, FIELD_BG_G = 0.18f, FIELD_BG_B = 0.22f;
    private static final float ACTIVE_BG_R = 0.22f, ACTIVE_BG_G = 0.25f, ACTIVE_BG_B = 0.35f;
    private static final float ACTIVE_BORDER_R = 0.40f, ACTIVE_BORDER_G = 0.50f, ACTIVE_BORDER_B = 0.80f;
    private static final float CELL_R = 0.55f, CELL_G = 0.45f, CELL_B = 0.65f;
    private static final float FIELD_TEXT_R = 0.90f, FIELD_TEXT_G = 0.90f, FIELD_TEXT_B = 0.90f;
    private static final float PLACEHOLDER_R = 0.45f, PLACEHOLDER_G = 0.45f, PLACEHOLDER_B = 0.50f;
    private static final float CURSOR_R = 0.90f, CURSOR_G = 0.90f, CURSOR_B = 0.30f;
    private static final float FIELD_BORDER_R = 0.12f, FIELD_BORDER_G = 0.12f, FIELD_BORDER_B = 0.15f;
    private static final float DIM_BG_R = 0.10f, DIM_BG_G = 0.10f, DIM_BG_B = 0.12f;
    private static final float BORDER = 2f;

    private final TemplateDef def;
    private final int anchorRow;
    private final int anchorCol;
    private final String[] fieldValues;
    private int activeField;
    private int cursorInField;

    // Saved state for cancel
    private final String originalText;
    private final int originalCursorRow;
    private final int originalCursorCol;

    /**
     * Create a new template overlay.
     *
     * @param def               the template definition
     * @param anchorRow         buffer row where the template starts
     * @param anchorCol         buffer column where the template starts
     * @param originalText      text that was removed to make way for the template
     * @param originalCursorRow cursor row before template activation
     * @param originalCursorCol cursor col before template activation
     */
    public TemplateOverlay(TemplateDef def, int anchorRow, int anchorCol,
                           String originalText, int originalCursorRow, int originalCursorCol) {
        this.def = def;
        this.anchorRow = anchorRow;
        this.anchorCol = anchorCol;
        this.originalText = originalText;
        this.originalCursorRow = originalCursorRow;
        this.originalCursorCol = originalCursorCol;

        this.fieldValues = new String[def.fields().size()];
        for (int i = 0; i < fieldValues.length; i++) {
            fieldValues[i] = def.fields().get(i).defaultValue();
        }
        this.activeField = 0;
        this.cursorInField = fieldValues.length > 0 ? fieldValues[0].length() : 0;
    }

    /** Pre-populate field values (for template reconstruction). */
    public void setFieldValues(String[] values) {
        for (int i = 0; i < Math.min(values.length, fieldValues.length); i++) {
            fieldValues[i] = values[i];
        }
        cursorInField = fieldValues.length > 0 ? fieldValues[activeField].length() : 0;
    }

    // ---- Input handling -------------------------------------------------

    /**
     * Handle a key event. Returns an action indicating what the caller should do.
     */
    public Result onKey(int key, int mods) {
        boolean shift = (mods & GLFW_MOD_SHIFT) != 0;

        switch (key) {
            case GLFW_KEY_TAB -> {
                if (shift) {
                    if (activeField > 0) {
                        activeField--;
                        cursorInField = fieldValues[activeField].length();
                    }
                } else {
                    if (activeField < fieldValues.length - 1) {
                        activeField++;
                        cursorInField = fieldValues[activeField].length();
                    } else {
                        // Tab past last field → commit
                        return Result.COMMIT;
                    }
                }
                return Result.CONSUMED;
            }
            case GLFW_KEY_ENTER -> {
                return Result.COMMIT;
            }
            case GLFW_KEY_ESCAPE -> {
                return Result.CANCEL;
            }
            case GLFW_KEY_BACKSPACE -> {
                if (cursorInField > 0) {
                    String val = fieldValues[activeField];
                    fieldValues[activeField] = val.substring(0, cursorInField - 1)
                            + val.substring(cursorInField);
                    cursorInField--;
                }
                return Result.CONSUMED;
            }
            case GLFW_KEY_DELETE -> {
                String val = fieldValues[activeField];
                if (cursorInField < val.length()) {
                    fieldValues[activeField] = val.substring(0, cursorInField)
                            + val.substring(cursorInField + 1);
                }
                return Result.CONSUMED;
            }
            case GLFW_KEY_LEFT -> {
                if (cursorInField > 0) {
                    cursorInField--;
                    return Result.CONSUMED;
                }
                // At left edge of field → leave template (arrow moves cursor)
                return Result.COMMIT;
            }
            case GLFW_KEY_RIGHT -> {
                if (cursorInField < fieldValues[activeField].length()) {
                    cursorInField++;
                    return Result.CONSUMED;
                }
                // At right edge → leave template
                return Result.COMMIT;
            }
            case GLFW_KEY_UP, GLFW_KEY_DOWN -> {
                // Vertical movement always leaves the template
                return Result.COMMIT;
            }
            case GLFW_KEY_HOME -> {
                cursorInField = 0;
                return Result.CONSUMED;
            }
            case GLFW_KEY_END -> {
                cursorInField = fieldValues[activeField].length();
                return Result.CONSUMED;
            }
        }
        return Result.NOT_HANDLED;
    }

    /**
     * Handle character input — insert into the active field.
     */
    public void onChar(int codepoint) {
        String ch = new String(Character.toChars(codepoint));
        String val = fieldValues[activeField];
        fieldValues[activeField] = val.substring(0, cursorInField) + ch
                + val.substring(cursorInField);
        cursorInField += ch.length();
    }

    // ---- Commit / Cancel ------------------------------------------------

    /**
     * Commit the template: emit source text, insert into buffer, record undo.
     * Returns the cursor position after the inserted text as [row, col].
     */
    public int[] commit(TextBuffer buffer, UndoManager undo) {
        // Determine indent from anchor column
        String emitted = def.emitter().apply(fieldValues, anchorCol);
        int[] end = buffer.insertText(anchorRow, anchorCol, emitted);

        // Record a single undo entry: removed = originalText, inserted = emitted
        undo.record(anchorRow, anchorCol, originalText, emitted,
                originalCursorRow, originalCursorCol, end[0], end[1]);

        return end;
    }

    /**
     * Cancel the template: restore the original text.
     * Returns the cursor position as [row, col].
     */
    public int[] cancel(TextBuffer buffer) {
        if (!originalText.isEmpty()) {
            buffer.insertText(anchorRow, anchorCol, originalText);
        }
        return new int[]{originalCursorRow, originalCursorCol};
    }

    // ---- Rendering ------------------------------------------------------

    /**
     * Render the template overlay. Call between font.beginText / font.endText.
     */
    public void render(SdfFontRenderer font, TextArea textArea) {
        float cellW = textArea.getCellWidth();
        float cellH = textArea.getCellHeight();
        float scale = textArea.getFontScale();
        float originX = textArea.getTextOriginX();
        float originY = textArea.getTextOriginY();
        float hlOffset = textArea.getHighlightOffsetY();
        int scrollRow = textArea.getScrollRowVal();
        int scrollCol = textArea.getScrollColVal();

        // Template grid origin in pixels
        int visRow = anchorRow - scrollRow;
        int visCol = anchorCol - scrollCol;

        // Don't render if completely off-screen
        int templateRows = def.rowCount();
        if (visRow + templateRows < 0 || visRow > textArea.getVisibleRows()) return;

        // Dim background behind the template area (nudged down)
        float totalCols = 80; // approximate max width
        float bgX = originX + visCol * cellW - 4f;
        float bgY = originY + visRow * cellH - 2f + hlOffset;
        float bgW = totalCols * cellW + 8f;
        float bgH = templateRows * cellH + 4f;
        font.drawRect(bgX, bgY, bgW, bgH, DIM_BG_R, DIM_BG_G, DIM_BG_B);

        // Render fixed cells
        for (TemplateDef.TemplateCell cell : def.cells()) {
            int cRow = visRow + cell.row();
            int cCol = visCol + cell.col();
            if (cRow < 0) continue;
            float x = originX + cCol * cellW;
            float y = originY + (cRow + 1) * cellH;
            font.drawText(cell.text(), x, y, scale, CELL_R, CELL_G, CELL_B);
        }

        // Render editable fields
        for (int i = 0; i < def.fields().size(); i++) {
            TemplateDef.TemplateField field = def.fields().get(i);
            int fRow = visRow + field.row();
            int fCol = visCol + field.col();
            if (fRow < 0) continue;

            String value = fieldValues[i];
            int displayWidth = Math.max(field.minWidth(), value.length() + 1);
            boolean isActive = (i == activeField);

            float fx = originX + fCol * cellW;
            float fy = originY + fRow * cellH + hlOffset;
            float fw = displayWidth * cellW;

            // Border (drawn first, slightly larger than the field)
            if (isActive) {
                font.drawRect(fx - BORDER, fy - BORDER, fw + BORDER * 2, cellH + BORDER * 2,
                        ACTIVE_BORDER_R, ACTIVE_BORDER_G, ACTIVE_BORDER_B);
            } else {
                font.drawRect(fx - BORDER, fy - BORDER, fw + BORDER * 2, cellH + BORDER * 2,
                        FIELD_BORDER_R, FIELD_BORDER_G, FIELD_BORDER_B);
            }

            // Field background (inside the border)
            if (isActive) {
                font.drawRect(fx, fy, fw, cellH,
                        ACTIVE_BG_R, ACTIVE_BG_G, ACTIVE_BG_B);
            } else {
                font.drawRect(fx, fy, fw, cellH,
                        FIELD_BG_R, FIELD_BG_G, FIELD_BG_B);
            }

            // Field text or placeholder
            float textY = originY + (fRow + 1) * cellH;
            if (value.isEmpty() && !isActive) {
                font.drawText(field.name(), fx, textY, scale,
                        PLACEHOLDER_R, PLACEHOLDER_G, PLACEHOLDER_B);
            } else {
                font.drawText(value, fx, textY, scale,
                        FIELD_TEXT_R, FIELD_TEXT_G, FIELD_TEXT_B);
            }

            // Cursor in active field
            if (isActive) {
                float cursorX = fx + cursorInField * cellW;
                font.drawRect(cursorX, fy + 2f, 2f, cellH - 4f,
                        CURSOR_R, CURSOR_G, CURSOR_B);
            }
        }
    }

    // ---- Accessors ------------------------------------------------------

    public int getAnchorRow() { return anchorRow; }
    public int getAnchorCol() { return anchorCol; }
    public TemplateDef getDef() { return def; }
    public String[] getFieldValues() { return fieldValues; }
    public int getActiveField() { return activeField; }

    /** Result of an input action on the template. */
    public enum Result {
        CONSUMED,     // template handled the key
        COMMIT,       // template should be committed
        CANCEL,       // template should be cancelled
        NOT_HANDLED   // key was not relevant to the template
    }
}
