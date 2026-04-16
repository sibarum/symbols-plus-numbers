package spn.lang;

import com.oracle.truffle.api.CallTarget;
import spn.node.SpnExpressionNode;
import spn.node.func.SpnInvokeNode;
import spn.type.FieldDescriptor;
import spn.type.FieldType;
import spn.type.SpnFunctionDescriptor;
import spn.type.SpnStructDescriptor;
import spn.type.SpnSymbol;
import spn.type.SpnTypeDescriptor;
import spn.type.SpnVariantSet;

import java.util.*;

/**
 * Compile-time type resolver for SPN.
 *
 * Runs during parsing at well-defined hook points. For each expression the
 * parser builds, the resolver tracks its type and — when operators, methods,
 * or field accesses are involved — decides which concrete implementation to
 * call, replacing the generic primitive node with a direct {@link SpnInvokeNode}.
 *
 * <h3>Every dispatch decision is recorded</h3>
 * The IDE can query {@link #dispatchAt} to discover what a given expression
 * node resolved to: which overload, which promotion chain, which method.
 * This powers visual feedback like "this + calls +(Rational, Rational)".
 *
 * <h3>Design principles</h3>
 * <ul>
 *   <li><b>Compile-time certainty</b>: every operator, method, and field
 *       access is fully resolved before execution. No runtime dispatch.</li>
 *   <li><b>Definition-order</b>: types and overloads are registered top-down;
 *       each resolution can only see declarations above it.</li>
 *   <li><b>Incremental</b>: the resolver tracks which line each inference
 *       result was derived from. Invalidating from line N clears results
 *       at N+ and re-resolves.</li>
 *   <li><b>Separate from the parser</b>: the parser builds structural AST
 *       nodes; the resolver enriches them with type-directed rewrites.</li>
 * </ul>
 */
public final class TypeResolver {

    // ── Registries (borrowed from SpnParser until TypeGraph migration) ───────

    private final Map<String, List<SpnParser.OperatorOverload>> operatorRegistry;
    private final List<SpnParser.Promotion> promotionRegistry;
    private final Map<String, SpnParser.MethodEntry> methodRegistry;
    private final Map<String, SpnStructDescriptor> structRegistry;
    private final Map<String, SpnTypeDescriptor> typeRegistry;
    private final Map<String, SpnVariantSet> variantRegistry;
    private final Map<String, SpnFunctionDescriptor> functionDescriptorRegistry;

    // ── Type tracking ───────────────────────────────────────────────────────

    /** Expression → inferred type. Populated as the parser builds the AST. */
    private final IdentityHashMap<SpnExpressionNode, FieldType> exprTypes = new IdentityHashMap<>();

    /** Expression → dispatch record. The IDE reads this for visual feedback. */
    private final IdentityHashMap<SpnExpressionNode, DispatchRecord> dispatches = new IdentityHashMap<>();

    // ── Dispatch record (for IDE display) ───────────────────────────────────

    /** What a given expression resolved to at compile time. */
    public record DispatchRecord(
        String operatorOrMethod,      // "+", "neg", "inv", "area", etc.
        String resolvedTarget,        // "+(Rational, Rational)" or "Rational.neg()"
        int promotionsApplied,        // 0 = exact match, 1+ = promotion chain used
        CallTarget callTarget         // the actual target (for cross-reference)
    ) {}

    // ── Constructor ─────────────────────────────────────────────────────────

    TypeResolver(Map<String, List<SpnParser.OperatorOverload>> operatorRegistry,
                 List<SpnParser.Promotion> promotionRegistry,
                 Map<String, SpnParser.MethodEntry> methodRegistry,
                 Map<String, SpnStructDescriptor> structRegistry,
                 Map<String, SpnTypeDescriptor> typeRegistry,
                 Map<String, SpnVariantSet> variantRegistry,
                 Map<String, SpnFunctionDescriptor> functionDescriptorRegistry) {
        this.operatorRegistry = operatorRegistry;
        this.promotionRegistry = promotionRegistry;
        this.methodRegistry = methodRegistry;
        this.structRegistry = structRegistry;
        this.typeRegistry = typeRegistry;
        this.variantRegistry = variantRegistry;
        this.functionDescriptorRegistry = functionDescriptorRegistry;
    }

    // ── Type tracking ───────────────────────────────────────────────────────

    /** Tag an expression with its inferred type. */
    public void trackType(SpnExpressionNode expr, FieldType type) {
        if (type != null) exprTypes.put(expr, type);
    }

    /** Look up the inferred type for an expression. */
    public FieldType inferType(SpnExpressionNode expr) {
        // Check tracked type first (set by let bindings, variable reads, etc.)
        FieldType tracked = exprTypes.get(expr);
        if (tracked != null) return tracked;
        // Structural inference: derive type from the node class itself
        if (expr instanceof spn.node.struct.SpnStructConstructNode sc) {
            SpnStructDescriptor desc = sc.getDescriptor();
            if (desc != null) {
                SpnTypeDescriptor td = typeRegistry.get(desc.getName());
                if (td != null) return FieldType.ofConstrainedType(td);
                return FieldType.ofStruct(desc);
            }
        }
        if (expr instanceof spn.node.type.SpnProductConstructNode pc) {
            return FieldType.ofProduct(pc.getDescriptor());
        }
        if (expr instanceof spn.node.struct.SpnTupleConstructNode tc) {
            return FieldType.ofTuple(tc.getDescriptor());
        }
        // Literals
        if (expr instanceof spn.node.expr.SpnLongLiteralNode) return FieldType.LONG;
        if (expr instanceof spn.node.expr.SpnDoubleLiteralNode) return FieldType.DOUBLE;
        if (expr instanceof spn.node.expr.SpnStringLiteralNode) return FieldType.STRING;
        if (expr instanceof spn.node.expr.SpnBooleanLiteralNode) return FieldType.BOOLEAN;
        if (expr instanceof spn.node.expr.SpnSymbolLiteralNode) return FieldType.SYMBOL;
        return null;
    }

    // ── Operator dispatch ───────────────────────────────────────────────────

    /**
     * Try to resolve a binary operator to a registered overload. Returns a
     * direct {@link SpnInvokeNode} with any needed promotions applied, or
     * null if no user-defined overload matches (caller should use the
     * primitive built-in node).
     *
     * <p>Dispatch strategy (in order):
     * <ol>
     *   <li>Exact match on both operand types (cost 0)</li>
     *   <li>If both primitives, stop — built-in handles it</li>
     *   <li>BFS the promotion graph, find minimum-cost match</li>
     * </ol>
     *
     * <p>The dispatch decision is recorded in {@link #dispatches} so the IDE
     * can display which overload was chosen.
     */
    public SpnExpressionNode tryOperatorDispatch(String op,
                                                  SpnExpressionNode left,
                                                  SpnExpressionNode right) {
        List<SpnParser.OperatorOverload> overloads = operatorRegistry.get(op);
        if (overloads == null || overloads.isEmpty()) return null;

        FieldType leftType = inferType(left);
        FieldType rightType = inferType(right);
        if (leftType == null || rightType == null) return null;

        // 1. Exact match (cost 0)
        for (SpnParser.OperatorOverload ov : overloads) {
            if (ov.paramTypes().length == 2
                    && typesMatch(ov.paramTypes()[0], leftType)
                    && typesMatch(ov.paramTypes()[1], rightType)) {
                SpnExpressionNode result = new SpnInvokeNode(ov.callTarget(), left, right);
                trackType(result, ov.returnType());
                recordDispatch(result, op,
                        op + "(" + leftType.describe() + ", " + rightType.describe() + ")",
                        0, ov.callTarget());
                return result;
            }
        }

        // 2. Both primitives → built-in handles it
        if (isPrimitive(leftType) && isPrimitive(rightType)) return null;

        // 3. Walk promotion trees, find minimum-cost match
        List<PromotionStep> leftSteps = buildPromotionChain(leftType);
        List<PromotionStep> rightSteps = buildPromotionChain(rightType);

        int bestCost = Integer.MAX_VALUE;
        PromotionStep bestLeft = null, bestRight = null;
        SpnParser.OperatorOverload bestOverload = null;

        for (PromotionStep ls : leftSteps) {
            if (ls.depth >= bestCost) continue;
            for (PromotionStep rs : rightSteps) {
                int cost = ls.depth + rs.depth;
                if (cost == 0 || cost >= bestCost) continue;
                for (SpnParser.OperatorOverload ov : overloads) {
                    if (ov.paramTypes().length == 2
                            && ov.paramTypes()[0].describe().equals(ls.typeDesc)
                            && ov.paramTypes()[1].describe().equals(rs.typeDesc)) {
                        bestCost = cost;
                        bestLeft = ls;
                        bestRight = rs;
                        bestOverload = ov;
                        break;
                    }
                }
            }
        }

        if (bestOverload == null) return null;

        SpnExpressionNode promotedLeft = applyPromotionChain(left, bestLeft);
        SpnExpressionNode promotedRight = applyPromotionChain(right, bestRight);
        SpnExpressionNode result = new SpnInvokeNode(bestOverload.callTarget(), promotedLeft, promotedRight);
        trackType(result, bestOverload.returnType());
        recordDispatch(result, op,
                op + "(" + bestLeft.typeDesc + ", " + bestRight.typeDesc + ")",
                bestCost, bestOverload.callTarget());
        return result;
    }

    /**
     * Try unary operator dispatch: {@code -(T) -> T} for negation, then
     * {@code .neg()} method fallback. Returns null if operand is primitive
     * (caller should use the built-in negate node).
     */
    public SpnExpressionNode tryUnaryDispatch(String op, SpnExpressionNode operand) {
        FieldType operandType = inferType(operand);
        if (operandType == null || !isDefinitelyNonPrimitive(operandType)) return null;

        // 1. Unary operator overload
        List<SpnParser.OperatorOverload> overloads = operatorRegistry.get(op);
        if (overloads != null) {
            for (SpnParser.OperatorOverload ov : overloads) {
                if (ov.paramTypes().length == 1 && typesMatch(ov.paramTypes()[0], operandType)) {
                    SpnExpressionNode result = new SpnInvokeNode(ov.callTarget(), operand);
                    trackType(result, ov.returnType());
                    recordDispatch(result, op,
                            op + "(" + operandType.describe() + ")",
                            0, ov.callTarget());
                    return result;
                }
            }
        }

        // 2. .neg() / .inv() method fallback
        String methodName = op.equals("-") ? "neg" : "inv";
        SpnParser.MethodEntry method = resolveMethod(operandType, methodName);
        if (method != null) {
            SpnExpressionNode result = new spn.node.func.SpnMethodInvokeNode(
                    method.callTarget(), operand, new SpnExpressionNode[0]);
            if (method.descriptor() != null && method.descriptor().hasTypedReturn()) {
                trackType(result, method.descriptor().getReturnType());
            }
            recordDispatch(result, op,
                    operandType.describe() + "." + methodName + "()",
                    0, method.callTarget());
            return result;
        }

        return null; // no dispatch found — caller will error or use primitive
    }

    /**
     * Try multiplicative inverse: when the left operand of {@code /} is the
     * literal 1, look for a unary {@code /(T) -> T} or {@code .inv()} method.
     */
    public SpnExpressionNode tryUnaryInverse(SpnExpressionNode left, SpnExpressionNode right) {
        if (!(left instanceof spn.node.expr.SpnLongLiteralNode lit)) return null;
        try { if (lit.executeLong(null) != 1L) return null; }
        catch (Exception e) { return null; }
        return tryUnaryDispatch("/", right);
    }

    // ── Method resolution ───────────────────────────────────────────────────

    /** Resolve a method by receiver type and name. */
    public SpnParser.MethodEntry resolveMethod(FieldType receiverType, String methodName) {
        if (receiverType == null) return null;

        // Union types: method must exist on ALL variants
        if (receiverType instanceof FieldType.OfVariant ov) {
            SpnParser.MethodEntry first = null;
            for (SpnStructDescriptor variant : ov.variantSet().getVariants()) {
                SpnParser.MethodEntry entry = methodRegistry.get(variant.getName() + "." + methodName);
                if (entry == null) return null;
                if (first == null) first = entry;
            }
            return first;
        }

        // Try exact type description match
        String key = receiverType.describe() + "." + methodName;
        SpnParser.MethodEntry entry = methodRegistry.get(key);
        if (entry != null) return entry;
        // Try the type's simple name
        String typeName = resolveTypeName(receiverType);
        if (typeName != null) {
            entry = methodRegistry.get(typeName + "." + methodName);
            if (entry != null) return entry;
        }
        return null;
    }

    // ── Field resolution ────────────────────────────────────────────────────

    /** Resolve a named field to its index on the receiver's struct descriptor. */
    public int resolveFieldIndex(FieldType receiverType, String fieldName) {
        SpnStructDescriptor sd = resolveStructDescriptor(receiverType);
        if (sd != null) {
            int idx = sd.fieldIndex(fieldName);
            if (idx >= 0) return idx;
        }
        return -1;
    }

    /** Track the type of a field access from the receiver's descriptor. */
    public FieldType resolveFieldType(FieldType receiverType, String fieldName) {
        SpnStructDescriptor sd = resolveStructDescriptor(receiverType);
        if (sd != null) {
            int idx = sd.fieldIndex(fieldName);
            if (idx >= 0) return sd.fieldType(idx);
        }
        return null;
    }

    // ── Promotion ───────────────────────────────────────────────────────────

    /** Apply implicit promotions to function arguments at call sites. */
    public void promoteArgs(List<SpnExpressionNode> args, String funcName) {
        SpnFunctionDescriptor desc = functionDescriptorRegistry.get(funcName);
        if (desc == null) return;
        FieldDescriptor[] params = desc.getParams();
        for (int i = 0; i < args.size() && i < params.length; i++) {
            FieldType argType = inferType(args.get(i));
            FieldType paramType = params[i].type();
            if (argType == null || paramType == null) continue;
            if (paramType instanceof FieldType.Untyped) continue;
            if (typesMatch(paramType, argType)) continue;

            List<PromotionStep> steps = buildPromotionChain(argType);
            for (PromotionStep step : steps) {
                if (step.typeDesc.equals(paramType.describe())) {
                    args.set(i, applyPromotionChain(args.get(i), step));
                    trackType(args.get(i), paramType);
                    break;
                }
            }
        }
    }

    // ── Type unification ────────────────────────────────────────────────────

    /** Unify two branch types into a single result type (or union). */
    public FieldType unifyTypes(FieldType a, FieldType b) {
        if (a == null) return b;
        if (b == null) return a;
        if (typesMatch(a, b)) return a;

        List<SpnStructDescriptor> variants = new ArrayList<>();
        try {
            collectUnionMembers(variants, a);
            collectUnionMembers(variants, b);
        } catch (Exception e) {
            return null;
        }

        variants.sort(Comparator.comparing(SpnStructDescriptor::getName));
        for (int i = variants.size() - 1; i > 0; i--) {
            if (variants.get(i) == variants.get(i - 1)) variants.remove(i);
        }

        if (variants.size() == 1) return FieldType.ofStruct(variants.get(0));
        String name = variants.stream()
                .map(SpnStructDescriptor::getName)
                .collect(java.util.stream.Collectors.joining(" | "));
        return FieldType.ofVariant(new SpnVariantSet(name,
                variants.toArray(new SpnStructDescriptor[0])));
    }

    // ── Type comparison ─────────────────────────────────────────────────────

    public boolean typesMatch(FieldType declared, FieldType inferred) {
        if (declared == null || inferred == null) return false;
        if (declared == inferred) return true;
        if (declared.describe().equals(inferred.describe())) return true;

        // Union assignability
        if (declared instanceof FieldType.OfVariant declaredUnion) {
            SpnStructDescriptor[] declaredVariants = declaredUnion.variantSet().getVariants();
            if (inferred instanceof FieldType.OfStruct inferredStruct) {
                for (SpnStructDescriptor v : declaredVariants) {
                    if (v == inferredStruct.descriptor()) return true;
                }
            }
            if (inferred instanceof FieldType.OfVariant inferredUnion) {
                outer:
                for (SpnStructDescriptor iv : inferredUnion.variantSet().getVariants()) {
                    for (SpnStructDescriptor dv : declaredVariants) {
                        if (iv == dv) continue outer;
                    }
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    public boolean isPrimitive(FieldType type) {
        return type == FieldType.LONG || type == FieldType.DOUBLE
                || type == FieldType.BOOLEAN || type == FieldType.STRING;
    }

    public boolean isDefinitelyNonPrimitive(FieldType t) {
        return t instanceof FieldType.OfStruct
            || t instanceof FieldType.OfProduct
            || t instanceof FieldType.OfVariant
            || t instanceof FieldType.OfTuple
            || t instanceof FieldType.OfArray
            || t instanceof FieldType.OfSet
            || t instanceof FieldType.OfDictionary
            || t instanceof FieldType.OfConstrainedType
            || t instanceof FieldType.OfFunction;
    }

    // ── Pattern-category validation ────────────────────────────────────────

    /** Returns a mismatch description, or null if compatible. */
    public String patternCategoryMismatch(spn.node.match.MatchPattern pat, FieldType subjectType) {
        if (subjectType == null) return null;
        if (subjectType instanceof FieldType.Untyped) return null;

        return switch (pat) {
            case spn.node.match.MatchPattern.Wildcard _ -> null;
            case spn.node.match.MatchPattern.Capture _ -> null;
            case spn.node.match.MatchPattern.OfType _ -> null;
            case spn.node.match.MatchPattern.TupleElements _ -> (
                    subjectType instanceof FieldType.OfTuple || subjectType instanceof FieldType.OfArray
            ) ? null : "tuple pattern '(...)' cannot destructure " + subjectType.describe();
            case spn.node.match.MatchPattern.Tuple _ -> (
                    subjectType instanceof FieldType.OfTuple
            ) ? null : "tuple pattern cannot match " + subjectType.describe();
            case spn.node.match.MatchPattern.Struct s -> structPatternMismatch(s.descriptor(), subjectType);
            case spn.node.match.MatchPattern.StructDestructure s -> structPatternMismatch(s.descriptor(), subjectType);
            case spn.node.match.MatchPattern.Product _ -> (
                    subjectType instanceof FieldType.OfProduct || subjectType instanceof FieldType.OfConstrainedType
            ) ? null : "product pattern cannot match " + subjectType.describe();
            case spn.node.match.MatchPattern.EmptyArray _,
                 spn.node.match.MatchPattern.ArrayHeadTail _,
                 spn.node.match.MatchPattern.ArrayExactLength _ -> (
                    subjectType instanceof FieldType.OfArray
            ) ? null : "array pattern cannot match " + subjectType.describe();
            case spn.node.match.MatchPattern.StringPrefix _,
                 spn.node.match.MatchPattern.StringSuffix _,
                 spn.node.match.MatchPattern.StringRegex _ -> (
                    subjectType == FieldType.STRING
            ) ? null : "string pattern cannot match " + subjectType.describe();
            case spn.node.match.MatchPattern.EmptySet _,
                 spn.node.match.MatchPattern.SetContaining _ -> (
                    subjectType instanceof FieldType.OfSet
            ) ? null : "set pattern cannot match " + subjectType.describe();
            case spn.node.match.MatchPattern.EmptyDictionary _,
                 spn.node.match.MatchPattern.DictionaryKeys _ -> (
                    subjectType instanceof FieldType.OfDictionary
            ) ? null : "dictionary pattern cannot match " + subjectType.describe();
            case spn.node.match.MatchPattern.Literal lit -> {
                Object v = lit.expected();
                FieldType litType = null;
                if (v instanceof Long) litType = FieldType.LONG;
                else if (v instanceof Double) litType = FieldType.DOUBLE;
                else if (v instanceof String) litType = FieldType.STRING;
                else if (v instanceof Boolean) litType = FieldType.BOOLEAN;
                else if (v instanceof SpnSymbol) litType = FieldType.SYMBOL;
                if (litType == null) yield null;
                yield typesMatch(subjectType, litType) ? null
                        : "literal of type " + litType.describe() + " cannot match " + subjectType.describe();
            }
        };
    }

    private String structPatternMismatch(SpnStructDescriptor patternDesc, FieldType subjectType) {
        if (subjectType instanceof FieldType.OfStruct os) {
            if (os.descriptor() == patternDesc) return null;
            return "struct pattern '" + patternDesc.getName() + "(...)' cannot match " + subjectType.describe();
        }
        if (subjectType instanceof FieldType.OfVariant ov) {
            for (SpnStructDescriptor v : ov.variantSet().getVariants()) {
                if (v == patternDesc) return null;
            }
            return "struct pattern '" + patternDesc.getName()
                    + "(...)' is not a variant of " + ov.variantSet().getName();
        }
        if (subjectType instanceof FieldType.OfConstrainedType oct) {
            SpnStructDescriptor resolved = structRegistry.get(oct.descriptor().getName());
            if (resolved == patternDesc) return null;
        }
        return "struct pattern '" + patternDesc.getName() + "(...)' cannot match " + subjectType.describe();
    }

    // ── IDE query ───────────────────────────────────────────────────────────

    /** Returns the dispatch record for an expression, or null if it wasn't dispatched. */
    public DispatchRecord dispatchAt(SpnExpressionNode expr) {
        return dispatches.get(expr);
    }

    /** All dispatch records in the current compilation. */
    public Map<SpnExpressionNode, DispatchRecord> allDispatches() {
        return Collections.unmodifiableMap(dispatches);
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private void recordDispatch(SpnExpressionNode expr, String op, String resolved,
                                int promotions, CallTarget target) {
        dispatches.put(expr, new DispatchRecord(op, resolved, promotions, target));
    }

    private record PromotionStep(String typeDesc, int depth, List<CallTarget> converters) {}

    private List<PromotionStep> buildPromotionChain(FieldType startType) {
        List<PromotionStep> steps = new ArrayList<>();
        steps.add(new PromotionStep(startType.describe(), 0, List.of()));

        Set<String> visited = new HashSet<>();
        visited.add(startType.describe());
        Deque<PromotionStep> queue = new ArrayDeque<>();
        queue.add(steps.getFirst());

        while (!queue.isEmpty()) {
            PromotionStep current = queue.poll();
            for (SpnParser.Promotion promo : promotionRegistry) {
                if (promo.sourceDesc().equals(current.typeDesc) && visited.add(promo.targetDesc())) {
                    List<CallTarget> newConverters = new ArrayList<>(current.converters);
                    newConverters.add(promo.converter());
                    PromotionStep next = new PromotionStep(
                            promo.targetDesc(), current.depth + 1, List.copyOf(newConverters));
                    steps.add(next);
                    queue.add(next);
                }
            }
        }
        return steps;
    }

    private SpnExpressionNode applyPromotionChain(SpnExpressionNode expr, PromotionStep step) {
        SpnExpressionNode current = expr;
        for (CallTarget converter : step.converters) {
            current = new SpnInvokeNode(converter, current);
        }
        return current;
    }

    SpnStructDescriptor resolveStructDescriptor(FieldType type) {
        if (type instanceof FieldType.OfStruct os) return os.descriptor();
        if (type instanceof FieldType.OfConstrainedType oct && oct.descriptor().isProduct())
            return structRegistry.get(oct.descriptor().getName());
        if (type instanceof FieldType.OfProduct op)
            return structRegistry.get(op.descriptor().getName());
        return null;
    }

    String resolveTypeName(FieldType type) {
        if (type instanceof FieldType.OfStruct os) return os.descriptor().getName();
        if (type instanceof FieldType.OfConstrainedType oct) return oct.descriptor().getName();
        if (type instanceof FieldType.OfProduct op) return op.descriptor().getName();
        return null;
    }

    private void collectUnionMembers(List<SpnStructDescriptor> variants, FieldType type) {
        if (type instanceof FieldType.OfStruct os) {
            variants.add(os.descriptor());
        } else if (type instanceof FieldType.OfVariant ov) {
            Collections.addAll(variants, ov.variantSet().getVariants());
        } else if (type instanceof FieldType.OfConstrainedType oct) {
            SpnStructDescriptor sd = structRegistry.get(oct.descriptor().getName());
            if (sd != null) { variants.add(sd); return; }
            throw new IllegalArgumentException("Cannot use type '" + oct.descriptor().getName() + "' in union");
        } else if (type instanceof FieldType.OfProduct op) {
            SpnStructDescriptor sd = structRegistry.get(op.descriptor().getName());
            if (sd != null) { variants.add(sd); return; }
            throw new IllegalArgumentException("Cannot use type '" + op.descriptor().getName() + "' in union");
        } else {
            throw new IllegalArgumentException("Union types can only combine struct/data types, got: " + type.describe());
        }
    }
}
