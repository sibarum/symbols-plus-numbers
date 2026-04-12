package spn.gui.diagnostic;

import spn.fonts.SdfFontRenderer;
import spn.lang.DeclarationScanner;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders a faint highlight on regions of code that have been modified
 * since the file was last saved/loaded.
 *
 * <p>Uses declaration-level comparison: scans the saved content and current
 * content into spans, then highlights lines belonging to changed spans.
 * This survives line insertions/deletions gracefully because comparison
 * is content-based, not position-based.
 */
public class ChangeOverlay {

    // Faint blue-green tint for changed regions
    private static final float CHANGE_R = 0.15f, CHANGE_G = 0.25f, CHANGE_B = 0.20f;

    private List<DeclarationScanner.Span> savedSpans = List.of();
    private Set<Integer> changedLines = Set.of();

    /**
     * Snapshot the saved content. Called when file is loaded or saved.
     * This becomes the baseline for change detection.
     */
    public void snapshotSaved(String savedContent) {
        if (savedContent == null || savedContent.isBlank()) {
            savedSpans = List.of();
        } else {
            savedSpans = DeclarationScanner.scan(savedContent);
        }
        changedLines = Set.of(); // just saved — nothing changed
    }

    /**
     * Update changed lines by comparing current content against saved snapshot.
     * Call this periodically (e.g., after diagnostic debounce).
     */
    public void update(String currentContent) {
        if (savedSpans.isEmpty() && (currentContent == null || currentContent.isBlank())) {
            changedLines = Set.of();
            return;
        }

        List<DeclarationScanner.Span> currentSpans = DeclarationScanner.scan(
                currentContent != null ? currentContent : "");

        Set<Integer> changed = new HashSet<>();

        // For each current span, check if it has a matching saved span (by content)
        // Build a set of saved span contents for fast lookup
        Set<String> savedContents = new HashSet<>();
        for (DeclarationScanner.Span s : savedSpans) {
            savedContents.add(s.kind() + ":" + s.source());
        }

        for (DeclarationScanner.Span cs : currentSpans) {
            String key = cs.kind() + ":" + cs.source();
            if (!savedContents.contains(key)) {
                // This span has no match in saved — it's changed or new
                for (int line = cs.startLine(); line < cs.endLine(); line++) {
                    changed.add(line);
                }
            }
        }

        changedLines = changed;
    }

    /** Check if there are any changed lines to render. */
    public boolean isEmpty() { return changedLines.isEmpty(); }

    /** Number of changed lines since last save. */
    public int changedLineCount() { return changedLines.size(); }

    /**
     * Render faint change highlights for visible rows.
     * Draws a subtle gutter indicator on the left edge of changed lines.
     */
    public void render(SdfFontRenderer font, float gutterX, float textY,
                       float cellHeight, float highlightY,
                       int scrollRow, int visibleRows, float gutterIndicatorWidth) {

        for (int i = 0; i < visibleRows; i++) {
            int row = scrollRow + i;
            if (!changedLines.contains(row)) continue;

            float ry = textY + i * cellHeight + highlightY;
            // Subtle gutter bar on the left edge
            font.drawRect(gutterX, ry, gutterIndicatorWidth, cellHeight,
                    CHANGE_R, CHANGE_G, CHANGE_B);
        }
    }
}
