package spn.lang;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces a flat token stream from SPN source code by wrapping the
 * line-local SpnLexer. Skips whitespace and comments, attaches full
 * source positions (line + column) and token text.
 */
public class SpnTokenizer {

    private final SpnLexer lexer = new SpnLexer();
    private final List<SpnParseToken> tokens;
    private int pos;

    public SpnTokenizer(String source) {
        this.tokens = tokenize(source);
        this.pos = 0;
    }

    private List<SpnParseToken> tokenize(String source) {
        List<SpnParseToken> result = new ArrayList<>();
        String[] lines = source.split("\n", -1);

        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String line = lines[lineIdx];
            List<Token> lineTokens = lexer.tokenizeLine(line);
            int lineNum = lineIdx + 1;

            for (Token tok : lineTokens) {
                if (tok.type() == TokenType.WHITESPACE || tok.type() == TokenType.COMMENT) {
                    continue;
                }
                String text = line.substring(tok.startCol(), tok.endCol());
                result.add(new SpnParseToken(lineNum, tok.startCol(), tok.endCol(), text, tok.type()));
            }
        }

        return result;
    }

    /** Look at the current token without consuming it. Returns null at EOF. */
    public SpnParseToken peek() {
        return pos < tokens.size() ? tokens.get(pos) : null;
    }

    /** Look ahead by offset tokens. Returns null if out of bounds. */
    public SpnParseToken peek(int offset) {
        int idx = pos + offset;
        return idx < tokens.size() ? tokens.get(idx) : null;
    }

    /** Consume and return the current token. */
    public SpnParseToken advance() {
        return tokens.get(pos++);
    }

    /** Consume the current token if it matches the given text. Returns true if consumed. */
    public boolean match(String text) {
        SpnParseToken tok = peek();
        if (tok != null && tok.text().equals(text)) {
            pos++;
            return true;
        }
        return false;
    }

    /** Consume and return the current token, or throw if it doesn't match. */
    public SpnParseToken expect(String text) {
        SpnParseToken tok = peek();
        if (tok == null) {
            throw error("Expected '" + text + "' but reached end of input");
        }
        if (!tok.text().equals(text)) {
            throw error("Expected '" + text + "' but got '" + tok.text() + "'", tok);
        }
        return advance();
    }

    /** Consume and return the current token if its type matches, or throw. */
    public SpnParseToken expectType(TokenType type) {
        SpnParseToken tok = peek();
        if (tok == null) {
            throw error("Expected " + type + " but reached end of input");
        }
        if (tok.type() != type) {
            throw error("Expected " + type + " but got " + tok.type() + "('" + tok.text() + "')", tok);
        }
        return advance();
    }

    /** True if there are more tokens. */
    public boolean hasMore() {
        return pos < tokens.size();
    }

    /** True if current token has the given text. */
    public boolean check(String text) {
        SpnParseToken tok = peek();
        return tok != null && tok.text().equals(text);
    }

    /** True if current token has the given type. */
    public boolean checkType(TokenType type) {
        SpnParseToken tok = peek();
        return tok != null && tok.type() == type;
    }

    /** Save position for backtracking. */
    public int mark() {
        return pos;
    }

    /** Restore position for backtracking. */
    public void reset(int mark) {
        this.pos = mark;
    }

    public SpnParseException error(String message) {
        SpnParseToken tok = peek();
        if (tok != null) {
            return new SpnParseException(message + " at " + tok.location());
        }
        return new SpnParseException(message + " at end of input");
    }

    public SpnParseException error(String message, SpnParseToken tok) {
        return new SpnParseException(message + " at " + tok.location());
    }
}
