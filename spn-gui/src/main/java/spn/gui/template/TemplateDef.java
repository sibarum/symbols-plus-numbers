package spn.gui.template;

import java.util.List;
import java.util.function.BiFunction;

/**
 * A template definition: a grid-aligned form for structured code entry.
 *
 * <p>A template consists of fixed text cells (keywords, punctuation) and
 * editable fields. The emitter function converts field values into source text.
 *
 * @param keyword  the triggering keyword (e.g. "struct", "pure")
 * @param cells    fixed text at specific grid positions
 * @param fields   editable fields at specific grid positions
 * @param emitter  (fieldValues, indentLevel) → output source text
 */
public record TemplateDef(
        String keyword,
        List<TemplateCell> cells,
        List<TemplateField> fields,
        BiFunction<String[], Integer, String> emitter
) {

    /** A fixed, non-editable text element in the template grid. */
    public record TemplateCell(int row, int col, String text) {}

    /**
     * An editable field in the template grid.
     *
     * @param row          row offset from template anchor
     * @param col          column offset from template anchor
     * @param minWidth     minimum display width in characters
     * @param name         field label (shown as placeholder when empty)
     * @param defaultValue initial value
     */
    public record TemplateField(int row, int col, int minWidth,
                                String name, String defaultValue) {}

    /** Total number of rows this template spans. */
    public int rowCount() {
        int max = 0;
        for (TemplateCell c : cells) max = Math.max(max, c.row());
        for (TemplateField f : fields) max = Math.max(max, f.row());
        return max + 1;
    }
}
