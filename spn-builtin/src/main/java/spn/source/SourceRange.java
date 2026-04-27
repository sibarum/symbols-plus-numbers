package spn.source;

/**
 * A span of source code, in parser convention:
 * <ul>
 *   <li>{@code startLine}/{@code endLine} — 1-based (first line is 1)
 *   <li>{@code startCol}/{@code endCol}   — 0-based (first column is 0),
 *       end is exclusive ({@code [start, end)})
 * </ul>
 *
 * <p>The parser, tokens, and AST all use this convention because it matches
 * {@link spn.lang.SpnParseToken SpnParseToken} and what humans see in error
 * messages ("line 42, col 7"). Consumers that speak a different convention —
 * most notably the editor, which is 0-based on both axes — must convert at
 * the API boundary via {@link #toEditorCoords()}.
 *
 * <p>This is the canonical position type for {@link spn.lang.TypeGraph.Node},
 * {@link spn.lang.IncrementalParser.DispatchAnnotation}, and any other
 * declaration-level source span. Scattered {@code line()}/{@code col()} int
 * pairs with unlabelled conventions have caused recurring off-by-one bugs;
 * centralising them here is the fix.
 */
public record SourceRange(int startLine, int startCol, int endLine, int endCol) {

    /** Sentinel for "no known position". {@link #isKnown()} returns false. */
    public static final SourceRange UNKNOWN = new SourceRange(-1, -1, -1, -1);

    public boolean isKnown() { return startLine >= 0; }

    /** Range covering a single token on one line. */
    public static SourceRange ofToken(int line, int col, int endCol) {
        return new SourceRange(line, col, line, endCol);
    }

    /** Convert to the editor's 0-based-line / 0-based-col convention. Use
     *  only at the parser → UI boundary. */
    public SourceRange toEditorCoords() {
        if (!isKnown()) return UNKNOWN;
        return new SourceRange(startLine - 1, startCol, endLine - 1, endCol);
    }
}
