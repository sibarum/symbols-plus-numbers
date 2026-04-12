package spn.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Scans SPN source text and splits it into top-level declaration spans.
 * Each span represents one import, type, function, let binding, or expression.
 *
 * <p>This operates on raw text — no parsing. It identifies boundaries by looking
 * for top-level keywords at the start of non-indented lines. This is a heuristic
 * that works for well-formatted SPN code.
 *
 * <p>Used by the incremental parser to determine which declarations need re-parsing
 * after an edit.
 */
public final class DeclarationScanner {

    /** A contiguous span of source lines forming one top-level declaration. */
    public record Span(
            int startLine,    // 0-based inclusive
            int endLine,      // 0-based exclusive
            String kind,      // keyword that starts this span ("import", "pure", "type", etc.)
            String source     // the source text of this span
    ) {
        public int lineCount() { return endLine - startLine; }

        /** Check if this span overlaps with a range of edited lines. */
        public boolean overlaps(int editStartLine, int editEndLine) {
            return startLine < editEndLine && endLine > editStartLine;
        }

        /** Content-based identity for cache matching. */
        public boolean contentEquals(Span other) {
            return kind.equals(other.kind) && source.equals(other.source);
        }
    }

    private static final Set<String> TOP_LEVEL_KEYWORDS = Set.of(
            "import", "module", "version", "require",
            "type", "data", "struct",
            "pure", "action", "const",
            "promote", "let"
    );

    /**
     * Scan source text into declaration spans.
     * Lines are split by newline; each span starts at a line beginning with a
     * top-level keyword (not indented) and extends to the line before the next
     * top-level keyword (or end of file).
     *
     * Blank lines and comments between declarations are attached to the following span.
     */
    public static List<Span> scan(String source) {
        String[] lines = source.split("\n", -1);
        List<Span> spans = new ArrayList<>();

        int spanStart = 0;
        String spanKind = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.stripLeading();

            // Check if this line starts a new top-level declaration
            if (!trimmed.isEmpty() && line.charAt(0) != ' ' && line.charAt(0) != '\t') {
                String keyword = detectKeyword(trimmed);
                if (keyword != null) {
                    // Close the previous span if there was one
                    if (spanKind != null) {
                        spans.add(buildSpan(spanStart, i, spanKind, lines));
                    } else if (i > 0 && spanStart < i) {
                        // Leading expressions/statements before the first keyword
                        spans.add(buildSpan(spanStart, i, "<expr>", lines));
                    }
                    spanStart = i;
                    spanKind = keyword;
                    continue;
                }
            }

            // Non-keyword, non-indented line at top level (bare expression)
            if (!trimmed.isEmpty() && spanKind == null
                    && line.charAt(0) != ' ' && line.charAt(0) != '\t'
                    && !trimmed.startsWith("--")) {
                // Start of a bare expression span
                if (spanStart < i) {
                    spans.add(buildSpan(spanStart, i, "<expr>", lines));
                }
                spanStart = i;
                spanKind = "<expr>";
            }
        }

        // Close final span
        if (spanKind != null) {
            spans.add(buildSpan(spanStart, lines.length, spanKind, lines));
        } else if (spanStart < lines.length) {
            // Trailing content
            String trailing = joinLines(lines, spanStart, lines.length);
            if (!trailing.isBlank()) {
                spans.add(buildSpan(spanStart, lines.length, "<expr>", lines));
            }
        }

        return spans;
    }

    private static Span buildSpan(int startLine, int endLine, String kind, String[] lines) {
        String source = joinLines(lines, startLine, endLine);
        return new Span(startLine, endLine, kind, source);
    }

    private static String joinLines(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private static String detectKeyword(String trimmedLine) {
        // Extract first word
        int end = 0;
        while (end < trimmedLine.length() && Character.isLetter(trimmedLine.charAt(end))) end++;
        if (end == 0) return null;
        String word = trimmedLine.substring(0, end);
        return TOP_LEVEL_KEYWORDS.contains(word) ? word : null;
    }
}
