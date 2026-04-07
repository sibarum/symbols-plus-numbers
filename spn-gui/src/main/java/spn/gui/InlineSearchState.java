package spn.gui;

import java.util.List;

/**
 * State for inline function search within the editor. When multiple functions
 * match a typed prefix, this state enables Tab/Shift+Tab cycling and continued
 * typing to narrow the results. Displayed in the HUD, not as a popup.
 */
class InlineSearchState {

    private final FunctionIndex index;
    private StringBuilder prefix;
    private List<FunctionIndex.Entry> matches;
    private int selectedIndex;

    // The original text and cursor position before search started
    final int originalRow;
    final int originalCol;
    final int wordStartCol;

    InlineSearchState(FunctionIndex index, String initialPrefix,
                      int originalRow, int originalCol, int wordStartCol) {
        this.index = index;
        this.prefix = new StringBuilder(initialPrefix);
        this.originalRow = originalRow;
        this.originalCol = originalCol;
        this.wordStartCol = wordStartCol;
        refilter();
    }

    /** The currently selected entry, or null if no matches. */
    FunctionIndex.Entry selected() {
        if (matches.isEmpty()) return null;
        return matches.get(selectedIndex);
    }

    /** All current matches. */
    List<FunctionIndex.Entry> matches() { return matches; }

    /** Current selected index. */
    int selectedIndex() { return selectedIndex; }

    /** Current prefix text. */
    String prefix() { return prefix.toString(); }

    /** Append a character to the prefix and re-filter. */
    void appendChar(int codepoint) {
        prefix.append(Character.toChars(codepoint));
        refilter();
    }

    /** Delete the last character from the prefix and re-filter. */
    void backspace() {
        if (prefix.length() > 0) {
            prefix.deleteCharAt(prefix.length() - 1);
            refilter();
        }
    }

    /** Move selection forward (Tab). Wraps around. */
    void next() {
        if (!matches.isEmpty()) {
            selectedIndex = (selectedIndex + 1) % matches.size();
        }
    }

    /** Move selection backward (Shift+Tab). Wraps around. */
    void prev() {
        if (!matches.isEmpty()) {
            selectedIndex = (selectedIndex - 1 + matches.size()) % matches.size();
        }
    }

    /** True if there are no matches for the current prefix. */
    boolean isEmpty() { return matches.isEmpty(); }

    /** True if exactly one match remains. */
    boolean isSingleMatch() { return matches.size() == 1; }

    /** Build HUD text showing matches with the selected one highlighted. */
    String hudText() {
        if (matches.isEmpty()) return "No matches for \"" + prefix + "\" | Esc Cancel";

        StringBuilder sb = new StringBuilder();
        int show = Math.min(matches.size(), 7); // show up to 7 in HUD
        int start = Math.max(0, selectedIndex - 3);
        if (start + show > matches.size()) start = Math.max(0, matches.size() - show);

        for (int i = start; i < start + show; i++) {
            if (sb.length() > 0) sb.append(" | ");
            FunctionIndex.Entry e = matches.get(i);
            if (i == selectedIndex) {
                sb.append("[").append(e.name()).append("]");
            } else {
                sb.append(e.name());
            }
        }
        if (matches.size() > show) {
            sb.append(" | (").append(matches.size()).append(" total)");
        }
        sb.append(" | Tab Cycle | Enter Select | Esc Cancel");
        return sb.toString();
    }

    private void refilter() {
        matches = index.matchPrefix(prefix.toString());
        selectedIndex = 0;
    }
}
