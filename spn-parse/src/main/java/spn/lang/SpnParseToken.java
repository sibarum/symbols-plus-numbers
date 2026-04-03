package spn.lang;

/**
 * A token with full source position, text, and type.
 * Produced by SpnTokenizer from the line-local SpnLexer output.
 *
 * @param line   1-based line number
 * @param col    0-based column (start)
 * @param endCol 0-based column (exclusive end)
 * @param text   the source text of this token
 * @param type   the token type from the lexer
 */
public record SpnParseToken(int line, int col, int endCol, String text, TokenType type) {

    public String location() {
        return "line " + line + ", col " + col;
    }

    @Override
    public String toString() {
        return type + "(" + text + ") at " + location();
    }
}
