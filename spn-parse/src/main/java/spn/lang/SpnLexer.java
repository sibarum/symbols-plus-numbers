package spn.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Hand-written line-local lexer for SPN syntax highlighting.
 * Stateless — each line is tokenized independently.
 */
public class SpnLexer {

    private static final Set<String> KEYWORDS = Set.of(
            "type", "where", "with", "data", "struct",
            "pure", "action", "let", "const", "while", "do", "match", "yield",
            "import", "module", "version", "require", "return",
            "int", "float", "string", "bool", "promote", "macro", "emit"
    );

    private static final Set<String> PATTERN_KEYWORDS = Set.of(
            "contains", "oneOf", "matches", "charset", "length"
    );

    public List<Token> tokenizeLine(String line) {
        List<Token> tokens = new ArrayList<>();
        int len = line.length();
        int pos = 0;
        TokenType prevNonWs = null;

        while (pos < len) {
            int start = pos;
            char ch = line.charAt(pos);

            // -- line comment
            if (ch == '-' && pos + 1 < len && line.charAt(pos + 1) == '-') {
                tokens.add(new Token(start, len, TokenType.COMMENT));
                return tokens;
            }

            // whitespace
            if (Character.isWhitespace(ch)) {
                while (pos < len && Character.isWhitespace(line.charAt(pos))) pos++;
                tokens.add(new Token(start, pos, TokenType.WHITESPACE));
                continue;
            }

            // string literal
            if (ch == '"') {
                pos++;
                while (pos < len) {
                    char c = line.charAt(pos);
                    if (c == '\\' && pos + 1 < len) { pos += 2; continue; }
                    if (c == '"') { pos++; break; }
                    pos++;
                }
                tokens.add(new Token(start, pos, TokenType.STRING));
                prevNonWs = TokenType.STRING;
                continue;
            }

            // regex literal — only if not preceded by value-producing token
            // and not followed by '(' (which indicates operator overload: pure /(Type, Type))
            if (ch == '/' && !isValueToken(prevNonWs)
                    && !(pos + 1 < len && line.charAt(pos + 1) == '(')) {
                pos++;
                while (pos < len) {
                    char c = line.charAt(pos);
                    if (c == '\\' && pos + 1 < len) { pos += 2; continue; }
                    if (c == '/') { pos++; break; }
                    pos++;
                }
                tokens.add(new Token(start, pos, TokenType.REGEX));
                prevNonWs = TokenType.REGEX;
                continue;
            }

            // symbol literal :name or :dotted.name
            if (ch == ':' && pos + 1 < len && isIdentStart(line.charAt(pos + 1))
                    && !isValueToken(prevNonWs)) {
                pos++;
                while (pos < len && (isIdentPart(line.charAt(pos)) || line.charAt(pos) == '.')) pos++;
                // strip trailing dot (e.g. ":foo." → ":foo" + ".")
                if (line.charAt(pos - 1) == '.') pos--;
                tokens.add(new Token(start, pos, TokenType.SYMBOL));
                prevNonWs = TokenType.SYMBOL;
                continue;
            }

            // number
            if (Character.isDigit(ch)) {
                while (pos < len && Character.isDigit(line.charAt(pos))) pos++;
                if (pos < len && line.charAt(pos) == '.') {
                    pos++;
                    while (pos < len && Character.isDigit(line.charAt(pos))) pos++;
                }
                tokens.add(new Token(start, pos, TokenType.NUMBER));
                prevNonWs = TokenType.NUMBER;
                continue;
            }

            // identifier / keyword / type name
            if (isIdentStart(ch)) {
                while (pos < len && isIdentPart(line.charAt(pos))) pos++;
                String word = line.substring(start, pos);
                TokenType type;
                if (KEYWORDS.contains(word)) type = TokenType.KEYWORD;
                else if (PATTERN_KEYWORDS.contains(word)) type = TokenType.PATTERN_KW;
                else if (Character.isUpperCase(ch)) type = TokenType.TYPE_NAME;
                else type = TokenType.IDENTIFIER;
                tokens.add(new Token(start, pos, type));
                prevNonWs = type;
                continue;
            }

            // multi-char operators, with optional _qualifier suffix (check before single-char)
            if (pos + 1 < len) {
                String two = line.substring(pos, pos + 2);
                if (isDoubleOperator(two)) {
                    pos += 2;
                    if (pos < len && line.charAt(pos) == '_') {
                        pos++; // consume _
                        while (pos < len && isIdentPart(line.charAt(pos))) pos++;
                    }
                    tokens.add(new Token(start, pos, TokenType.OPERATOR));
                    prevNonWs = TokenType.OPERATOR;
                    continue;
                }
            }

            // single-char operators, with optional _qualifier suffix (e.g., *_dot, +_cross)
            if (isOperatorChar(ch)) {
                pos++;
                if (pos < len && line.charAt(pos) == '_') {
                    pos++; // consume _
                    while (pos < len && isIdentPart(line.charAt(pos))) pos++;
                }
                tokens.add(new Token(start, pos, TokenType.OPERATOR));
                prevNonWs = TokenType.OPERATOR;
                continue;
            }

            // delimiters
            if (isDelimiter(ch)) {
                pos++;
                TokenType type = TokenType.DELIMITER;
                tokens.add(new Token(start, pos, type));
                // closing delimiters act like values for regex heuristic
                prevNonWs = (ch == ')' || ch == ']') ? TokenType.IDENTIFIER : type;
                continue;
            }

            // fallback: unknown single character
            pos++;
            tokens.add(new Token(start, pos, TokenType.IDENTIFIER));
            prevNonWs = TokenType.IDENTIFIER;
        }

        return tokens;
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }

    private static boolean isDoubleOperator(String s) {
        return switch (s) {
            case "==", "!=", ">=", "<=", "->", "++", "&&", "||" -> true;
            default -> false;
        };
    }

    private static boolean isOperatorChar(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/' || c == '%'
                || c == '=' || c == '<' || c == '>' || c == '|' || c == '&';
    }

    private static boolean isDelimiter(char c) {
        return c == '(' || c == ')' || c == '[' || c == ']'
                || c == '{' || c == '}' || c == ',' || c == '.';
    }

    /** True if the given token type represents a value that could precede a division `/`. */
    private static boolean isValueToken(TokenType t) {
        return t == TokenType.IDENTIFIER || t == TokenType.TYPE_NAME
                || t == TokenType.NUMBER || t == TokenType.STRING;
    }
}
