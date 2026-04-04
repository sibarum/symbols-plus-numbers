package spn.gui.lang;

import java.util.EnumMap;
import java.util.List;

/** Maps each {@link EditorContext} to an ordered list of up to 9 {@link Suggestion}s. */
public class SuggestionProvider {

    private final EnumMap<EditorContext, List<Suggestion>> suggestions;

    public SuggestionProvider() {
        suggestions = new EnumMap<>(EditorContext.class);

        suggestions.put(EditorContext.TOP_LEVEL, List.of(
                new Suggestion("type",     "type Name where x > 0, x % 1 == 0\n  rule a + b = c\n  rule a * b = c"),
                new Suggestion("data",     "data Name\n  = Variant1(field)\n  | Variant2(field1, field2)"),
                new Suggestion("struct",   "struct Name(field1: Type, field2: Type)"),
                new Suggestion("function", "pure name(Type, Type) -> Type = (a, b) {\n    a + b\n}"),
                new Suggestion("let",      "let name = value"),
                new Suggestion("import",   "import Module"),
                new Suggestion("module",   "module Name")
        ));

        suggestions.put(EditorContext.FUNCTION_BODY, List.of(
                new Suggestion("let",    "let name = value"),
                new Suggestion("if",     "if condition {\n    value\n}"),
                new Suggestion("match",  "match value\n    | pattern -> result\n    | _       -> default"),
                new Suggestion("while",  "while range(0, 10) do (i) {\n    yield i\n}"),
                new Suggestion("return", "return value"),
                new Suggestion("yield",  "yield value")
        ));

        suggestions.put(EditorContext.TYPE_BODY, List.of(
                new Suggestion("rule",  "rule a + b = c"),
                new Suggestion("with",  "with Special"),
                new Suggestion("op",    "+(a, b) = (left[0] + right[0])")
        ));

        suggestions.put(EditorContext.MATCH_BODY, List.of(
                new Suggestion("branch",   "| pattern -> result"),
                new Suggestion("guard",    "| x | x > 0 -> result"),
                new Suggestion("wildcard", "| _ -> default"),
                new Suggestion("string",   "| \"prefix\" ++ rest -> rest"),
                new Suggestion("array",    "| [h | t] -> h")
        ));

        suggestions.put(EditorContext.WHILE_BODY, List.of(
                new Suggestion("let",    "let name = value"),
                new Suggestion("if",     "if condition {\n    value\n}"),
                new Suggestion("yield",  "yield value"),
                new Suggestion("return", "return value")
        ));

        suggestions.put(EditorContext.BLOCK, List.of(
                new Suggestion("let",    "let name = value"),
                new Suggestion("if",     "if condition {\n    value\n}"),
                new Suggestion("match",  "match value\n    | pattern -> result\n    | _       -> default"),
                new Suggestion("return", "return value")
        ));
    }

    /** Returns the suggestions for the given context (never null, may be empty). */
    public List<Suggestion> getSuggestions(EditorContext context) {
        List<Suggestion> list = suggestions.get(context);
        return list != null ? list : List.of();
    }
}
