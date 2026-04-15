package spn.lang;

import spn.node.match.MatchPattern;
import spn.type.FieldType;
import spn.type.SpnStructDescriptor;
import spn.type.SpnSymbol;
import spn.type.SpnSymbolTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses SPN match-branch patterns into {@link MatchPattern} AST nodes.
 *
 * Extracted from {@link SpnParser} to isolate the pattern grammar. The
 * outer parser only needs to call {@link #parsePattern()} for the leading
 * pattern of a match arm and {@link #parseNestedPattern(FieldType)} for
 * patterns appearing inside tuples or struct destructures.
 *
 * <p>Binding slots are allocated through a {@link ScopeProvider} adapter,
 * which the outer parser implements against its active {@code Scope}. This
 * keeps PatternParser unaware of the outer parser's scope stack.
 *
 * <p>Category mismatch checking (pattern-vs-subject-type) stays in
 * {@link SpnParser} because it runs over the already-parsed AST.
 */
final class PatternParser {

    /** Pattern + the slots that pattern's captures wrote into. */
    record ParsedPattern(MatchPattern pattern, int[] bindingSlots) {}

    /** Adapter for allocating frame slots in the outer parser's active scope. */
    interface ScopeProvider {
        int addLocal(String name);
        int addLocal(String name, FieldType expectedType);
    }

    private final SpnTokenizer tokens;
    private final Map<String, SpnStructDescriptor> structRegistry;
    private final SpnSymbolTable symbolTable;
    private final ScopeProvider scope;

    PatternParser(SpnTokenizer tokens,
                  Map<String, SpnStructDescriptor> structRegistry,
                  SpnSymbolTable symbolTable,
                  ScopeProvider scope) {
        this.tokens = tokens;
        this.structRegistry = structRegistry;
        this.symbolTable = symbolTable;
        this.scope = scope;
    }

    // ── Top-level pattern (first arm of a match branch) ──────────────────────

    ParsedPattern parsePattern() {
        SpnParseToken tok = tokens.peek();
        if (tok == null) throw tokens.error("Expected pattern, but reached end of input");

        // Wildcard: _
        if (tok.text().equals("_")) {
            tokens.advance();
            return new ParsedPattern(new MatchPattern.Wildcard(), new int[0]);
        }

        // Tuple pattern: (pattern, pattern, ...)
        if (tok.text().equals("(")) {
            tokens.advance(); // consume '('
            List<MatchPattern> elements = new ArrayList<>();
            while (!tokens.check(")")) {
                elements.add(parseNestedPattern());
                tokens.match(",");
            }
            tokens.expect(")");
            return new ParsedPattern(
                    new MatchPattern.TupleElements(
                            elements.toArray(MatchPattern[]::new), elements.size()),
                    new int[0]);
        }

        // Collection patterns: []
        if (tok.text().equals("[")) {
            tokens.advance();
            if (tokens.check("]")) {
                tokens.advance();
                return new ParsedPattern(new MatchPattern.EmptyArray(), new int[0]);
            }

            // [contains :red, :blue]
            if (tokens.check("contains")) {
                tokens.advance();
                List<Object> required = new ArrayList<>();
                while (!tokens.check("]")) {
                    if (tokens.checkType(TokenType.SYMBOL)) {
                        SpnParseToken s = tokens.advance();
                        required.add(symbolTable.intern(s.text().substring(1)));
                    } else {
                        required.add(parseLiteralValue());
                    }
                    tokens.match(",");
                }
                tokens.expect("]");
                return new ParsedPattern(
                        new MatchPattern.SetContaining(required.toArray()),
                        new int[0]);
            }

            // [h | t] — head/tail
            SpnParseToken first = tokens.peek();
            if (first == null) throw tokens.error("Expected pattern element after '['");
            SpnParseToken second = tokens.peek(1);
            if (second != null && second.text().equals("|")) {
                String headName = tokens.advance().text();
                tokens.advance(); // consume |
                String tailName = tokens.advance().text();
                tokens.expect("]");

                int headSlot = scope.addLocal(headName);
                int tailSlot = scope.addLocal(tailName);
                return new ParsedPattern(new MatchPattern.ArrayHeadTail(),
                        new int[]{headSlot, tailSlot});
            }

            // Dict keys pattern: [:name n, :age a]
            if (first.type() == TokenType.SYMBOL) {
                List<SpnSymbol> keys = new ArrayList<>();
                List<Integer> slots = new ArrayList<>();
                while (!tokens.check("]")) {
                    SpnParseToken symTok = tokens.expectType(TokenType.SYMBOL);
                    keys.add(symbolTable.intern(symTok.text().substring(1)));
                    String bindName = tokens.expectType(TokenType.IDENTIFIER).text();
                    slots.add(scope.addLocal(bindName));
                    tokens.match(",");
                }
                tokens.expect("]");
                return new ParsedPattern(
                        new MatchPattern.DictionaryKeys(keys.toArray(new SpnSymbol[0])),
                        slots.stream().mapToInt(Integer::intValue).toArray());
            }

            // [a, b, c] — exact-length array pattern
            List<String> names = new ArrayList<>();
            names.add(tokens.advance().text());
            while (tokens.match(",")) {
                names.add(tokens.advance().text());
            }
            tokens.expect("]");

            int[] slots = new int[names.size()];
            for (int i = 0; i < names.size(); i++) {
                slots[i] = scope.addLocal(names.get(i));
            }
            return new ParsedPattern(new MatchPattern.ArrayExactLength(names.size()), slots);
        }

        // String prefix pattern: "http://" ++ rest
        if (tok.type() == TokenType.STRING) {
            tokens.advance();
            String prefix = unescapeString(tok.text());
            if (tokens.match("++")) {
                String bindName = tokens.expectType(TokenType.IDENTIFIER).text();
                int slot = scope.addLocal(bindName);
                return new ParsedPattern(new MatchPattern.StringPrefix(prefix), new int[]{slot});
            }
            // Literal string pattern
            return new ParsedPattern(new MatchPattern.Literal(prefix), new int[0]);
        }

        // Regex pattern: /regex/(bindings)
        if (tok.type() == TokenType.REGEX) {
            tokens.advance();
            String regex = tok.text().substring(1, tok.text().length() - 1);
            if (tokens.check("(")) {
                tokens.advance();
                List<Integer> slots = new ArrayList<>();
                while (!tokens.check(")")) {
                    String bindName = tokens.advance().text();
                    if (bindName.equals("_")) {
                        slots.add(-1);
                    } else {
                        slots.add(scope.addLocal(bindName));
                    }
                    tokens.match(",");
                }
                tokens.expect(")");
                return new ParsedPattern(new MatchPattern.StringRegex(regex),
                        slots.stream().mapToInt(Integer::intValue).toArray());
            }
            return new ParsedPattern(new MatchPattern.StringRegex(regex), new int[0]);
        }

        // Symbol literal: :north
        if (tok.type() == TokenType.SYMBOL) {
            tokens.advance();
            SpnSymbol sym = symbolTable.intern(tok.text().substring(1));
            return new ParsedPattern(new MatchPattern.Literal(sym), new int[0]);
        }

        // Number literal
        if (tok.type() == TokenType.NUMBER) {
            tokens.advance();
            Object val = tok.text().contains(".")
                    ? (Object) Double.parseDouble(tok.text())
                    : (Object) Long.parseLong(tok.text());
            return new ParsedPattern(new MatchPattern.Literal(val), new int[0]);
        }

        // Struct pattern: TypeName or TypeName(pattern, pattern, ...)
        if (tok.type() == TokenType.TYPE_NAME) {
            tokens.advance();
            String typeName = tok.text();
            SpnStructDescriptor desc = structRegistry.get(typeName);

            if (tokens.check("(")) {
                tokens.advance();
                MatchPattern[] fieldPatterns = parseStructFieldPatterns(desc, typeName, tok);
                tokens.expect(")");

                if (desc != null) {
                    return new ParsedPattern(
                            new MatchPattern.StructDestructure(desc, fieldPatterns),
                            new int[0]);
                }
                // Fall through for unknown types
            }

            if (desc != null) {
                return new ParsedPattern(new MatchPattern.Struct(desc), new int[0]);
            }
            throw tokens.error("Unknown type in pattern: " + typeName, tok);
        }

        // Identifier — binds the whole value to a variable
        if (tok.type() == TokenType.IDENTIFIER) {
            tokens.advance();
            int slot = scope.addLocal(tok.text());
            return new ParsedPattern(new MatchPattern.Wildcard(), new int[]{slot});
        }

        throw tokens.error("Expected pattern, got: " + tok.text(), tok);
    }

    // ── Nested patterns (tuple elements, struct fields) ──────────────────────

    MatchPattern parseNestedPattern() {
        return parseNestedPattern(null);
    }

    /**
     * Parse a pattern inside a composite context (tuple element, struct field).
     * Returns a MatchPattern directly — variable bindings use Capture with
     * embedded frame slots, so no external bindingSlots array is needed.
     *
     * @param expected the known type of the value this pattern will match
     *                 against (i.e. the enclosing struct's field type at this
     *                 position), used to infer the type of captured identifier
     *                 bindings. Null means the type is unknown.
     */
    MatchPattern parseNestedPattern(FieldType expected) {
        SpnParseToken tok = tokens.peek();
        if (tok == null) throw tokens.error("Expected pattern element");

        // Wildcard: _
        if (tok.text().equals("_")) {
            tokens.advance();
            return new MatchPattern.Wildcard();
        }

        // Boolean literals: true / false
        // (must come before the IDENTIFIER fallback or they'd be treated as captures)
        if (tok.text().equals("true") && tok.type() == TokenType.IDENTIFIER) {
            tokens.advance();
            return new MatchPattern.Literal(Boolean.TRUE);
        }
        if (tok.text().equals("false") && tok.type() == TokenType.IDENTIFIER) {
            tokens.advance();
            return new MatchPattern.Literal(Boolean.FALSE);
        }

        // Number literal
        if (tok.type() == TokenType.NUMBER) {
            tokens.advance();
            Object val = tok.text().contains(".")
                    ? (Object) Double.parseDouble(tok.text())
                    : (Object) Long.parseLong(tok.text());
            return new MatchPattern.Literal(val);
        }

        // String literal
        if (tok.type() == TokenType.STRING) {
            tokens.advance();
            return new MatchPattern.Literal(unescapeString(tok.text()));
        }

        // Symbol literal
        if (tok.type() == TokenType.SYMBOL) {
            tokens.advance();
            return new MatchPattern.Literal(symbolTable.intern(tok.text().substring(1)));
        }

        // Nested tuple: (pattern, pattern, ...)
        if (tok.text().equals("(")) {
            tokens.advance();
            List<MatchPattern> elements = new ArrayList<>();
            while (!tokens.check(")")) {
                elements.add(parseNestedPattern());
                tokens.match(",");
            }
            tokens.expect(")");
            return new MatchPattern.TupleElements(
                    elements.toArray(MatchPattern[]::new), elements.size());
        }

        // Struct deconstruction: TypeName(pattern, pattern, ...) or bare TypeName
        if (tok.type() == TokenType.TYPE_NAME) {
            tokens.advance();
            String typeName = tok.text();
            SpnStructDescriptor desc = structRegistry.get(typeName);
            if (desc == null) throw tokens.error("Unknown type in pattern: " + typeName, tok);

            if (tokens.check("(")) {
                tokens.advance();
                MatchPattern[] fieldPatterns = parseStructFieldPatterns(desc, typeName, tok);
                tokens.expect(")");
                return new MatchPattern.StructDestructure(desc, fieldPatterns);
            }
            return new MatchPattern.Struct(desc);
        }

        // Identifier — variable capture (types from enclosing struct field)
        if (tok.type() == TokenType.IDENTIFIER) {
            tokens.advance();
            int slot = scope.addLocal(tok.text(), expected);
            return new MatchPattern.Capture(slot);
        }

        throw tokens.error("Unexpected token in pattern: " + tok.text(), tok);
    }

    // ── Struct field-pattern list (positional or named) ──────────────────────

    /**
     * Parse a struct's field-pattern list after the opening '('. Decides between
     * positional and named form by looking at the first two tokens:
     * <ul>
     *   <li>{@code IDENTIFIER '='} → named form: {@code (field = pattern, ...)}</li>
     *   <li>anything else → positional form: {@code (pattern, pattern, ...)}</li>
     * </ul>
     *
     * Named form: fields may appear in any order, unspecified fields default to
     * Wildcard, duplicate field names are an error, and each binding takes the
     * declared field's type from the descriptor.
     *
     * <p>Named form requires a known descriptor ({@code desc != null}). If the
     * caller has no descriptor, only positional form is supported (unknown types
     * fall back to positional parsing and the caller handles the missing desc).
     *
     * @return the fieldPatterns array in POSITIONAL order (one slot per struct
     *         field), ready for {@link MatchPattern.StructDestructure}
     */
    private MatchPattern[] parseStructFieldPatterns(
            SpnStructDescriptor desc, String typeName, SpnParseToken originTok) {

        boolean named = isNamedFieldForm();

        if (!named) {
            // Positional: existing behavior.
            List<MatchPattern> fieldPatterns = new ArrayList<>();
            int fieldIdx = 0;
            while (!tokens.check(")")) {
                FieldType fieldType = (desc != null && fieldIdx < desc.fieldCount())
                        ? desc.fieldType(fieldIdx) : null;
                fieldPatterns.add(parseNestedPattern(fieldType));
                tokens.match(",");
                fieldIdx++;
            }
            return fieldPatterns.toArray(MatchPattern[]::new);
        }

        // Named form: Type(field = pattern, field = pattern, ...)
        // Requires a known descriptor so we can resolve names to positional indices.
        if (desc == null) {
            throw tokens.error("Named-field pattern requires a known type, but "
                    + typeName + " is unresolved", originTok);
        }

        MatchPattern[] byIndex = new MatchPattern[desc.fieldCount()];
        // Defaults: any field left unspecified matches anything.
        for (int i = 0; i < byIndex.length; i++) byIndex[i] = new MatchPattern.Wildcard();

        boolean[] seen = new boolean[desc.fieldCount()];

        while (!tokens.check(")")) {
            SpnParseToken nameTok = tokens.expectType(TokenType.IDENTIFIER);
            String fieldName = nameTok.text();
            int idx = desc.fieldIndex(fieldName);
            if (idx < 0) {
                throw tokens.error(typeName + " has no field '" + fieldName + "'", nameTok);
            }
            if (seen[idx]) {
                throw tokens.error("Duplicate field '" + fieldName
                        + "' in named pattern for " + typeName, nameTok);
            }
            seen[idx] = true;

            tokens.expect("=");
            FieldType fieldType = desc.fieldType(idx);
            byIndex[idx] = parseNestedPattern(fieldType);

            tokens.match(",");
        }
        return byIndex;
    }

    /**
     * After consuming '(' in a struct pattern, check whether the list that
     * follows is in named form. Named form starts with {@code IDENTIFIER '='}.
     */
    private boolean isNamedFieldForm() {
        SpnParseToken first = tokens.peek();
        SpnParseToken second = tokens.peek(1);
        if (first == null || second == null) return false;
        return first.type() == TokenType.IDENTIFIER && "=".equals(second.text());
    }

    // ── Local helpers ────────────────────────────────────────────────────────

    private Object parseLiteralValue() {
        SpnParseToken tok = tokens.advance();
        if (tok.type() == TokenType.NUMBER) {
            return tok.text().contains(".")
                    ? (Object) Double.parseDouble(tok.text())
                    : (Object) Long.parseLong(tok.text());
        }
        if (tok.type() == TokenType.STRING) {
            return unescapeString(tok.text());
        }
        throw tokens.error("Expected literal value, got: " + tok.text(), tok);
    }

    private static String unescapeString(String raw) {
        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        return raw.replace("\\n", "\n")
                  .replace("\\t", "\t")
                  .replace("\\\\", "\\")
                  .replace("\\\"", "\"");
    }
}
