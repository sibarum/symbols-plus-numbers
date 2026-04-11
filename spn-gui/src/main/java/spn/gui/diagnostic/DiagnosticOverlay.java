package spn.gui.diagnostic;

import spn.fonts.SdfFontRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders diagnostic marks (error underlines, stale hazes) over the text area.
 * Called by TextArea during its render pass with layout metrics.
 */
public class DiagnosticOverlay {

    // ACTIVE: solid red underline + subtle background tint
    private static final float ACTIVE_BG_R = 0.5f, ACTIVE_BG_G = 0.1f, ACTIVE_BG_B = 0.1f, ACTIVE_BG_A = 0.15f;
    private static final float UNDERLINE_R = 0.9f, UNDERLINE_G = 0.25f, UNDERLINE_B = 0.25f;
    private static final float UNDERLINE_H = 2f;

    // STALE: faint red haze only
    private static final float STALE_BG_R = 0.4f, STALE_BG_G = 0.15f, STALE_BG_B = 0.1f, STALE_BG_A = 0.08f;

    private final List<DiagnosticMark> marks = new ArrayList<>();

    /** Replace all marks with a new set (called after re-parse reconciliation). */
    public void setMarks(List<DiagnosticMark> newMarks) {
        marks.clear();
        marks.addAll(newMarks);
    }

    /** Get current marks (for HUD message lookup). */
    public List<DiagnosticMark> getMarks() {
        return marks;
    }

    /** Mark all diagnostics on the given row as STALE (user is editing that line). */
    public void markLineStale(int row) {
        for (DiagnosticMark m : marks) {
            if (m.row() == row && m.isActive()) {
                m.markStale();
            }
        }
    }

    /** Find the first active diagnostic on the given row, or null. */
    public DiagnosticMark findOnRow(int row) {
        for (DiagnosticMark m : marks) {
            if (m.row() == row) return m;
        }
        return null;
    }

    public boolean isEmpty() { return marks.isEmpty(); }

    /**
     * Render diagnostic highlights for visible rows.
     *
     * @param font       the renderer
     * @param textX      x offset where text content starts (after gutter)
     * @param textY      y offset where first visible row starts
     * @param cellWidth  width of one character cell
     * @param cellHeight height of one line
     * @param highlightY y offset for highlight rectangles (HIGHLIGHT_OFFSET_Y)
     * @param scrollRow  first visible row index
     * @param scrollCol  first visible column index
     * @param visibleRows number of visible rows
     * @param visibleCols number of visible columns
     * @param lineLength  function to get line length by row (buffer::lineLength)
     */
    public void render(SdfFontRenderer font, float textX, float textY,
                       float cellWidth, float cellHeight, float highlightY,
                       int scrollRow, int scrollCol,
                       int visibleRows, int visibleCols,
                       java.util.function.IntUnaryOperator lineLength) {

        for (DiagnosticMark mark : marks) {
            int row = mark.row();
            int viewRow = row - scrollRow;
            if (viewRow < 0 || viewRow >= visibleRows) continue;

            int startCol = mark.diagnostic().startCol();
            int endCol = mark.diagnostic().endCol();
            if (endCol < 0) endCol = lineLength.applyAsInt(row); // -1 = end of line
            if (endCol <= startCol) endCol = startCol + 1; // at least one char wide

            int drawStart = Math.max(startCol - scrollCol, 0);
            int drawEnd = Math.min(endCol - scrollCol, visibleCols);
            if (drawEnd <= 0 || drawStart >= visibleCols) continue;

            float rx = textX + drawStart * cellWidth;
            float ry = textY + viewRow * cellHeight + highlightY;
            float rw = (drawEnd - drawStart) * cellWidth;

            if (mark.isActive()) {
                // Background tint
                font.drawRect(rx, ry, rw, cellHeight,
                        ACTIVE_BG_R, ACTIVE_BG_G, ACTIVE_BG_B);
                // Underline at bottom of cell
                font.drawRect(rx, ry + cellHeight - UNDERLINE_H, rw, UNDERLINE_H,
                        UNDERLINE_R, UNDERLINE_G, UNDERLINE_B);
            } else {
                // Stale: faint haze only
                font.drawRect(rx, ry, rw, cellHeight,
                        STALE_BG_R, STALE_BG_G, STALE_BG_B);
            }
        }
    }
}
