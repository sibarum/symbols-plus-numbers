package spn.reflect;

import spn.node.SpnExpressionNode;
import spn.node.SpnStatementNode;
import spn.node.expr.*;
import spn.node.match.MatchPattern;
import spn.node.match.SpnMatchBranchNode;
import spn.node.match.SpnMatchNode;
import spn.type.*;

/**
 * Reflects AST structures back to hypothetical SPN source code.
 *
 * This is a design tool for iterating on syntax. It renders type descriptors,
 * struct definitions, function signatures, expressions, and patterns into
 * readable source strings using the proposed SPN syntax.
 *
 * The output is not meant to be parseable (there's no parser yet) -- it's a
 * human-readable representation for syntax design discussions.
 */
public final class SpnSourceReflector {

    private SpnSourceReflector() {}

    // ── Type descriptors ────────────────────────────────────────────────────

    public static String reflectType(SpnTypeDescriptor type) {
        var sb = new StringBuilder("type ").append(type.getName());

        // Value parameter for scalar constrained types
        if (type.hasValueParam() && type.getComponentDescriptors().length == 0) {
            sb.append("(").append(type.getValueParam()).append(")");
        }

        // Product components
        ComponentDescriptor[] comps = type.getComponentDescriptors();
        if (comps.length > 0) {
            sb.append("(");
            for (int i = 0; i < comps.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(comps[i].name());
                if (!(comps[i].type() instanceof FieldType.Untyped)) {
                    sb.append(": ").append(comps[i].type().describe());
                }
                for (Constraint c : comps[i].constraints()) {
                    sb.append(" where ").append(c.describe());
                }
            }
            sb.append(")");
        }

        // Type-level constraints
        Constraint[] constraints = type.getConstraints();
        if (constraints.length > 0) {
            sb.append(" where ");
            for (int i = 0; i < constraints.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(type.describeConstraint(constraints[i]));
            }
        }

        // Distinguished elements
        SpnDistinguishedElement[] elements = type.getElements();
        if (elements.length > 0) {
            sb.append(" with ");
            for (int i = 0; i < elements.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(elements[i].getName());
            }
        }

        // Algebraic rules
        AlgebraicRule[] rules = type.getRules();
        if (rules.length > 0) {
            for (AlgebraicRule rule : rules) {
                sb.append("\n  rule ").append(rule.describe());
            }
        }

        // Product operation definitions
        ProductOperationDef[] ops = type.getProductOperationDefs();
        if (ops.length > 0) {
            for (ProductOperationDef op : ops) {
                sb.append("\n  ").append(op.operation().getSymbol()).append("(a, b) = (");
                ComponentExpression[] exprs = op.componentResults();
                for (int i = 0; i < exprs.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(exprs[i].describe());
                }
                sb.append(")");
            }
        }

        return sb.toString();
    }

    // ── Struct descriptors ──────────────────────────────────────────────────

    public static String reflectStruct(SpnStructDescriptor struct) {
        var sb = new StringBuilder("struct ").append(struct.getName());
        if (struct.isGeneric()) {
            sb.append("<");
            String[] params = struct.getTypeParams();
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(params[i]);
            }
            sb.append(">");
        }
        FieldDescriptor[] fields = struct.getFields();
        if (fields.length > 0) {
            sb.append("(");
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(fields[i].name());
                if (fields[i].isTyped()) {
                    sb.append(": ").append(fields[i].type().describe());
                }
            }
            sb.append(")");
        }
        return sb.toString();
    }

    /** Reflects a variant set as a data type declaration. */
    public static String reflectVariantSet(SpnVariantSet vs) {
        var sb = new StringBuilder("data ").append(vs.getName());
        SpnStructDescriptor[] variants = vs.getVariants();
        for (int i = 0; i < variants.length; i++) {
            sb.append(i == 0 ? "\n  = " : "\n  | ");
            sb.append(reflectStruct(variants[i]).substring("struct ".length()));
        }
        return sb.toString();
    }

    /** Reflects a symbol set as a type declaration. */
    public static String reflectSymbolSet(SpnSymbolSet ss) {
        var sb = new StringBuilder("type ").append(ss.getName()).append(" = Symbol where oneOf(");
        SpnSymbol[] symbols = ss.getSymbols();
        for (int i = 0; i < symbols.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(":").append(symbols[i].name());
        }
        return sb.append(")").toString();
    }

    // ── Function descriptors ────────────────────────────────────────────────

    /**
     * Reflects a function signature. The signature lists types only (no param names);
     * param names appear in the body binding: {@code pure add(Long, Long) -> Long = (a, b) { ... }}
     */
    public static String reflectFunction(SpnFunctionDescriptor func) {
        var sb = new StringBuilder();
        if (func.isPure()) sb.append("pure ");
        sb.append(func.getName()).append("(");
        FieldDescriptor[] params = func.getParams();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params[i].type().describe());
        }
        sb.append(")");
        if (func.hasTypedReturn()) {
            sb.append(" -> ").append(func.getReturnType().describe());
        }
        return sb.toString();
    }

    // ── Match patterns ──────────────────────────────────────────────────────

    public static String reflectPattern(MatchPattern pattern) {
        return switch (pattern) {
            case MatchPattern.Struct sp -> sp.descriptor().getName() + "("
                    + fieldBindPlaceholders(sp.descriptor().fieldCount()) + ")";
            case MatchPattern.Product pp -> pp.typeDescriptor().getName() + "("
                    + fieldBindPlaceholders(pp.typeDescriptor().componentCount()) + ")";
            case MatchPattern.Tuple tp -> "(" + typePlaceholders(tp.descriptor().getElementTypes()) + ")";
            case MatchPattern.EmptyArray _ -> "[]";
            case MatchPattern.ArrayHeadTail _ -> "[h | t]";
            case MatchPattern.ArrayExactLength al -> "[" + fieldBindPlaceholders(al.length()) + "]";
            case MatchPattern.EmptySet _ -> "{}";
            case MatchPattern.SetContaining sc -> "{contains " + elemList(sc.requiredElements()) + "}";
            case MatchPattern.EmptyDictionary _ -> "{:}";
            case MatchPattern.DictionaryKeys dk -> dictKeyPattern(dk.requiredKeys());
            case MatchPattern.StringPrefix sp -> "\"" + sp.prefix() + "\" ++ rest";
            case MatchPattern.StringSuffix ss -> "init ++ \"" + ss.suffix() + "\"";
            case MatchPattern.StringRegex sr -> "/" + sr.regex() + "/";
            case MatchPattern.OfType ot -> ot.fieldType().describe();
            case MatchPattern.Literal lit -> literalToString(lit.expected());
            case MatchPattern.Wildcard _ -> "_";
        };
    }

    // ── Expressions ─────────────────────────────────────────────────────────

    public static String reflectExpression(SpnExpressionNode node) {
        return switch (node) {
            case SpnLongLiteralNode n -> {
                // Access the value via executeGeneric with a null frame (literals don't use frame)
                try { yield String.valueOf(n.executeGeneric(null)); }
                catch (Exception e) { yield "<long>"; }
            }
            case SpnDoubleLiteralNode n -> {
                try { yield String.valueOf(n.executeGeneric(null)); }
                catch (Exception e) { yield "<double>"; }
            }
            case SpnBooleanLiteralNode n -> {
                try { yield String.valueOf(n.executeGeneric(null)); }
                catch (Exception e) { yield "<bool>"; }
            }
            case SpnStringLiteralNode n -> {
                try { yield "\"" + n.executeGeneric(null) + "\""; }
                catch (Exception e) { yield "<string>"; }
            }
            case SpnSymbolLiteralNode n -> {
                try { yield String.valueOf(n.executeGeneric(null)); }
                catch (Exception e) { yield "<symbol>"; }
            }
            case SpnMatchNode m -> reflectMatch(m);
            default -> "<expr:" + node.getClass().getSimpleName() + ">";
        };
    }

    // ── Tuple descriptors ───────────────────────────────────────────────────

    public static String reflectTuple(SpnTupleDescriptor tuple) {
        var sb = new StringBuilder("(");
        FieldType[] types = tuple.getElementTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(types[i].describe());
        }
        return sb.append(")").toString();
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private static String reflectMatch(SpnMatchNode match) {
        var sb = new StringBuilder("match <subject>");
        for (SpnMatchBranchNode branch : match.getBranches()) {
            sb.append("\n  | ").append(reflectPattern(branch.getPattern()));
            sb.append(" -> <body>");
        }
        return sb.toString();
    }

    private static String fieldBindPlaceholders(int count) {
        var sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append((char) ('a' + i));
        }
        return sb.toString();
    }

    private static String typePlaceholders(FieldType[] types) {
        var sb = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(types[i].describe());
        }
        return sb.toString();
    }

    private static String elemList(Object[] elements) {
        var sb = new StringBuilder();
        for (int i = 0; i < elements.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(literalToString(elements[i]));
        }
        return sb.toString();
    }

    private static String dictKeyPattern(SpnSymbol[] keys) {
        var sb = new StringBuilder("{");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(":").append(keys[i].name()).append(" ").append((char) ('a' + i));
        }
        return sb.append("}").toString();
    }

    private static String literalToString(Object value) {
        if (value instanceof String s) return "\"" + s + "\"";
        if (value instanceof SpnSymbol s) return ":" + s.name();
        return String.valueOf(value);
    }
}
