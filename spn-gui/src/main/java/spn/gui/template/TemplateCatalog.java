package spn.gui.template;

import spn.gui.template.TemplateDef.TemplateCell;
import spn.gui.template.TemplateDef.TemplateField;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Static catalog of all template definitions for SPN language constructs.
 *
 * <p>Each template defines the grid layout (fixed cells + editable fields)
 * and an emitter that converts field values to source text.
 */
public final class TemplateCatalog {

    private static final Map<String, TemplateDef> BY_KEYWORD = new HashMap<>();

    static {
        register(structTemplate());
        register(dataTemplate());
        register(typeScalarTemplate());
        register(typeProductTemplate());
        register(pureTemplate());
        register(letTemplate());
        register(matchTemplate());
        register(whileTemplate());
        register(ifTemplate());
    }

    private static void register(TemplateDef def) {
        BY_KEYWORD.put(def.keyword(), def);
    }

    /** Look up a template by keyword, or null if not found. */
    public static TemplateDef forKeyword(String keyword) {
        return BY_KEYWORD.get(keyword);
    }

    /** All registered template keywords. */
    public static Iterable<String> keywords() {
        return BY_KEYWORD.keySet();
    }

    // ---- Template definitions -------------------------------------------

    // struct Name(field1: Type1, field2: Type2)
    private static TemplateDef structTemplate() {
        return new TemplateDef("struct",
                List.of(
                        new TemplateCell(0, 0, "struct "),
                        new TemplateCell(0, 15, "("),
                        new TemplateCell(0, 26, ": "),
                        new TemplateCell(0, 36, ", "),
                        new TemplateCell(0, 48, ": "),
                        new TemplateCell(0, 58, ")")
                ),
                List.of(
                        new TemplateField(0, 7, 8, "Name", ""),
                        new TemplateField(0, 16, 10, "field1", ""),
                        new TemplateField(0, 28, 8, "Type1", ""),
                        new TemplateField(0, 38, 10, "field2", ""),
                        new TemplateField(0, 50, 8, "Type2", "")
                ),
                (vals, indent) -> {
                    String pad = " ".repeat(indent);
                    StringBuilder sb = new StringBuilder();
                    sb.append(pad).append("struct ").append(vals[0]).append("(");
                    // Emit non-empty field pairs
                    boolean first = true;
                    for (int i = 1; i + 1 < vals.length; i += 2) {
                        if (vals[i].isEmpty() && vals[i + 1].isEmpty()) continue;
                        if (!first) sb.append(", ");
                        sb.append(vals[i]);
                        if (!vals[i + 1].isEmpty()) sb.append(": ").append(vals[i + 1]);
                        first = false;
                    }
                    sb.append(")");
                    return sb.toString();
                });
    }

    // data Name = Variant1(field) | Variant2(field)
    private static TemplateDef dataTemplate() {
        return new TemplateDef("data",
                List.of(
                        new TemplateCell(0, 0, "data "),
                        new TemplateCell(1, 0, "  = "),
                        new TemplateCell(1, 14, "("),
                        new TemplateCell(1, 25, ")"),
                        new TemplateCell(2, 0, "  | "),
                        new TemplateCell(2, 14, "("),
                        new TemplateCell(2, 25, ")")
                ),
                List.of(
                        new TemplateField(0, 5, 10, "Name", ""),
                        new TemplateField(1, 4, 10, "Variant1", ""),
                        new TemplateField(1, 15, 10, "field1", ""),
                        new TemplateField(2, 4, 10, "Variant2", ""),
                        new TemplateField(2, 15, 10, "field2", "")
                ),
                (vals, indent) -> {
                    String pad = " ".repeat(indent);
                    StringBuilder sb = new StringBuilder();
                    sb.append(pad).append("data ").append(vals[0]).append("\n");
                    sb.append(pad).append("  = ").append(vals[1]);
                    if (!vals[2].isEmpty()) sb.append("(").append(vals[2]).append(")");
                    sb.append("\n");
                    if (!vals[3].isEmpty()) {
                        sb.append(pad).append("  | ").append(vals[3]);
                        if (!vals[4].isEmpty()) sb.append("(").append(vals[4]).append(")");
                    }
                    return sb.toString().stripTrailing();
                });
    }

    // type Name where constraint1, constraint2
    private static TemplateDef typeScalarTemplate() {
        return new TemplateDef("type",
                List.of(
                        new TemplateCell(0, 0, "type "),
                        new TemplateCell(0, 15, " where ")
                ),
                List.of(
                        new TemplateField(0, 5, 10, "Name", ""),
                        new TemplateField(0, 22, 30, "constraints", "")
                ),
                (vals, indent) -> {
                    String pad = " ".repeat(indent);
                    StringBuilder sb = new StringBuilder();
                    sb.append(pad).append("type ").append(vals[0]);
                    if (!vals[1].isEmpty()) sb.append(" where ").append(vals[1]);
                    return sb.toString();
                });
    }

    // type Name(comp1: Type1, comp2: Type2)
    //   +(a, b) = (expr)
    private static TemplateDef typeProductTemplate() {
        return new TemplateDef("typeprod",
                List.of(
                        new TemplateCell(0, 0, "type "),
                        new TemplateCell(0, 15, "("),
                        new TemplateCell(0, 26, ": "),
                        new TemplateCell(0, 36, ", "),
                        new TemplateCell(0, 48, ": "),
                        new TemplateCell(0, 58, ")"),
                        new TemplateCell(1, 0, "  "),
                        new TemplateCell(1, 5, "(a, b) = ")
                ),
                List.of(
                        new TemplateField(0, 5, 10, "Name", ""),
                        new TemplateField(0, 16, 10, "comp1", ""),
                        new TemplateField(0, 28, 8, "Type1", ""),
                        new TemplateField(0, 38, 10, "comp2", ""),
                        new TemplateField(0, 50, 8, "Type2", ""),
                        new TemplateField(1, 2, 3, "op", "+"),
                        new TemplateField(1, 14, 40, "expr", "")
                ),
                (vals, indent) -> {
                    String pad = " ".repeat(indent);
                    StringBuilder sb = new StringBuilder();
                    sb.append(pad).append("type ").append(vals[0]).append("(");
                    boolean first = true;
                    for (int i = 1; i + 1 < 5; i += 2) {
                        if (vals[i].isEmpty() && vals[i + 1].isEmpty()) continue;
                        if (!first) sb.append(", ");
                        sb.append(vals[i]);
                        if (!vals[i + 1].isEmpty()) sb.append(": ").append(vals[i + 1]);
                        first = false;
                    }
                    sb.append(")");
                    if (!vals[5].isEmpty() && !vals[6].isEmpty()) {
                        sb.append("\n").append(pad).append("  ").append(vals[5])
                          .append("(a, b) = ").append(vals[6]);
                    }
                    return sb.toString();
                });
    }

    // pure name(Type1, Type2) -> RetType = (a, b) {
    //     body
    // }
    private static TemplateDef pureTemplate() {
        return new TemplateDef("pure",
                List.of(
                        new TemplateCell(0, 0, "pure "),
                        new TemplateCell(0, 15, "("),
                        new TemplateCell(0, 24, ", "),
                        new TemplateCell(0, 34, ") -> "),
                        new TemplateCell(0, 47, " = ("),
                        new TemplateCell(0, 55, ", "),
                        new TemplateCell(0, 61, ") {"),
                        new TemplateCell(1, 0, "    "),
                        new TemplateCell(2, 0, "}")
                ),
                List.of(
                        new TemplateField(0, 5, 10, "name", ""),
                        new TemplateField(0, 16, 8, "Type1", ""),
                        new TemplateField(0, 26, 8, "Type2", ""),
                        new TemplateField(0, 39, 8, "RetType", ""),
                        new TemplateField(0, 51, 4, "a", ""),
                        new TemplateField(0, 57, 4, "b", ""),
                        new TemplateField(1, 4, 40, "body", "")
                ),
                (vals, indent) -> {
                    String pad = " ".repeat(indent);
                    StringBuilder sb = new StringBuilder();
                    sb.append(pad).append("pure ").append(vals[0]).append("(");
                    // Parameter types
                    boolean first = true;
                    if (!vals[1].isEmpty()) { sb.append(vals[1]); first = false; }
                    if (!vals[2].isEmpty()) {
                        if (!first) sb.append(", ");
                        sb.append(vals[2]);
                    }
                    sb.append(") -> ").append(vals[3]).append(" = (");
                    // Parameter names
                    first = true;
                    if (!vals[4].isEmpty()) { sb.append(vals[4]); first = false; }
                    if (!vals[5].isEmpty()) {
                        if (!first) sb.append(", ");
                        sb.append(vals[5]);
                    }
                    sb.append(") {\n");
                    sb.append(pad).append("    ").append(vals[6]).append("\n");
                    sb.append(pad).append("}");
                    return sb.toString();
                });
    }

    // let name = value
    private static TemplateDef letTemplate() {
        return new TemplateDef("let",
                List.of(
                        new TemplateCell(0, 0, "let "),
                        new TemplateCell(0, 14, " = ")
                ),
                List.of(
                        new TemplateField(0, 4, 10, "name", ""),
                        new TemplateField(0, 17, 20, "value", "")
                ),
                (vals, indent) -> {
                    String pad = " ".repeat(indent);
                    return pad + "let " + vals[0] + " = " + vals[1];
                });
    }

    // match expr
    //     | pattern1 -> result1
    //     | pattern2 -> result2
    private static TemplateDef matchTemplate() {
        return new TemplateDef("match",
                List.of(
                        new TemplateCell(0, 0, "match "),
                        new TemplateCell(1, 0, "    | "),
                        new TemplateCell(1, 22, " -> "),
                        new TemplateCell(2, 0, "    | "),
                        new TemplateCell(2, 22, " -> ")
                ),
                List.of(
                        new TemplateField(0, 6, 14, "expr", ""),
                        new TemplateField(1, 6, 16, "pattern1", ""),
                        new TemplateField(1, 26, 20, "result1", ""),
                        new TemplateField(2, 6, 16, "pattern2", ""),
                        new TemplateField(2, 26, 20, "result2", "")
                ),
                (vals, indent) -> {
                    String pad = " ".repeat(indent);
                    StringBuilder sb = new StringBuilder();
                    sb.append(pad).append("match ").append(vals[0]).append("\n");
                    sb.append(pad).append("    | ").append(vals[1])
                      .append(" -> ").append(vals[2]);
                    if (!vals[3].isEmpty()) {
                        sb.append("\n").append(pad).append("    | ").append(vals[3])
                          .append(" -> ").append(vals[4]);
                    }
                    return sb.toString();
                });
    }

    // while range(start, end) do (i) {
    //     body
    // }
    private static TemplateDef whileTemplate() {
        return new TemplateDef("while",
                List.of(
                        new TemplateCell(0, 0, "while "),
                        new TemplateCell(0, 26, " do ("),
                        new TemplateCell(0, 35, ") {"),
                        new TemplateCell(1, 0, "    "),
                        new TemplateCell(2, 0, "}")
                ),
                List.of(
                        new TemplateField(0, 6, 20, "producer", "range(1, 10)"),
                        new TemplateField(0, 31, 4, "var", "i"),
                        new TemplateField(1, 4, 40, "body", "")
                ),
                (vals, indent) -> {
                    String pad = " ".repeat(indent);
                    StringBuilder sb = new StringBuilder();
                    sb.append(pad).append("while ").append(vals[0])
                      .append(" do (").append(vals[1]).append(") {\n");
                    sb.append(pad).append("    ").append(vals[2]).append("\n");
                    sb.append(pad).append("}");
                    return sb.toString();
                });
    }

    // if condition { value }
    private static TemplateDef ifTemplate() {
        return new TemplateDef("if",
                List.of(
                        new TemplateCell(0, 0, "if "),
                        new TemplateCell(0, 23, " { "),
                        new TemplateCell(0, 46, " }")
                ),
                List.of(
                        new TemplateField(0, 3, 20, "condition", ""),
                        new TemplateField(0, 26, 20, "value", "")
                ),
                (vals, indent) -> {
                    String pad = " ".repeat(indent);
                    return pad + "if " + vals[0] + " { " + vals[1] + " }";
                });
    }
}
