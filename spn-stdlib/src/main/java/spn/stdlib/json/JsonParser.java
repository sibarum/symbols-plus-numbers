package spn.stdlib.json;

import spn.type.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * Minimal recursive-descent JSON parser that produces SPN values directly.
 *
 * Mapping:
 *   JSON object  → SpnDictionaryValue (symbol keys)
 *   JSON array   → SpnArrayValue
 *   JSON string  → String
 *   JSON integer → Long
 *   JSON float   → Double
 *   JSON true/false → Boolean
 *   JSON null    → None (from Option ADT)
 */
final class JsonParser {

    private final String input;
    private int pos;

    JsonParser(String input) {
        this.input = input;
        this.pos = 0;
    }

    Object parse() {
        skipWhitespace();
        Object value = parseValue();
        skipWhitespace();
        return value;
    }

    private Object parseValue() {
        skipWhitespace();
        if (pos >= input.length()) throw error("Unexpected end of input");

        char c = input.charAt(pos);
        return switch (c) {
            case '"' -> parseString();
            case '{' -> parseObject();
            case '[' -> parseArray();
            case 't', 'f' -> parseBoolean();
            case 'n' -> parseNull();
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) yield parseNumber();
                throw error("Unexpected character: " + c);
            }
        };
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= input.length()) throw error("Unterminated escape");
                char esc = input.charAt(pos++);
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > input.length()) throw error("Invalid unicode escape");
                        sb.append((char) Integer.parseInt(input.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("Invalid escape: \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw error("Unterminated string");
    }

    private SpnDictionaryValue parseObject() {
        expect('{');
        skipWhitespace();
        var keys = new ArrayList<SpnSymbol>();
        var values = new ArrayList<Object>();
        long keyId = 0;
        if (peek() != '}') {
            do {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                keys.add(new SpnSymbol(key, keyId++));
                values.add(value);
                skipWhitespace();
            } while (match(','));
        }
        expect('}');

        Object[] keysAndValues = new Object[keys.size() * 2];
        for (int i = 0; i < keys.size(); i++) {
            keysAndValues[i * 2] = keys.get(i);
            keysAndValues[i * 2 + 1] = values.get(i);
        }
        return SpnDictionaryValue.of(FieldType.UNTYPED, keysAndValues);
    }

    private SpnArrayValue parseArray() {
        expect('[');
        skipWhitespace();
        var elements = new ArrayList<>();
        if (peek() != ']') {
            do {
                elements.add(parseValue());
                skipWhitespace();
            } while (match(','));
        }
        expect(']');
        return new SpnArrayValue(FieldType.UNTYPED, elements.toArray());
    }

    private Object parseNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        boolean isFloat = false;
        if (pos < input.length() && input.charAt(pos) == '.') {
            isFloat = true;
            pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        }
        if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
            isFloat = true;
            pos++;
            if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        }
        String num = input.substring(start, pos);
        if (isFloat) return Double.parseDouble(num);
        long val = Long.parseLong(num);
        return val;
    }

    private boolean parseBoolean() {
        if (input.startsWith("true", pos)) { pos += 4; return true; }
        if (input.startsWith("false", pos)) { pos += 5; return false; }
        throw error("Expected boolean");
    }

    private Object parseNull() {
        if (input.startsWith("null", pos)) {
            pos += 4;
            return spn.stdlib.option.SpnOptionDescriptors.none();
        }
        throw error("Expected null");
    }

    // ── Utilities ──────────────────────────────────────────────

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
    }

    private char peek() {
        return pos < input.length() ? input.charAt(pos) : 0;
    }

    private void expect(char c) {
        skipWhitespace();
        if (pos >= input.length() || input.charAt(pos) != c) {
            throw error("Expected '" + c + "'");
        }
        pos++;
    }

    private boolean match(char c) {
        skipWhitespace();
        if (pos < input.length() && input.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private RuntimeException error(String msg) {
        return new RuntimeException("JSON parse error at position " + pos + ": " + msg);
    }
}
