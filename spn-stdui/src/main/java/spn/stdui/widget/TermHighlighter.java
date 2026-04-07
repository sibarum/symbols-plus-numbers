package spn.stdui.widget;

import spn.stdui.buffer.TextBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds all occurrences of a term (whole-word) within a set of buffer lines.
 * Useful for cursor-word highlighting, search results, rename previews, etc.
 */
public class TermHighlighter {

    public record Match(int row, int startCol, int endCol) {}

    public static List<Match> findMatches(TextBuffer buffer, String term, int fromRow, int toRow) {
        if (term == null || term.isEmpty()) return List.of();
        List<Match> matches = new ArrayList<>();
        int len = term.length();
        for (int row = fromRow; row < toRow && row < buffer.lineCount(); row++) {
            String line = buffer.getLine(row);
            int idx = 0;
            while ((idx = line.indexOf(term, idx)) >= 0) {
                boolean wordStart = idx == 0 || !isWordChar(line.charAt(idx - 1));
                boolean wordEnd = (idx + len) >= line.length() || !isWordChar(line.charAt(idx + len));
                if (wordStart && wordEnd) {
                    matches.add(new Match(row, idx, idx + len));
                }
                idx += len;
            }
        }
        return matches;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
    }
}
