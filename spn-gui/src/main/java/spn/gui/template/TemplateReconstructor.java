package spn.gui.template;

import spn.gui.TextBuffer;
import spn.gui.lang.HighlightCache;
import spn.lang.Token;
import spn.lang.TokenType;

import java.util.List;

/**
 * Reconstructs a {@link TemplateOverlay} from existing well-formed source code.
 *
 * <p>Uses token-walking (not the full parser) to extract field values from
 * known syntactic constructs. Handles single-line and simple multi-line forms.
 */
public final class TemplateReconstructor {

    /**
     * Attempt to reconstruct a template from the code at the given row.
     * Returns null if no recognizable construct is found.
     *
     * @param buffer         the text buffer
     * @param highlightCache the token cache
     * @param row            the row to analyze (typically the cursor row)
     * @return a reconstruction result, or null
     */
    public static Reconstruction tryReconstruct(TextBuffer buffer, HighlightCache highlightCache,
                                                 int row) {
        String line = buffer.getLine(row);
        List<Token> tokens = highlightCache.getTokens(row, line);
        if (tokens.isEmpty()) return null;

        // Find the first non-whitespace token — should be a keyword
        Token first = null;
        for (Token t : tokens) {
            if (t.type() != TokenType.WHITESPACE) { first = t; break; }
        }
        if (first == null || first.type() != TokenType.KEYWORD) return null;

        String keyword = line.substring(first.startCol(), first.endCol());
        int indent = first.startCol();

        return switch (keyword) {
            case "struct" -> reconstructStruct(buffer, line, tokens, row, indent);
            case "data"   -> reconstructData(buffer, highlightCache, row, indent);
            case "type"   -> reconstructType(buffer, line, tokens, row, indent);
            case "pure"   -> reconstructPure(buffer, highlightCache, line, tokens, row, indent);
            case "let"    -> reconstructLet(buffer, line, tokens, row, indent);
            default -> null;
        };
    }

    // ---- struct Name(field1: Type1, field2: Type2) ----------------------

    private static Reconstruction reconstructStruct(TextBuffer buffer, String line,
                                                     List<Token> tokens, int row, int indent) {
        // Extract: name, then pairs of (field, type) inside parens
        String afterKeyword = line.substring(Math.min(indent + 7, line.length())).trim();
        int parenStart = afterKeyword.indexOf('(');
        if (parenStart < 0) return null;

        String name = afterKeyword.substring(0, parenStart).trim();
        int parenEnd = afterKeyword.lastIndexOf(')');
        if (parenEnd < 0) parenEnd = afterKeyword.length();

        String inner = afterKeyword.substring(parenStart + 1, parenEnd).trim();
        String[] pairs = inner.split(",");

        // Build field values: [name, field1, type1, field2, type2, ...]
        // Template expects exactly 5 values for struct
        String[] vals = new String[5];
        vals[0] = name;
        for (int i = 0; i < Math.min(pairs.length, 2); i++) {
            String pair = pairs[i].trim();
            int colon = pair.indexOf(':');
            if (colon >= 0) {
                vals[1 + i * 2] = pair.substring(0, colon).trim();
                vals[2 + i * 2] = pair.substring(colon + 1).trim();
            } else {
                vals[1 + i * 2] = pair;
                vals[2 + i * 2] = "";
            }
        }
        // Fill any remaining nulls
        for (int i = 0; i < vals.length; i++) if (vals[i] == null) vals[i] = "";

        TemplateDef def = TemplateCatalog.forKeyword("struct");
        if (def == null) return null;
        return new Reconstruction(def, row, indent, row, line.length(), vals);
    }

    // ---- data Name = Variant1(field) | Variant2(field) -----------------

    private static Reconstruction reconstructData(TextBuffer buffer,
                                                    HighlightCache highlightCache,
                                                    int row, int indent) {
        // Find extent: rows until a line that's not indented or pipe-prefixed
        int endRow = row;
        for (int r = row + 1; r < buffer.lineCount(); r++) {
            String l = buffer.getLine(r).trim();
            if (l.isEmpty() || l.startsWith("|")) { endRow = r; }
            else break;
        }

        // Collect all text
        StringBuilder full = new StringBuilder();
        for (int r = row; r <= endRow; r++) {
            if (r > row) full.append("\n");
            full.append(buffer.getLine(r));
        }
        String text = full.toString();

        // Parse: "data Name" then "= Variant1(field)" and "| Variant2(field)"
        String afterKeyword = text.substring(indent + 5).trim();
        // Split on first newline or = to get name
        int eqIdx = afterKeyword.indexOf('=');
        if (eqIdx < 0) return null;

        String name = afterKeyword.substring(0, eqIdx).trim();
        String rest = afterKeyword.substring(eqIdx + 1).trim();

        // Split variants by |
        String[] variants = rest.split("\\|");
        String[] vals = new String[5];
        vals[0] = name;
        for (int i = 0; i < Math.min(variants.length, 2); i++) {
            String v = variants[i].trim();
            int paren = v.indexOf('(');
            if (paren >= 0) {
                vals[1 + i * 2] = v.substring(0, paren).trim();
                int cp = v.lastIndexOf(')');
                vals[2 + i * 2] = (cp > paren) ? v.substring(paren + 1, cp).trim() : "";
            } else {
                vals[1 + i * 2] = v;
                vals[2 + i * 2] = "";
            }
        }
        for (int i = 0; i < vals.length; i++) if (vals[i] == null) vals[i] = "";

        TemplateDef def = TemplateCatalog.forKeyword("data");
        if (def == null) return null;
        int endCol = buffer.lineLength(endRow);
        return new Reconstruction(def, row, indent, endRow, endCol, vals);
    }

    // ---- type Name where constraints -----------------------------------

    private static Reconstruction reconstructType(TextBuffer buffer, String line,
                                                    List<Token> tokens, int row, int indent) {
        String afterKeyword = line.substring(indent + 5).trim();
        int whereIdx = afterKeyword.indexOf(" where ");

        String[] vals = new String[2];
        if (whereIdx >= 0) {
            vals[0] = afterKeyword.substring(0, whereIdx).trim();
            vals[1] = afterKeyword.substring(whereIdx + 7).trim();
        } else {
            // Could be a product type — check for parens
            int parenIdx = afterKeyword.indexOf('(');
            if (parenIdx >= 0) {
                vals[0] = afterKeyword.substring(0, parenIdx).trim();
                vals[1] = "";
            } else {
                vals[0] = afterKeyword;
                vals[1] = "";
            }
        }

        TemplateDef def = TemplateCatalog.forKeyword("type");
        if (def == null) return null;
        return new Reconstruction(def, row, indent, row, line.length(), vals);
    }

    // ---- pure name(Type1, Type2) -> RetType = (a, b) { body } ---------

    private static Reconstruction reconstructPure(TextBuffer buffer,
                                                    HighlightCache highlightCache,
                                                    String line, List<Token> tokens,
                                                    int row, int indent) {
        // Find the closing brace to determine extent
        int endRow = row;
        int braceDepth = 0;
        boolean foundOpen = false;
        outer:
        for (int r = row; r < buffer.lineCount(); r++) {
            String l = buffer.getLine(r);
            for (int c = 0; c < l.length(); c++) {
                if (l.charAt(c) == '{') { braceDepth++; foundOpen = true; }
                else if (l.charAt(c) == '}') {
                    braceDepth--;
                    if (foundOpen && braceDepth == 0) { endRow = r; break outer; }
                }
            }
        }

        // Collect all text
        StringBuilder full = new StringBuilder();
        for (int r = row; r <= endRow; r++) {
            if (r > row) full.append("\n");
            full.append(buffer.getLine(r));
        }
        String text = full.toString();

        // Parse: "pure name(Type1, Type2) -> RetType = (a, b) { body }"
        String afterPure = text.substring(indent + 5).trim();

        // name is up to first (
        int firstParen = afterPure.indexOf('(');
        if (firstParen < 0) return null;
        String name = afterPure.substring(0, firstParen).trim();

        // Types are between first ( and matching )
        int closeParen = findMatchingParen(afterPure, firstParen);
        if (closeParen < 0) return null;
        String typesStr = afterPure.substring(firstParen + 1, closeParen).trim();
        String[] types = typesStr.isEmpty() ? new String[0] : typesStr.split(",");

        // -> RetType
        String after = afterPure.substring(closeParen + 1).trim();
        String retType = "";
        if (after.startsWith("->")) {
            after = after.substring(2).trim();
            int eqIdx = after.indexOf('=');
            if (eqIdx >= 0) {
                retType = after.substring(0, eqIdx).trim();
                after = after.substring(eqIdx + 1).trim();
            }
        }

        // = (a, b) { body }
        String paramA = "", paramB = "", body = "";
        if (after.startsWith("(")) {
            int cp = findMatchingParen(after, 0);
            if (cp >= 0) {
                String paramsStr = after.substring(1, cp).trim();
                String[] params = paramsStr.split(",");
                if (params.length > 0) paramA = params[0].trim();
                if (params.length > 1) paramB = params[1].trim();
                after = after.substring(cp + 1).trim();
            }
        }
        // { body }
        if (after.startsWith("{")) {
            after = after.substring(1);
            if (after.endsWith("}")) after = after.substring(0, after.length() - 1);
            body = after.trim();
        }

        String[] vals = { name,
                types.length > 0 ? types[0].trim() : "",
                types.length > 1 ? types[1].trim() : "",
                retType, paramA, paramB, body };

        TemplateDef def = TemplateCatalog.forKeyword("pure");
        if (def == null) return null;
        int endCol = buffer.lineLength(endRow);
        return new Reconstruction(def, row, indent, endRow, endCol, vals);
    }

    // ---- let name = value ----------------------------------------------

    private static Reconstruction reconstructLet(TextBuffer buffer, String line,
                                                   List<Token> tokens, int row, int indent) {
        String afterKeyword = line.substring(indent + 4).trim();
        int eqIdx = afterKeyword.indexOf('=');

        String[] vals = new String[2];
        if (eqIdx >= 0) {
            vals[0] = afterKeyword.substring(0, eqIdx).trim();
            vals[1] = afterKeyword.substring(eqIdx + 1).trim();
        } else {
            vals[0] = afterKeyword;
            vals[1] = "";
        }

        TemplateDef def = TemplateCatalog.forKeyword("let");
        if (def == null) return null;
        return new Reconstruction(def, row, indent, row, line.length(), vals);
    }

    // ---- Helpers --------------------------------------------------------

    private static int findMatchingParen(String s, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < s.length(); i++) {
            if (s.charAt(i) == '(') depth++;
            else if (s.charAt(i) == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * Result of reconstructing a template from existing code.
     *
     * @param def        the template definition to use
     * @param startRow   first row of the existing code
     * @param startCol   first column (indent)
     * @param endRow     last row of the existing code
     * @param endCol     column past the last character on endRow
     * @param fieldValues pre-populated field values
     */
    public record Reconstruction(TemplateDef def, int startRow, int startCol,
                                  int endRow, int endCol, String[] fieldValues) {}
}
