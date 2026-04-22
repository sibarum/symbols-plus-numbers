package spn.lang;

import spn.type.FieldDescriptor;
import spn.type.FieldType;
import spn.type.SpnFunctionDescriptor;
import spn.type.SpnStructDescriptor;
import spn.type.SpnTupleDescriptor;
import spn.type.SpnTypeDescriptor;
import spn.type.SpnVariantSet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Parses SPN type expressions into {@link FieldType} values.
 *
 * Extracted from {@link SpnParser} to isolate the type grammar. Handles:
 * <ul>
 *   <li>Primitive keywords: {@code int}, {@code float}, {@code string}, {@code bool}</li>
 *   <li>Named types via the struct / type / variant registries</li>
 *   <li>Untyped collections: {@code UntypedArray}, {@code UntypedSet}, {@code UntypedDict}.
 *       Legacy parameterised form {@code Array<T>}/{@code Set<T>}/{@code Dict<T>} is
 *       still accepted during the macro-v2 migration and produces the same untyped
 *       builtin; once the stdlib {@code Array<T>}/{@code Set<T>}/{@code Dict<K,V>}
 *       macros are in place, this legacy path is removed.</li>
 *   <li>Tuple types: {@code (T, U, V)}</li>
 *   <li>Anonymous unions: {@code Circle | Rectangle | Triangle}</li>
 *   <li>Function types named via {@code functionDescriptorRegistry}</li>
 *   <li>Untyped wildcard: {@code _}</li>
 * </ul>
 *
 * {@link #collectUnionMembers} is exposed package-private so the outer
 * parser's type-unification pass can reuse it when merging branch types.
 */
final class TypeParser {

    private static final Set<String> PRIMITIVE_TYPE_KEYWORDS =
            Set.of("int", "float", "string", "bool");

    private final SpnTokenizer tokens;
    private final Map<String, SpnStructDescriptor> structRegistry;
    private final Map<String, SpnTypeDescriptor> typeRegistry;
    private final Map<String, SpnVariantSet> variantRegistry;
    private final Map<String, SpnFunctionDescriptor> functionDescriptorRegistry;

    /** Called with (token, name) whenever a named type reference is resolved
     *  to a known declaration. Lets {@link SpnParser} record the use site
     *  for IDE go-to-def. May be null. */
    private final BiConsumer<SpnParseToken, String> typeRefRecorder;

    /** Names of currently-known macros. Used to detect {@code MacroName<Args>}
     *  appearing at a type position, so it delegates to the outer parser's
     *  expansion path instead of the legacy {@code Array<T>} parametric
     *  builtin parsing. May be null during bootstrap. */
    private final Set<String> macroNames;

    /** Trigger a macro expansion for a name already consumed by this parser.
     *  Called with the name token; the callback is expected to parse the
     *  opening delimiter + args + closer, run expansion, and return the
     *  resolved {@link FieldType} of the emitted type. May be null. */
    private final Function<SpnParseToken, FieldType> macroTypeExpander;

    TypeParser(SpnTokenizer tokens,
               Map<String, SpnStructDescriptor> structRegistry,
               Map<String, SpnTypeDescriptor> typeRegistry,
               Map<String, SpnVariantSet> variantRegistry,
               Map<String, SpnFunctionDescriptor> functionDescriptorRegistry,
               BiConsumer<SpnParseToken, String> typeRefRecorder,
               Set<String> macroNames,
               Function<SpnParseToken, FieldType> macroTypeExpander) {
        this.tokens = tokens;
        this.structRegistry = structRegistry;
        this.typeRegistry = typeRegistry;
        this.variantRegistry = variantRegistry;
        this.functionDescriptorRegistry = functionDescriptorRegistry;
        this.typeRefRecorder = typeRefRecorder;
        this.macroNames = macroNames;
        this.macroTypeExpander = macroTypeExpander;
    }

    /**
     * Parse a type expression, including anonymous union types:
     * {@code Circle | Rectangle}. Delegates to {@link #parseSingleFieldType()}
     * for each component.
     */
    FieldType parseFieldType() {
        FieldType first = parseSingleFieldType();

        if (!tokens.check("|")) return first;

        // Union type: Type | Type | ...
        List<SpnStructDescriptor> variants = new ArrayList<>();
        collectUnionMembers(variants, first);
        while (tokens.match("|")) {
            collectUnionMembers(variants, parseSingleFieldType());
        }

        // Sort for order-independence: Circle | Rectangle == Rectangle | Circle
        variants.sort(Comparator.comparing(SpnStructDescriptor::getName));
        // Deduplicate (after sorting, duplicates are adjacent)
        for (int i = variants.size() - 1; i > 0; i--) {
            if (variants.get(i) == variants.get(i - 1)) variants.remove(i);
        }

        if (variants.size() == 1) {
            return FieldType.ofStruct(variants.get(0));
        }

        String name = variants.stream()
                .map(SpnStructDescriptor::getName)
                .collect(Collectors.joining(" | "));
        return FieldType.ofVariant(new SpnVariantSet(name,
                variants.toArray(new SpnStructDescriptor[0])));
    }

    /**
     * Extract struct descriptors from a FieldType for union construction.
     * Package-private so {@link SpnParser#unifyTypes} can reuse the same
     * logic when merging branch-result types.
     */
    void collectUnionMembers(List<SpnStructDescriptor> variants, FieldType type) {
        if (type instanceof FieldType.OfStruct os) {
            variants.add(os.descriptor());
        } else if (type instanceof FieldType.OfVariant ov) {
            Collections.addAll(variants, ov.variantSet().getVariants());
        } else if (type instanceof FieldType.OfConstrainedType oct) {
            SpnStructDescriptor sd = structRegistry.get(oct.descriptor().getName());
            if (sd != null) { variants.add(sd); return; }
            throw tokens.error("Cannot use type '" + oct.descriptor().getName() + "' in union");
        } else if (type instanceof FieldType.OfProduct op) {
            SpnStructDescriptor sd = structRegistry.get(op.descriptor().getName());
            if (sd != null) { variants.add(sd); return; }
            throw tokens.error("Cannot use type '" + op.descriptor().getName() + "' in union");
        } else {
            throw tokens.error("Union types can only combine struct/data types, got: " + type.describe());
        }
    }

    /** Parse a single (non-union) type expression. */
    FieldType parseSingleFieldType() {
        SpnParseToken tok = tokens.peek();
        if (tok == null) throw tokens.error("Expected type, but reached end of input");

        // Primitive type keywords (int, float, string, bool)
        if (tok.type() == TokenType.KEYWORD && PRIMITIVE_TYPE_KEYWORDS.contains(tok.text())) {
            tokens.advance();
            return switch (tok.text()) {
                case "int" -> FieldType.LONG;
                case "float" -> FieldType.DOUBLE;
                case "string" -> FieldType.STRING;
                case "bool" -> FieldType.BOOLEAN;
                default -> FieldType.UNTYPED;
            };
        }

        if (tok.type() == TokenType.TYPE_NAME) {
            tokens.advance();
            String name = tok.text();

            // Macro invocation at type position: MacroName<Args> dispatches
            // to the outer parser's expansion machinery, which consumes the
            // angle-bracketed args, memoizes, and returns the emitted type.
            // All Name<T> in type position flows through macros — builtins
            // like Array/Set/Dict are declared as stdlib macros that emit
            // the untyped builtin.
            if (macroNames != null && macroTypeExpander != null
                    && macroNames.contains(name) && tokens.check("<")) {
                return macroTypeExpander.apply(tok);
            }

            return switch (name) {
                case "int", "Long" -> FieldType.LONG;
                case "float", "Double" -> FieldType.DOUBLE;
                case "string", "String" -> FieldType.STRING;
                case "bool", "Boolean" -> FieldType.BOOLEAN;
                case "Symbol" -> FieldType.SYMBOL;
                case "Array", "UntypedArray", "Collection" -> FieldType.ofArray(FieldType.UNTYPED);
                case "Set", "UntypedSet" -> FieldType.ofSet(FieldType.UNTYPED);
                case "Dict", "UntypedDict" -> FieldType.ofDictionary(FieldType.UNTYPED);
                case "Tuple" -> FieldType.ofTuple(SpnTupleDescriptor.untyped(0));
                default -> {
                    // Check registries
                    SpnStructDescriptor sd = structRegistry.get(name);
                    if (sd != null) {
                        if (typeRefRecorder != null) typeRefRecorder.accept(tok, name);
                        yield FieldType.ofStruct(sd);
                    }
                    SpnTypeDescriptor td = typeRegistry.get(name);
                    if (td != null) {
                        if (typeRefRecorder != null) typeRefRecorder.accept(tok, name);
                        yield td.isProduct()
                                ? FieldType.ofProduct(td) : FieldType.ofConstrainedType(td);
                    }
                    SpnVariantSet vs = variantRegistry.get(name);
                    if (vs != null) {
                        if (typeRefRecorder != null) typeRefRecorder.accept(tok, name);
                        yield FieldType.ofVariant(vs);
                    }
                    throw tokens.error("Unknown type: " + name, tok);
                }
            };
        }

        // Tuple type: (Type, Type, ...)
        if (tok.text().equals("(")) {
            tokens.advance();
            List<FieldType> elementTypes = new ArrayList<>();
            while (!tokens.check(")")) {
                elementTypes.add(parseFieldType());
                tokens.match(",");
            }
            tokens.expect(")");
            return FieldType.ofTuple(new SpnTupleDescriptor(
                    elementTypes.toArray(FieldType[]::new)));
        }

        if (tok.text().equals("_")) {
            tokens.advance();
            return FieldType.UNTYPED;
        }

        // Function name as type: uses the named function's signature structurally.
        // e.g., pure apply(myFunc, int) = ... where myFunc was declared as (int, int) -> int
        if (tok.type() == TokenType.IDENTIFIER) {
            SpnFunctionDescriptor fnDesc = functionDescriptorRegistry.get(tok.text());
            if (fnDesc != null) {
                tokens.advance();
                FieldDescriptor[] params = fnDesc.getParams();
                FieldType[] paramTypes = new FieldType[params.length];
                for (int i = 0; i < params.length; i++) {
                    paramTypes[i] = params[i].type();
                }
                return FieldType.ofFunction(paramTypes, fnDesc.getReturnType());
            }
        }

        throw tokens.error("Expected type name, got: " + tok.text(), tok);
    }
}
