package cott.pem.traction.spn;

import cott.pem.traction.TextBuffer;
import spn.lang.Token;
import spn.lang.TokenType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Determines the {@link EditorContext} at a given cursor row by scanning
 * tokens from the start of the file, tracking brace nesting and keywords.
 */
public class ContextDetector {

    /**
     * Detect the semantic context at {@code cursorRow}.
     *
     * <p>Algorithm: walk rows 0 through cursorRow, counting brace depth via
     * DELIMITER tokens. On each {@code {}, push a context derived from the
     * nearest preceding keyword. On each {@code }}, pop. At the end, depth 0
     * checks for braceless constructs (type body, match body).</p>
     */
    public EditorContext detect(TextBuffer buffer, HighlightCache cache, int cursorRow) {
        Deque<EditorContext> stack = new ArrayDeque<>();

        for (int row = 0; row <= cursorRow && row < buffer.lineCount(); row++) {
            String line = buffer.getLine(row);
            List<Token> tokens = cache.getTokens(row, line);

            for (Token tok : tokens) {
                if (tok.type() != TokenType.DELIMITER) continue;
                char ch = line.charAt(tok.startCol());
                if (ch == '{') {
                    stack.push(classifyBrace(line, tokens, tok));
                } else if (ch == '}') {
                    if (!stack.isEmpty()) stack.pop();
                }
            }
        }

        if (!stack.isEmpty()) return stack.peek();

        // Depth 0 — check for braceless constructs
        EditorContext braceless = detectBraceless(buffer, cache, cursorRow);
        return braceless != null ? braceless : EditorContext.TOP_LEVEL;
    }

    /**
     * When we see a {@code {}, scan backwards through the tokens on the same
     * line for the nearest KEYWORD to classify the block context.
     */
    private EditorContext classifyBrace(String line, List<Token> tokens, Token brace) {
        // Walk tokens in reverse order up to (but not including) the brace
        for (int i = tokens.indexOf(brace) - 1; i >= 0; i--) {
            Token t = tokens.get(i);
            if (t.type() == TokenType.WHITESPACE) continue;
            if (t.type() == TokenType.KEYWORD) {
                String kw = line.substring(t.startCol(), t.endCol());
                return switch (kw) {
                    case "pure", "do" -> EditorContext.FUNCTION_BODY;
                    case "while"      -> EditorContext.WHILE_BODY;
                    default           -> EditorContext.BLOCK;
                };
            }
            // If the token right before { is = or -> or ), it's likely a lambda/function body
            if (t.type() == TokenType.OPERATOR) {
                String op = line.substring(t.startCol(), t.endCol());
                if ("=".equals(op) || "->".equals(op)) return EditorContext.FUNCTION_BODY;
            }
            if (t.type() == TokenType.DELIMITER) {
                char ch = line.charAt(t.startCol());
                if (ch == ')') return EditorContext.FUNCTION_BODY;
            }
            // Stop at first non-whitespace that isn't a keyword
            return EditorContext.BLOCK;
        }
        return EditorContext.BLOCK;
    }

    /**
     * Detect braceless constructs: type bodies (indentation-based) and
     * match bodies (pipe-delimited branches).
     */
    private EditorContext detectBraceless(TextBuffer buffer, HighlightCache cache, int cursorRow) {
        // Scan backwards from cursorRow for type or match keywords at column 0
        for (int row = cursorRow - 1; row >= 0; row--) {
            String line = buffer.getLine(row);
            if (line.isEmpty()) continue;

            // Non-indented, non-blank line that isn't a pipe — we've hit a new top-level form
            // Check if it starts a type or match
            List<Token> tokens = cache.getTokens(row, line);
            Token firstNonWs = firstNonWhitespace(tokens);
            if (firstNonWs == null) continue;

            if (firstNonWs.type() == TokenType.KEYWORD) {
                String kw = line.substring(firstNonWs.startCol(), firstNonWs.endCol());
                if ("type".equals(kw)) {
                    // Check that all lines between this type decl and cursor are indented or blank
                    if (allIndentedOrBlank(buffer, row + 1, cursorRow)) {
                        return EditorContext.TYPE_BODY;
                    }
                    return null;
                }
                if ("match".equals(kw)) {
                    return EditorContext.MATCH_BODY;
                }
            }

            // If we hit a non-indented line with an OPERATOR | at the start, we're in match territory
            if (firstNonWs.type() == TokenType.OPERATOR
                    && "|".equals(line.substring(firstNonWs.startCol(), firstNonWs.endCol()))) {
                continue; // keep scanning backwards for the match keyword
            }

            // If the line is indented, keep scanning (could be inside a type/match body)
            if (firstNonWs.startCol() > 0) continue;

            // Non-indented, non-keyword line — we're at top level
            return null;
        }
        return null;
    }

    private boolean allIndentedOrBlank(TextBuffer buffer, int fromRow, int toRow) {
        for (int r = fromRow; r < toRow; r++) {
            String line = buffer.getLine(r);
            if (line.isEmpty()) continue;
            if (line.charAt(0) != ' ' && line.charAt(0) != '\t') return false;
        }
        return true;
    }

    private Token firstNonWhitespace(List<Token> tokens) {
        for (Token t : tokens) {
            if (t.type() != TokenType.WHITESPACE) return t;
        }
        return null;
    }
}
