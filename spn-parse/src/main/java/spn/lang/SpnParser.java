package spn.lang;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import spn.language.ImportDirective;
import spn.language.MethodEntry;
import spn.language.SpnLanguage;
import spn.language.SpnModule;
import spn.language.SpnModuleRegistry;
import spn.node.SpnExpressionNode;
import spn.node.SpnRootNode;
import spn.node.SpnStatementNode;
import spn.node.array.SpnArrayAccessNodeGen;
import spn.node.array.SpnArrayLiteralNode;
import spn.node.ctrl.SpnWhileNode;
import spn.node.dict.SpnDictionaryLiteralNode;
import spn.node.expr.*;
import spn.node.func.SpnFunctionRefNode;
import spn.node.func.SpnFunctionRootNode;
import spn.node.func.SpnIndirectInvokeNode;
import spn.node.func.SpnInvokeNode;
import spn.node.lambda.SpnLambdaNode;
import spn.node.lambda.SpnStreamBlockNode;
import spn.node.lambda.SpnYieldNode;
import spn.node.local.SpnReadLocalVariableNodeGen;
import spn.node.local.SpnWriteLocalVariableNodeGen;
import spn.node.match.MatchPattern;
import spn.node.match.SpnMatchBranchNode;
import spn.node.match.SpnMatchNode;
import spn.node.struct.SpnStructConstructNode;
import spn.type.*;

import java.util.*;

/**
 * Recursive descent parser for SPN source code. Produces Truffle AST nodes.
 *
 * The parser manages scope (frame slot allocation), type/struct/function
 * registries, and a symbol table for interning.
 */
public class SpnParser {

    private final SpnTokenizer tokens;

    /** Pattern-parsing is delegated to this helper (created in the constructor). */
    private final PatternParser patternParser;

    /** Type-expression parsing is delegated to this helper (created in the constructor). */
    private final TypeParser typeParser;

    /** Compile-time type resolver — operator dispatch, promotion, method/field resolution. */
    private final TypeResolver resolver;

    /** Unified declaration graph — populated alongside the old registries. */
    private final TypeGraph typeGraph = new TypeGraph();
    private final SpnLanguage language;
    private final SpnSymbolTable symbolTable;

    // Type registries (populated during parsing)
    private final Map<String, SpnTypeDescriptor> typeRegistry = new LinkedHashMap<>();
    private final Map<String, SpnStructDescriptor> structRegistry = new LinkedHashMap<>();
    private final Map<String, SpnVariantSet> variantRegistry = new LinkedHashMap<>();
    private final Map<String, CallTarget> functionRegistry = new LinkedHashMap<>();
    private final Map<String, SpnFunctionDescriptor> functionDescriptorRegistry = new LinkedHashMap<>();
    private final Map<String, spn.node.BuiltinFactory> builtinRegistry = new LinkedHashMap<>();
    private final Set<String> impureBuiltins = new HashSet<>();

    // Method registry: "TypeName.methodName" → (CallTarget, SpnFunctionDescriptor).
    // MethodEntry lives in spn.language so stdlib's generated module loader can
    // construct registrations without depending on spn-parse.
    private final Map<String, MethodEntry> methodRegistry = new LinkedHashMap<>();

    // Method factory registry: "TypeName.methodName" → BuiltinFactory. Used for
    // higher-order methods like arr.map(fn) where the function argument must
    // be baked into a fresh CallTarget per call site (same pattern as flat
    // higher-order builtins). Populated from stdlib modules' "methodFactories"
    // extras. Checked as a fallback when methodRegistry has no entry.
    private final Map<String, spn.node.BuiltinFactory> methodFactories = new LinkedHashMap<>();

    // Return-type descriptors paired with methodFactories entries — lets the
    // parser track the method result's type so chained calls like
    // arr.map(fn).length() can dispatch. Same key format.
    private final Map<String, SpnFunctionDescriptor> methodFactoryDescriptors = new LinkedHashMap<>();

    // Factory registry: "TypeName" → list of (CallTarget, arity) for overloaded factories
    record FactoryEntry(CallTarget callTarget, int arity, SpnFunctionDescriptor descriptor) {}
    private final Map<String, List<FactoryEntry>> factoryRegistry = new LinkedHashMap<>();

    // Macro registry: "Name" → MacroDef (compile-time code template)
    record MacroParam(String name, String requiredSignature) {}
    record MacroDef(String name, List<MacroParam> params, List<SpnParseToken> bodyTokens) {
        /** Convenience accessor: just the names. */
        public List<String> paramNames() {
            return params.stream().map(MacroParam::name).toList();
        }
    }

    // What kind of declaration an `emit TypeName` statement exports from the
    // macro body. Today only TYPE is supported; FUNCTION and VALUE are
    // reserved for future stages (emit fn, emit const, etc.).
    enum EmittedKind { TYPE }
    record EmittedEntry(EmittedKind kind, String internalName) {}

    // Macro expansion state
    private boolean insideMacroExpansion = false;
    // Names emitted by the currently-expanding macro. Cleared at the start of
    // each expansion. Typical macros emit exactly one entry — `renameMacroEmittedType`
    // rejects multi-emit when the caller uses `type X = macro<...>`.
    private final LinkedHashMap<String, EmittedEntry> macroEmittedBundle = new LinkedHashMap<>();
    private static final String MACRO_END_SENTINEL = "__MACRO_END__";
    private int macroExpansionCounter = 0; // unique suffix for internal type names
    private final Map<String, MacroDef> macroRegistry = new LinkedHashMap<>();
    // Memoization: same (macroName, arg-token-text) → same emitted internal type
    // name. Gives `Array<int>` identity across multiple invocations in the same
    // file. Keyed on the raw text of arg tokens, so semantic aliases
    // (`type Rat = Rational` used in `Array<Rat>` vs `Array<Rational>`) are
    // distinct entries — users collapse via the canonical name.
    // Cross-file singleton identity is a followup (lives in the module registry).
    private final Map<String, String> macroMemo = new LinkedHashMap<>();

    // Module namespace claimed by a `module com.foo.bar` declaration at the
    // top of the file. Null if no module was declared. Used to enforce
    // ownership on `register @fqn` — a file may only register keys under
    // a namespace it claims.
    private String currentModuleNamespace = null;

    // Globally-unique qualified-key registrations. `register pure @a.b.name
    // (params) -> ret` puts an entry here keyed by the FQN text (with the @).
    // Implementations reference the key; no impl is required to register it
    // (v1 convenience), but when registered, its existence is an interface
    // contract that signatures can refer to.
    private final Map<String, SpnFunctionDescriptor> qualifiedKeyRegistry = new LinkedHashMap<>();

    // Named signatures: `signature Name (@key, OtherSig, ...)` stores a
    // flattened set of required dispatch-key names (text, with @). Sub-signature
    // references are expanded at parse time; the registry always holds the
    // transitive closure of required keys.
    private final Map<String, Set<String>> signatureRegistry = new LinkedHashMap<>();

    // Qualified-key aliases: `import com.myapp.(serialize)` binds the short
    // name `serialize` to the full key `@com.myapp.serialize`. Resolved at
    // method-call sites when a plain method lookup misses.
    private final Map<String, String> qualifiedKeyAliases = new LinkedHashMap<>();

    // Associated constants: "TypeName.name" → zero-arg CallTarget returning the value
    record ConstantEntry(CallTarget callTarget, FieldType type) {}
    private final Map<String, ConstantEntry> constantRegistry = new LinkedHashMap<>();
    // Legacy frame-slot constants (from 'let TypeName.name = expr') — kept for backward compat
    private final Map<String, Integer> typeConstantSlots = new LinkedHashMap<>();
    private final Map<String, FieldType> typeConstantTypes = new LinkedHashMap<>();

    // Operator overload registry: operator → list of (paramTypes, callTarget) pairs
    record OperatorOverload(FieldType[] paramTypes, FieldType returnType, CallTarget callTarget) {}
    private final Map<String, List<OperatorOverload>> operatorRegistry = new LinkedHashMap<>();

    // Function overload registry: for functions with multiple type-dispatched overloads.
    // A function enters this registry when a second definition with the same name is
    // encountered (the first definition stays in functionRegistry for backward compat).
    private final Map<String, List<OperatorOverload>> functionOverloads = new LinkedHashMap<>();

    // Promotion registry: sourceType → (targetType, conversionCallTarget)
    record Promotion(String sourceDesc, String targetDesc, CallTarget converter) {}
    private final List<Promotion> promotionRegistry = new ArrayList<>();


    // Module system
    private final SpnModuleRegistry moduleRegistry;
    private final Map<String, SpnModule> qualifiedModules = new LinkedHashMap<>();

    // Current scope for frame slot management
    private Scope currentScope;

    private final String sourceName; // file name for error messages

    public SpnParser(String source, SpnLanguage language, SpnSymbolTable symbolTable) {
        this(source, null, language, symbolTable, null);
    }

    public SpnParser(String source, SpnLanguage language, SpnSymbolTable symbolTable,
                     SpnModuleRegistry moduleRegistry) {
        this(source, null, language, symbolTable, moduleRegistry);
    }

    public SpnParser(String source, String sourceName, SpnLanguage language,
                     SpnSymbolTable symbolTable, SpnModuleRegistry moduleRegistry) {
        this.tokens = new SpnTokenizer(source);
        this.sourceName = sourceName;
        this.language = language;
        this.symbolTable = symbolTable;
        this.moduleRegistry = moduleRegistry;
        if (sourceName != null) {
            this.tokens.setSourceName(sourceName);
        }
        // Pattern parser holds tokens, struct registry, symbol table, and an
        // adapter that routes addLocal() through the currently active Scope.
        this.patternParser = new PatternParser(
                tokens, structRegistry, symbolTable,
                new PatternParser.ScopeProvider() {
                    @Override public int addLocal(String name) {
                        return currentScope.addLocal(name);
                    }
                    @Override public int addLocal(String name, FieldType expected) {
                        return currentScope.addLocal(name, expected);
                    }
                });
        // Type parser holds tokens plus the four type-lookup registries.
        // The recorder callback captures type-reference use sites so the IDE
        // can offer go-to-definition on type names in signatures.
        // macroRegistry.keySet() is a live view: as macros get added during
        // parsing, the set reflects them immediately, so `MacroName<Args>`
        // in a later type position resolves correctly.
        this.typeParser = new TypeParser(
                tokens, structRegistry, typeRegistry, variantRegistry,
                functionDescriptorRegistry,
                this::recordTypeReference,
                macroRegistry.keySet(),
                this::expandMacroAsType);
        // Type resolver handles compile-time dispatch decisions.
        this.resolver = new TypeResolver(
                operatorRegistry, promotionRegistry, methodRegistry,
                structRegistry, typeRegistry, variantRegistry,
                functionDescriptorRegistry);
    }

    /** The compile-time resolver, exposed for the diagnostic engine and GUI. */
    public TypeResolver getResolver() { return resolver; }
    public TypeGraph getTypeGraph() { return typeGraph; }

    public Map<String, spn.node.BuiltinFactory> getBuiltinRegistry() {
        return builtinRegistry;
    }

    // ── Scope management ───────────────────────────────────────────────────

    private static class Scope {
        final FrameDescriptor.Builder frameBuilder;
        final Map<String, Integer> locals = new LinkedHashMap<>();
        final Map<String, FieldType> localTypes = new LinkedHashMap<>();
        final Scope parent;

        Scope(Scope parent) {
            this.parent = parent;
            this.frameBuilder = FrameDescriptor.newBuilder();
        }

        int addLocal(String name) {
            int slot = frameBuilder.addSlot(FrameSlotKind.Object, name, null);
            locals.put(name, slot);
            return slot;
        }

        int addLocal(String name, FieldType type) {
            int slot = addLocal(name);
            if (type != null) localTypes.put(name, type);
            return slot;
        }

        int lookupLocal(String name) {
            Integer slot = locals.get(name);
            if (slot != null) return slot;
            if (parent != null) return parent.lookupLocal(name);
            return -1;
        }

        /** Like lookupLocal but doesn't walk parents — used to detect outer-scope
         *  references from inside a do() closure body, which can't legally capture them. */
        int lookupLocalImmediate(String name) {
            Integer slot = locals.get(name);
            return slot != null ? slot : -1;
        }

        FieldType lookupType(String name) {
            FieldType type = localTypes.get(name);
            if (type != null) return type;
            if (parent != null) return parent.lookupType(name);
            return null;
        }

        void setType(String name, FieldType type) {
            localTypes.put(name, type);
        }

        FrameDescriptor buildFrame() {
            return frameBuilder.build();
        }
    }

    // ── Error recovery ──────────────────────────────────────────────────────

    /** Keywords that start a new top-level declaration — safe to resume after. */
    private static final Set<String> SYNC_KEYWORDS = Set.of(
            "type", "data", "struct", "pure", "action", "let", "const",
            "import", "module", "version", "require", "macro", "promote",
            "register", "signature"
    );

    /**
     * Skip tokens until we reach a declaration-boundary keyword. This puts the
     * parser at a point where {@link #parseTopLevel()} can resume cleanly.
     * Skips at most to EOF.
     */
    private void synchronize() {
        while (tokens.hasMore()) {
            SpnParseToken tok = tokens.peek();
            if (tok == null) break;
            // A keyword that starts a declaration is a safe resume point
            if (tok.type() == TokenType.KEYWORD && SYNC_KEYWORDS.contains(tok.text())) {
                return; // don't consume — let parseTopLevel() handle it
            }
            // A TYPE_NAME at the start of a line might be a macro invocation
            // or constructor — also a safe resume point
            if (tok.type() == TokenType.TYPE_NAME) {
                SpnParseToken prev = tokens.lastConsumed();
                if (prev == null || tok.line() > prev.line()) {
                    return;
                }
            }
            tokens.advance(); // skip this token
        }
    }

    private void pushScope() {
        currentScope = new Scope(currentScope);
    }

    private Scope popScope() {
        Scope scope = currentScope;
        currentScope = currentScope.parent;
        return scope;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Errors collected during recovery-mode parsing. */
    private final List<SpnParseException> collectedErrors = new ArrayList<>();

    /** If true, collect errors instead of throwing (recovery mode). */
    private boolean recoveryMode = false;

    /** Returns all errors collected during parsing (empty if no errors). */
    public List<SpnParseException> getErrors() {
        return collectedErrors;
    }

    /**
     * Parses the full source into an SpnRootNode ready for execution.
     * Type/struct/function declarations are registered; top-level statements
     * are collected into a block.
     *
     * <p>Error recovery: when a declaration fails to parse, the error is
     * recorded and the parser skips to the next declaration boundary keyword.
     * This allows multiple errors to be reported from a single parse, and
     * lets the IDE show diagnostics for the whole file, not just the first
     * broken line. After recovery, the parser continues with clean state for
     * the next declaration.
     */
    public SpnRootNode parse() {
        recoveryMode = true;
        pushScope();
        List<SpnStatementNode> statements = new ArrayList<>();

        while (tokens.hasMore()) {
            try {
                SpnStatementNode stmt = parseTopLevel();
                if (stmt != null) {
                    statements.add(stmt);
                }
            } catch (SpnParseException e) {
                collectedErrors.add(e);
                synchronize();
            }
        }

        SpnExpressionNode body;
        if (statements.isEmpty()) {
            body = new SpnLongLiteralNode(0);
        } else if (statements.size() == 1 && statements.getFirst() instanceof SpnExpressionNode expr) {
            body = expr;
        } else {
            body = new SpnBlockExprNode(statements.toArray(new SpnStatementNode[0]));
        }

        Scope scope = popScope();
        SpnRootNode root = new SpnRootNode(language, scope.buildFrame(), body, "<main>");

        // After recovery, throw the first error so callers that expect execution
        // still get a failure. All errors remain accessible via getErrors().
        if (!collectedErrors.isEmpty()) {
            throw collectedErrors.getFirst();
        }
        return root;
    }

    /** Accessors for registries (used by tests and the module system). */
    public Map<String, SpnTypeDescriptor> getTypeRegistry() { return typeRegistry; }
    public Map<String, SpnStructDescriptor> getStructRegistry() { return structRegistry; }
    public Map<String, SpnVariantSet> getVariantRegistry() { return variantRegistry; }
    public Map<String, CallTarget> getFunctionRegistry() { return functionRegistry; }
    public Map<String, SpnFunctionDescriptor> getFunctionDescriptorRegistry() { return functionDescriptorRegistry; }
    public Map<String, MethodEntry> getMethodRegistry() { return methodRegistry; }
    public Map<String, List<FactoryEntry>> getFactoryRegistry() { return factoryRegistry; }
    public Map<String, List<OperatorOverload>> getOperatorRegistry() { return operatorRegistry; }
    public Map<String, Integer> getTypeConstantSlots() { return typeConstantSlots; }
    public Map<String, FieldType> getTypeConstantTypes() { return typeConstantTypes; }
    public Map<String, ConstantEntry> getConstantRegistry() { return constantRegistry; }
    public Map<String, MacroDef> getMacroRegistry() { return macroRegistry; }
    public Map<String, Set<String>> getSignatureRegistry() { return signatureRegistry; }
    public Map<String, SpnFunctionDescriptor> getQualifiedKeyRegistry() { return qualifiedKeyRegistry; }
    public List<Promotion> getPromotionRegistry() { return promotionRegistry; }

    /** Build a map of every exported type/struct/variant declaration in this
     *  parse to its source position. Used by module loaders so cross-module
     *  go-to-definition can jump to the original declaration, not just the
     *  import statement that brought it in. */
    public Map<String, spn.language.TypeDeclPos> buildTypeDeclarations() {
        Map<String, spn.language.TypeDeclPos> result = new LinkedHashMap<>();
        for (TypeGraph.Kind k : new TypeGraph.Kind[]{
                TypeGraph.Kind.TYPE, TypeGraph.Kind.STRUCT, TypeGraph.Kind.VARIANT}) {
            for (TypeGraph.Node n : typeGraph.byKind(k)) {
                if (n.file() == null || !n.nameRange().isKnown()) continue;
                result.putIfAbsent(n.name(),
                        new spn.language.TypeDeclPos(n.file(), n.nameRange()));
            }
        }
        return result;
    }

    /** Map each locally-declared factory's CallTarget to its source position.
     *  Factories are overloaded (same type name, different signatures), so the
     *  CallTarget identity is the disambiguator an importing parser uses to
     *  pick the right declaration for a given call-site resolution. */
    public java.util.IdentityHashMap<CallTarget, spn.language.TypeDeclPos>
            buildFactoryDeclarations() {
        java.util.IdentityHashMap<CallTarget, spn.language.TypeDeclPos> result =
                new java.util.IdentityHashMap<>();
        for (List<FactoryEntry> entries : factoryRegistry.values()) {
            for (FactoryEntry fe : entries) {
                if (fe.callTarget() == null) continue;
                TypeGraph.Node n = typeGraph.byCallTarget(fe.callTarget());
                if (n == null || n.file() == null || !n.nameRange().isKnown()) continue;
                result.putIfAbsent(fe.callTarget(),
                        new spn.language.TypeDeclPos(n.file(), n.nameRange()));
            }
        }
        return result;
    }

    /** Map each locally-declared constant's composite name
     *  ("TypeName.constName") to its source position. */
    public Map<String, spn.language.TypeDeclPos> buildConstantDeclarations() {
        Map<String, spn.language.TypeDeclPos> result = new LinkedHashMap<>();
        for (TypeGraph.Node n : typeGraph.byKind(TypeGraph.Kind.CONSTANT)) {
            if (n.file() == null || !n.nameRange().isKnown()) continue;
            result.putIfAbsent(n.name(),
                    new spn.language.TypeDeclPos(n.file(), n.nameRange()));
        }
        return result;
    }

    /** Map each locally-declared method's CallTarget to its source position.
     *  Methods are overloaded across receiver types, so CallTarget identity is
     *  the disambiguator. */
    public java.util.IdentityHashMap<CallTarget, spn.language.TypeDeclPos>
            buildMethodDeclarations() {
        java.util.IdentityHashMap<CallTarget, spn.language.TypeDeclPos> result =
                new java.util.IdentityHashMap<>();
        for (TypeGraph.Node n : typeGraph.byKind(TypeGraph.Kind.METHOD)) {
            if (n.callTarget() == null || n.file() == null || !n.nameRange().isKnown()) continue;
            result.putIfAbsent(n.callTarget(),
                    new spn.language.TypeDeclPos(n.file(), n.nameRange()));
        }
        return result;
    }

    /** Map each locally-declared field's composite name to its source position.
     *  Includes both named fields ("Point.x") and positional components
     *  ("Rational.0"). */
    public Map<String, spn.language.TypeDeclPos> buildFieldDeclarations() {
        Map<String, spn.language.TypeDeclPos> result = new LinkedHashMap<>();
        for (TypeGraph.Node n : typeGraph.byKind(TypeGraph.Kind.FIELD)) {
            if (n.file() == null || !n.nameRange().isKnown()) continue;
            result.putIfAbsent(n.name(),
                    new spn.language.TypeDeclPos(n.file(), n.nameRange()));
        }
        return result;
    }

    /** Map each locally-declared operator overload's CallTarget to its source
     *  position. Operators are overloaded across operand types, so CallTarget
     *  identity is the disambiguator. */
    public java.util.IdentityHashMap<CallTarget, spn.language.TypeDeclPos>
            buildOperatorDeclarations() {
        java.util.IdentityHashMap<CallTarget, spn.language.TypeDeclPos> result =
                new java.util.IdentityHashMap<>();
        for (TypeGraph.Node n : typeGraph.byKind(TypeGraph.Kind.OPERATOR)) {
            if (n.callTarget() == null || n.file() == null || !n.nameRange().isKnown()) continue;
            result.putIfAbsent(n.callTarget(),
                    new spn.language.TypeDeclPos(n.file(), n.nameRange()));
        }
        return result;
    }

    /** Look up the declaration position for an arbitrary resolved CallTarget,
     *  covering both same-module (via the TypeGraph's byCallTarget index) and
     *  cross-module (via the importedOperator/Method/Factory maps). Used by
     *  {@link IncrementalParser} to fill in dispatch-annotation targets. */
    public spn.language.TypeDeclPos lookupCallTargetDecl(CallTarget ct) {
        if (ct == null) return null;
        TypeGraph.Node n = typeGraph.byCallTarget(ct);
        if (n != null && n.file() != null && n.nameRange().isKnown()) {
            return new spn.language.TypeDeclPos(n.file(), n.nameRange());
        }
        spn.language.TypeDeclPos pos = importedOperatorDeclarations.get(ct);
        if (pos != null) return pos;
        pos = importedMethodDeclarations.get(ct);
        if (pos != null) return pos;
        pos = importedFactoryDeclarations.get(ct);
        return pos;
    }

    // ── Top-level parsing ──────────────────────────────────────────────────

    private SpnStatementNode parseTopLevel() {
        SpnParseToken tok = tokens.peek();
        if (tok == null) return null;

        // Macro invocation: Name<args> where Name is a registered macro.
        // Detect before the keyword switch so macro names can't collide with keywords.
        if (macroRegistry.containsKey(tok.text())) {
            SpnParseToken next = tokens.peek(1);
            if (next != null && next.text().equals("<")) {
                expandMacroInvocation();
                return null;
            }
        }

        return switch (tok.text()) {
            case "import" -> { parseImportDecl(); yield null; }
            case "module" -> { skipModuleDecl(); yield null; }
            case "version" -> { skipVersionDecl(); yield null; }
            case "require" -> { skipRequireDecl(); yield null; }
            case "type" -> { parseTypeDecl(); yield null; }
            case "stateful" -> { parseStatefulTypeDecl(); yield null; }
            case "data" -> { parseDataDecl(); yield null; }
            case "struct" -> { parseStructAsType(); yield null; }
            case "pure" -> parseFuncDecl(true);
            case "action" -> parseFuncDecl(false);
            case "promote" -> { parsePromoteDecl(); yield null; }
            case "let" -> parseLetBinding();
            case "const" -> { parseConstDecl(); yield null; }
            case "while" -> parseWhileStatement();
            case "yield" -> parseYieldStatement();
            case "return" -> parseYieldStatement(); // return is syntactic sugar for yield
            case "macro" -> { parseMacroDecl(); yield null; }
            case "emit" -> { parseEmit(); yield null; }
            case "register" -> { parseRegisterDecl(); yield null; }
            case "signature" -> { parseSignatureDecl(); yield null; }
            default -> parseExpressionStatement();
        };
    }

    // ── Macros ─────────────────────────────────────────────────────────────

    /**
     * Parse: macro Name<P1, P2, ...> = { body }
     * The body is captured as a raw token sequence; parameters are substituted
     * textually at invocation time. Empty parameter lists are legal:
     * {@code macro Name<> = { ... }}.
     */
    private void parseMacroDecl() {
        tokens.expect("macro");
        SpnParseToken nameTok = tokens.advance();
        String name = nameTok.text();

        tokens.expect("<");
        List<MacroParam> params = new ArrayList<>();
        while (!tokens.check(">")) {
            String paramName = tokens.advance().text();
            String requiredSig = null;
            if (tokens.match("requires")) {
                SpnParseToken sigTok = tokens.advance();
                requiredSig = sigTok.text();
            }
            params.add(new MacroParam(paramName, requiredSig));
            tokens.match(",");
        }
        tokens.expect(">");

        tokens.expect("=");
        tokens.expect("{");

        // Capture body tokens until matching closing brace
        int start = tokens.mark();
        int depth = 1;
        while (tokens.hasMore() && depth > 0) {
            String t = tokens.peek().text();
            if (t.equals("{")) depth++;
            else if (t.equals("}")) {
                depth--;
                if (depth == 0) break;
            }
            tokens.advance();
        }
        int end = tokens.mark();
        tokens.expect("}");

        List<SpnParseToken> bodyTokens = tokens.slice(start, end);
        macroRegistry.put(name, new MacroDef(name, params, bodyTokens));
    }

    /**
     * Expand a macro invocation: Name(arg1, arg2, ...) at the current position.
     *
     * <p><b>Scoped expansion</b>: the macro body runs in a temporary scope.
     * Named declarations (type, function, method, constant) are LOCAL to the
     * macro and discarded after expansion — unless explicitly {@code emit}-ted.
     * Semantic declarations (operator overloads, promotions) register globally
     * and persist.
     *
     * <p>Existing macros (deriveOrderingFromInt) only emit semantic declarations,
     * so they work unchanged — nothing to emit, nothing to discard.
     */
    private void expandMacroInvocation() {
        SpnParseToken nameTok = tokens.advance();
        MacroDef macro = macroRegistry.get(nameTok.text());
        expandMacroInvocationAt(macro, nameTok);
    }

    /** Expand a macro whose name token has already been consumed by the caller.
     *  The next token must be the opening {@code <} delimiter. Used by
     *  type-context invocation where the TypeParser has already advanced past
     *  the name token before recognising the macro. */
    private void expandMacroInvocationAt(MacroDef macro, SpnParseToken nameTok) {
        tokens.expect("<");
        List<List<SpnParseToken>> args = new ArrayList<>();
        while (tokens.hasMore() && !tokens.check(">")) {
            List<SpnParseToken> arg = new ArrayList<>();
            int depth = 0;
            int angleDepth = 0;
            while (tokens.hasMore()) {
                String t = tokens.peek().text();
                if (depth == 0 && angleDepth == 0 && (t.equals(",") || t.equals(">"))) break;
                if (t.equals("(") || t.equals("[") || t.equals("{")) depth++;
                else if (t.equals(")") || t.equals("]") || t.equals("}")) depth--;
                else if (t.equals("<")) angleDepth++;
                else if (t.equals(">")) angleDepth--;
                arg.add(tokens.advance());
            }
            args.add(arg);
            if (!tokens.hasMore()) break;
            tokens.match(",");
        }
        tokens.expect(">");

        if (args.size() != macro.paramNames().size()) {
            throw tokens.error("Macro '" + macro.name() + "' expects "
                    + macro.paramNames().size() + " argument(s), got " + args.size(), nameTok);
        }

        // Memoization lookup (file-scoped): same macro + same arg token text
        // yields the same internal emitted type. On hit, we short-circuit the
        // body-parsing step and advertise the cached type as the emit of this
        // invocation so callers like renameMacroEmittedType can alias it.
        String memoKey = buildMacroMemoKey(macro.name(), args);
        String cachedInternal = macroMemo.get(memoKey);
        if (cachedInternal != null) {
            macroEmittedBundle.clear();
            macroEmittedBundle.put(cachedInternal,
                    new EmittedEntry(EmittedKind.TYPE, cachedInternal));
            return;
        }

        // Check `requires` constraints on macro params.
        for (int i = 0; i < macro.params().size(); i++) {
            MacroParam p = macro.params().get(i);
            if (p.requiredSignature() == null) continue;
            List<SpnParseToken> argTokens = args.get(i);
            if (argTokens.size() != 1 || argTokens.get(0).type() != TokenType.TYPE_NAME) {
                throw tokens.error("Parameter '" + p.name() + "' of macro '" + macro.name()
                        + "' requires signature '" + p.requiredSignature()
                        + "' but got a non-type argument", nameTok);
            }
            String typeName = argTokens.get(0).text();
            List<String> missing = signatureMissingKeys(p.requiredSignature(), typeName);
            if (!missing.isEmpty()) {
                throw tokens.error("Type '" + typeName + "' doesn't satisfy signature '"
                        + p.requiredSignature() + "': missing " + String.join(", ", missing),
                        nameTok);
            }
        }

        // Build parameter → argument map
        Map<String, List<SpnParseToken>> substitution = new HashMap<>();
        for (int i = 0; i < macro.paramNames().size(); i++) {
            substitution.put(macro.paramNames().get(i), args.get(i));
        }

        // Substitute parameters in body tokens
        List<SpnParseToken> expanded = new ArrayList<>();
        for (SpnParseToken tok : macro.bodyTokens()) {
            List<SpnParseToken> sub = substitution.get(tok.text());
            if (sub != null) {
                for (SpnParseToken s : sub) {
                    expanded.add(new SpnParseToken(
                            tok.line(), tok.col(), tok.endCol(), s.text(), s.type()));
                }
            } else {
                expanded.add(tok);
            }
        }

        // Uniquify internal type names to prevent collisions between multiple
        // invocations of the same macro. Only rename types that were declared
        // IN THE ORIGINAL BODY (not substituted from parameters).
        macroExpansionCounter++;
        String suffix = "$" + macroExpansionCounter;
        // Find internal type names from the ORIGINAL body (before substitution)
        Set<String> internalTypeNames = new HashSet<>();
        List<SpnParseToken> bodyTokens = macro.bodyTokens();
        for (int i = 0; i < bodyTokens.size() - 1; i++) {
            if (bodyTokens.get(i).text().equals("type")
                    && bodyTokens.get(i + 1).type() == TokenType.TYPE_NAME
                    && !substitution.containsKey(bodyTokens.get(i + 1).text())) {
                internalTypeNames.add(bodyTokens.get(i + 1).text());
            }
        }
        // Rename occurrences of those names in the EXPANDED (post-substitution) tokens
        if (!internalTypeNames.isEmpty()) {
            for (int i = 0; i < expanded.size(); i++) {
                SpnParseToken t = expanded.get(i);
                if (internalTypeNames.contains(t.text())) {
                    expanded.set(i, new SpnParseToken(
                            t.line(), t.col(), t.endCol(),
                            t.text() + suffix, t.type()));
                }
            }
        }

        // Resolve macro-directive conditional blocks: <! if C !> { A } <! else !> { B }
        // becomes just A's tokens or B's tokens (braces stripped) based on C.
        expanded = resolveConditionalBlocks(expanded, nameTok);

        // Snapshot named registries BEFORE expansion
        Map<String, SpnStructDescriptor> snapStructs = new LinkedHashMap<>(structRegistry);
        Map<String, SpnTypeDescriptor> snapTypes = new LinkedHashMap<>(typeRegistry);
        Map<String, CallTarget> snapFunctions = new LinkedHashMap<>(functionRegistry);
        Map<String, MethodEntry> snapMethods = new LinkedHashMap<>(methodRegistry);
        Map<String, SpnFunctionDescriptor> snapDescriptors = new LinkedHashMap<>(functionDescriptorRegistry);
        Map<String, List<FactoryEntry>> snapFactories = new LinkedHashMap<>(factoryRegistry);
        Map<String, ConstantEntry> snapConstants = new LinkedHashMap<>(constantRegistry);
        Map<String, List<OperatorOverload>> snapFunctionOverloads = new LinkedHashMap<>(functionOverloads);

        // Enter macro scope
        boolean wasInMacro = insideMacroExpansion;
        insideMacroExpansion = true;
        macroEmittedBundle.clear();

        // Add a sentinel at the end so we know when the macro body is done
        expanded.add(new SpnParseToken(
                nameTok.line(), nameTok.col(), nameTok.endCol(),
                MACRO_END_SENTINEL, TokenType.IDENTIFIER));

        // Inject expanded tokens — the main parseTopLevel loop will process them.
        // The sentinel stops the macro scope.
        tokens.injectAt(expanded);

        // Parse until we hit the sentinel
        while (tokens.hasMore()) {
            SpnParseToken peek = tokens.peek();
            if (peek != null && MACRO_END_SENTINEL.equals(peek.text())) {
                tokens.advance(); // consume sentinel
                break;
            }
            try {
                SpnStatementNode stmt = parseTopLevel();
                // Statements from macro body are discarded (types/functions
                // registered in registries are what matter, not runtime nodes)
            } catch (SpnParseException e) {
                // Tag the error with the macro invocation site so the user
                // gets a stack trace: "error at X, in macro Y at file:line"
                e.pushMacroFrame(macro.name(), sourceName, nameTok.line());
                collectedErrors.add(e);
                synchronize();
            }
        }

        // Revert named registries — keep only emitted names + global semantics
        revertNamedRegistries(snapStructs, snapTypes, snapFunctions, snapMethods,
                snapDescriptors, snapFactories, snapConstants, snapFunctionOverloads);

        insideMacroExpansion = wasInMacro;

        // Record the (first) emitted type in the memo so subsequent invocations
        // with the same args reuse it.
        for (EmittedEntry entry : macroEmittedBundle.values()) {
            if (entry.kind() == EmittedKind.TYPE) {
                macroMemo.put(memoKey, entry.internalName());
                break;
            }
        }
    }

    /** Expand a macro invocation at a type position: {@code MacroName<Args>}
     *  with the name token already consumed by {@link TypeParser}. Parses the
     *  angle-bracketed args, runs expansion (memoized), and resolves the
     *  emitted type back to a {@link FieldType}. */
    private FieldType expandMacroAsType(SpnParseToken nameTok) {
        MacroDef macro = macroRegistry.get(nameTok.text());
        if (macro == null) return FieldType.UNTYPED;
        expandMacroInvocationAt(macro, nameTok);
        // Single-emit: the bundle has at most one TYPE entry after expansion.
        for (EmittedEntry entry : macroEmittedBundle.values()) {
            if (entry.kind() != EmittedKind.TYPE) continue;
            SpnStructDescriptor sd = structRegistry.get(entry.internalName());
            if (sd != null) return FieldType.ofStruct(sd);
            SpnTypeDescriptor td = typeRegistry.get(entry.internalName());
            if (td != null) {
                return td.isProduct()
                        ? FieldType.ofProduct(td)
                        : FieldType.ofConstrainedType(td);
            }
        }
        return FieldType.UNTYPED;
    }

    /** Build a file-scoped memo key: macro name + joined text of all arg tokens.
     *  Aliases don't collapse — `Array<Rat>` and `Array<Rational>` are distinct
     *  entries even if `Rat` is an alias for `Rational`. */
    private String buildMacroMemoKey(String macroName, List<List<SpnParseToken>> args) {
        StringBuilder sb = new StringBuilder(macroName);
        for (List<SpnParseToken> arg : args) {
            sb.append('|');
            for (SpnParseToken t : arg) sb.append(t.text()).append(' ');
        }
        return sb.toString();
    }

    /**
     * Revert named registries to their pre-macro state, keeping entries
     * that were explicitly {@code emit}-ted.
     */
    private void revertNamedRegistries(
            Map<String, SpnStructDescriptor> snapStructs,
            Map<String, SpnTypeDescriptor> snapTypes,
            Map<String, CallTarget> snapFunctions,
            Map<String, MethodEntry> snapMethods,
            Map<String, SpnFunctionDescriptor> snapDescriptors,
            Map<String, List<FactoryEntry>> snapFactories,
            Map<String, ConstantEntry> snapConstants,
            Map<String, List<OperatorOverload>> snapFunctionOverloads) {

        if (macroEmittedBundle.isEmpty()) {
            // No emit → old-style macro. Keep everything it registered
            // (backward compat: deriveOrderingFromInt, deriveDouble, etc.).
            return;
        }

        // Selective revert: keep emitted names, revert everything else.
        // For each registry, remove entries that are NEW (not in snapshot)
        // AND not emitted.
        revertRegistry(structRegistry, snapStructs);
        revertRegistry(typeRegistry, snapTypes);
        revertRegistry(functionRegistry, snapFunctions);
        revertRegistry(methodRegistry, snapMethods);
        revertRegistry(functionDescriptorRegistry, snapDescriptors);
        revertRegistry(factoryRegistry, snapFactories);
        revertRegistry(constantRegistry, snapConstants);
        revertRegistry(functionOverloads, snapFunctionOverloads);
    }

    /** Remove new entries from a registry unless they match an emitted name. */
    private <V> void revertRegistry(Map<String, V> registry, Map<String, V> snapshot) {
        var newKeys = new ArrayList<>(registry.keySet());
        for (String key : newKeys) {
            if (!snapshot.containsKey(key) && !isEmittedKey(key)) {
                registry.remove(key);
            }
        }
    }

    /** Check if a registry key matches any emitted name (direct or prefixed like "TypeName.method"). */
    private boolean isEmittedKey(String key) {
        for (EmittedEntry entry : macroEmittedBundle.values()) {
            String emitted = entry.internalName();
            if (key.equals(emitted) || key.startsWith(emitted + ".")) return true;
        }
        return false;
    }

    /**
     * After a macro expansion in {@code type X = macroCall<T>} context, find
     * the emitted type (registered under its internal name) and re-register
     * it under the user's chosen name {@code X}. Also re-register any methods
     * and factories prefixed with the internal name.
     */
    private void renameMacroEmittedType(String targetName, SpnParseToken nameTok) {
        if (macroEmittedBundle.isEmpty()) {
            throw tokens.error("Macro did not emit a type — use 'emit TypeName' in the macro body", nameTok);
        }

        if (macroEmittedBundle.size() > 1) {
            throw tokens.error("Macro emits multiple declarations (" + macroEmittedBundle.size()
                    + "); a macro invoked from 'type X = macro<...>' must emit exactly one type",
                    nameTok);
        }

        String internalName = macroEmittedBundle.values().iterator().next().internalName();
        aliasEmittedType(targetName, internalName, nameTok);
    }

    /**
     * Register an already-emitted internal type under {@code targetName} as an alias.
     * Copies the struct descriptor, type descriptor, methods, factories, and
     * function descriptors prefixed with the internal name.
     */
    private void aliasEmittedType(String targetName, String internalName, SpnParseToken nameTok) {
        // Register the struct descriptor under the user's chosen name as an ALIAS.
        // Keep the internal name too — compiled CallTargets reference the original
        // descriptor, so lookups by the internal name must still work.
        SpnStructDescriptor sd = structRegistry.get(internalName);
        if (sd != null) {
            structRegistry.put(targetName, sd);
            typeGraph.add(TypeGraph.Node.builder(targetName, TypeGraph.Kind.TYPE)
                    .file(sourceName).nameRange(nameTok.range())
                    .structDescriptor(sd).build());
        }

        // Alias type descriptor
        SpnTypeDescriptor td = typeRegistry.get(internalName);
        if (td != null) {
            typeRegistry.put(targetName, td);
        }

        // Alias methods: "InternalName.method" → also register as "TargetName.method"
        for (var entry : new ArrayList<>(methodRegistry.entrySet())) {
            if (entry.getKey().startsWith(internalName + ".")) {
                String newKey = targetName + entry.getKey().substring(internalName.length());
                methodRegistry.put(newKey, entry.getValue());
            }
        }

        // Alias factories
        List<FactoryEntry> fes = factoryRegistry.get(internalName);
        if (fes != null) {
            factoryRegistry.put(targetName, fes);
        }

        // Alias function descriptors
        for (var entry : new ArrayList<>(functionDescriptorRegistry.entrySet())) {
            String key = entry.getKey();
            if (key.equals(internalName) || key.startsWith(internalName + ".") || key.startsWith(internalName + "/")) {
                String newKey = targetName + key.substring(internalName.length());
                functionDescriptorRegistry.put(newKey, entry.getValue());
            }
        }
    }

    // ── Emit (macro scope export) ────────────────────────────────────────

    /**
     * Parse: {@code emit TypeName}
     *
     * <p>Only valid inside a macro expansion. Records the (internal name → internal
     * name) entry in the current macro's emit bundle — the emitted type, together
     * with any methods prefixed with its name, persists into the caller's scope
     * after the macro scope reverts; internal declarations without an emit are
     * discarded.
     */
    private void parseEmit() {
        SpnParseToken emitTok = tokens.expect("emit");
        if (!insideMacroExpansion) {
            throw tokens.error("'emit' can only be used inside a macro body", emitTok);
        }

        SpnParseToken nameTok = tokens.advance();
        String name = nameTok.text();
        macroEmittedBundle.put(name, new EmittedEntry(EmittedKind.TYPE, name));
    }

    // ── Qualified-key registration: register pure @fqn(params) -> ret ─────

    /**
     * Parse: {@code register pure @a.b.name(params) -> ReturnType}
     *        {@code register action @a.b.name(params) -> ReturnType}
     *
     * <p>Declares a globally-unique qualified dispatch key. The FQN's
     * namespace (all dotted segments except the last) must prefix-match
     * the current file's {@code module} namespace — you may only register
     * keys under a namespace you own.
     *
     * <p>Registration is a declaration only; impls are provided separately
     * by any module. The declared descriptor lives in {@code qualifiedKeyRegistry}
     * and serves as the interface contract that signatures can reference.
     */
    private void parseRegisterDecl() {
        SpnParseToken regTok = tokens.expect("register");
        boolean isPure;
        if (tokens.match("pure")) {
            isPure = true;
        } else if (tokens.match("action")) {
            isPure = false;
        } else {
            throw tokens.error("Expected 'pure' or 'action' after 'register'", regTok);
        }

        SpnParseToken keyTok = tokens.peek();
        if (keyTok == null || keyTok.type() != TokenType.QUALIFIED_KEY) {
            throw tokens.error("Expected a qualified key (e.g. @com.myapp.serialize) after 'register " + (isPure ? "pure" : "action") + "'", regTok);
        }
        tokens.advance();
        String fqn = keyTok.text(); // includes leading @

        // Dotted-FQN ownership enforcement: the namespace portion of the key
        // (everything before the last dot) must match the current module's
        // namespace as a prefix.
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot < 0) {
            // Unqualified — allowed for now, but no namespace checks apply.
            // Useful for local/experimental keys within a single file.
        } else {
            String keyPrefix = fqn.substring(1, lastDot); // strip leading @
            if (currentModuleNamespace == null) {
                throw tokens.error("Cannot register '" + fqn
                        + "' — this file has no 'module' declaration. Claim a namespace first.", keyTok);
            }
            if (!keyPrefix.equals(currentModuleNamespace)
                    && !keyPrefix.startsWith(currentModuleNamespace + ".")) {
                throw tokens.error("Cannot register '" + fqn
                        + "' — key namespace '" + keyPrefix
                        + "' is not under this file's module namespace '"
                        + currentModuleNamespace + "'.", keyTok);
            }
        }

        List<FieldType> paramTypes = parseParamTypeList();
        FieldType returnType = parseOptionalReturnType();

        // Build the descriptor for the slot. No body; callers provide impls.
        SpnFunctionDescriptor.Builder db = isPure
                ? SpnFunctionDescriptor.pure(fqn) : SpnFunctionDescriptor.impure(fqn);
        for (int i = 0; i < paramTypes.size(); i++) db.param("_" + i, paramTypes.get(i));
        db.returns(returnType);
        SpnFunctionDescriptor descriptor = db.build();

        // Duplicate registration is a conflict — a key is owned exactly once.
        if (qualifiedKeyRegistry.containsKey(fqn)) {
            throw tokens.error("Qualified key '" + fqn + "' is already registered.", keyTok);
        }
        qualifiedKeyRegistry.put(fqn, descriptor);
    }

    // ── Named signatures: signature Name (keys, sub-signatures) ────────────

    /**
     * Parse: {@code signature Name (@key, @other.key, SubSig, ...)}
     *
     * <p>A signature is a named set of required dispatch keys. Entries are
     * flat; a sub-signature's keys expand in place at parse time so the
     * registry holds the transitive closure. No type variables in the
     * signature body — the canonical shape of each key lives in its
     * registration (or built-in operator definition).
     *
     * <p>A type satisfies a signature iff it has impls for every key in
     * the flattened set. Structural, anonymous, retroactive.
     */
    private void parseSignatureDecl() {
        SpnParseToken sigTok = tokens.expect("signature");
        SpnParseToken nameTok = tokens.peek();
        if (nameTok == null || nameTok.type() != TokenType.TYPE_NAME) {
            throw tokens.error("Expected a capitalized signature name after 'signature'", sigTok);
        }
        tokens.advance();
        String sigName = nameTok.text();

        tokens.expect("(");
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        while (!tokens.check(")")) {
            SpnParseToken entry = tokens.peek();
            if (entry == null) throw tokens.error("Unterminated signature body", sigTok);
            if (entry.type() == TokenType.QUALIFIED_KEY) {
                tokens.advance();
                keys.add(entry.text());
            } else if (entry.type() == TokenType.TYPE_NAME) {
                tokens.advance();
                Set<String> sub = signatureRegistry.get(entry.text());
                if (sub == null) {
                    throw tokens.error("Unknown signature '" + entry.text()
                            + "' referenced in '" + sigName + "'", entry);
                }
                keys.addAll(sub);
            } else {
                throw tokens.error("Signature entry must be a '@key' or another signature name, got: "
                        + entry.text(), entry);
            }
            tokens.match(",");
        }
        tokens.expect(")");

        if (signatureRegistry.containsKey(sigName)) {
            throw tokens.error("Signature '" + sigName + "' is already defined.", nameTok);
        }
        signatureRegistry.put(sigName, keys);
    }

    /**
     * Check whether a type satisfies a named signature — i.e. has impls for
     * every required dispatch key. Returns a list of missing keys (empty if
     * satisfied). The caller decides whether "satisfies" is true/false and
     * how to report missing keys.
     */
    private List<String> signatureMissingKeys(String sigName, String typeName) {
        Set<String> required = signatureRegistry.get(sigName);
        if (required == null) return List.of(sigName); // unknown signature itself
        List<String> missing = new ArrayList<>();
        for (String key : required) {
            if (!typeHasKey(typeName, key)) missing.add(key);
        }
        return missing;
    }

    /**
     * Structural check: does {@code typeName} have an impl for {@code key}?
     * Operator keys ({@code @+}, {@code @*}) check the operator registry for
     * overloads involving the type. Method keys ({@code @name},
     * {@code @pkg.name}) check the method registry for
     * {@code TypeName.@key}.
     */
    private boolean typeHasKey(String typeName, String key) {
        // Strip the leading '@'
        String body = key.startsWith("@") ? key.substring(1) : key;
        // If body contains only operator chars (plus optional _qualifier),
        // treat as operator lookup.
        String opName = extractOperatorName(body);
        if (opName != null) {
            FieldType tType = resolveTypeByName(typeName);
            List<OperatorOverload> overloads = operatorRegistry.get(opName);
            if (overloads == null || tType == null) return false;
            for (OperatorOverload ov : overloads) {
                for (FieldType pt : ov.paramTypes()) {
                    if (typesMatch(pt, tType)) return true;
                }
            }
            return false;
        }
        // Otherwise it's a method-style key: look up TypeName.@key
        return methodRegistry.containsKey(typeName + "." + key);
    }

    /** If the key body is all operator chars (with optional _qualifier), return it. */
    private static String extractOperatorName(String body) {
        if (body.isEmpty()) return null;
        int i = 0;
        while (i < body.length() && isOpChar(body.charAt(i))) i++;
        if (i == 0) return null;
        if (i < body.length() && body.charAt(i) != '_') return null;
        return body;
    }

    private static boolean isOpChar(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/' || c == '%'
                || c == '=' || c == '<' || c == '>' || c == '|' || c == '&';
    }

    // ── Macro conditional blocks: <! if COND !> { A } <! else !> { B } ─────

    /**
     * Scans a (parameter-substituted) macro expansion for conditional block
     * directives. Each directive has the fixed shape:
     *   {@code <! if COND !> { BRANCH_TRUE } <! else !> { BRANCH_FALSE }}
     * The condition is evaluated against literal tokens (macro-param
     * substitution has already happened). The entire directive is replaced
     * with the inner tokens of the chosen branch — braces stripped — so that
     * declarations inside the branch register at the surrounding scope.
     * Nested directives are resolved recursively on the chosen branch.
     *
     * <p>Mandatory {@code else} — no single-branch form in v1.
     */
    private List<SpnParseToken> resolveConditionalBlocks(
            List<SpnParseToken> input, SpnParseToken macroCallTok) {
        List<SpnParseToken> out = new ArrayList<>();
        int i = 0;
        while (i < input.size()) {
            SpnParseToken t = input.get(i);
            if (t.type() == TokenType.MACRO_DIRECTIVE && t.text().equals("<!")) {
                // Parse: <! if COND !> { A } <! else !> { B }
                int start = i;
                i++;
                if (i >= input.size() || !input.get(i).text().equals("if")) {
                    throw error("Expected 'if' after '<!'", input.get(start), macroCallTok);
                }
                i++;
                List<SpnParseToken> condTokens = new ArrayList<>();
                while (i < input.size() && !(input.get(i).type() == TokenType.MACRO_DIRECTIVE
                        && input.get(i).text().equals("!>"))) {
                    condTokens.add(input.get(i));
                    i++;
                }
                if (i >= input.size()) {
                    throw error("Unterminated macro directive (missing '!>')",
                            input.get(start), macroCallTok);
                }
                i++; // consume !>
                List<SpnParseToken> trueBranch = consumeBracedBlock(input, i, start, macroCallTok);
                i += trueBranch.size() + 2; // { ... }
                // expect <! else !>
                if (i + 2 >= input.size()
                        || input.get(i).type() != TokenType.MACRO_DIRECTIVE
                        || !input.get(i).text().equals("<!")
                        || !input.get(i + 1).text().equals("else")
                        || input.get(i + 2).type() != TokenType.MACRO_DIRECTIVE
                        || !input.get(i + 2).text().equals("!>")) {
                    throw error("Expected '<! else !>' — mandatory after '<! if !> {...}'",
                            input.get(start), macroCallTok);
                }
                i += 3; // consume <! else !>
                List<SpnParseToken> falseBranch = consumeBracedBlock(input, i, start, macroCallTok);
                i += falseBranch.size() + 2;

                boolean chosen = evalMacroCondition(condTokens, input.get(start), macroCallTok);
                // Recursively resolve nested directives within the chosen branch
                out.addAll(resolveConditionalBlocks(
                        chosen ? trueBranch : falseBranch, macroCallTok));
            } else if (t.type() == TokenType.MACRO_DIRECTIVE && t.text().equals("!>")) {
                throw error("Unexpected '!>' without matching '<!'", t, macroCallTok);
            } else {
                out.add(t);
                i++;
            }
        }
        return out;
    }

    /**
     * Returns the tokens INSIDE a {@code { ... }} block starting at index
     * {@code openPos} in {@code input}. Tracks brace depth so nested blocks
     * are consumed correctly.
     */
    private List<SpnParseToken> consumeBracedBlock(
            List<SpnParseToken> input, int openPos, int directiveStart, SpnParseToken macroCallTok) {
        if (openPos >= input.size() || !input.get(openPos).text().equals("{")) {
            throw error("Expected '{' after macro directive", input.get(directiveStart), macroCallTok);
        }
        int depth = 1;
        List<SpnParseToken> inside = new ArrayList<>();
        int i = openPos + 1;
        while (i < input.size() && depth > 0) {
            SpnParseToken t = input.get(i);
            if (t.text().equals("{")) {
                depth++;
                inside.add(t);
            } else if (t.text().equals("}")) {
                depth--;
                if (depth == 0) return inside;
                inside.add(t);
            } else {
                inside.add(t);
            }
            i++;
        }
        throw error("Unterminated block in macro directive", input.get(directiveStart), macroCallTok);
    }

    /**
     * Evaluates a condition built from literal tokens (post-substitution).
     * Supports: int/string/symbol/bool literals; comparison (==, !=, &lt;, &gt;,
     * &lt;=, &gt;=); logical (&amp;&amp;, ||, !); parentheses. Any non-literal
     * identifier reaching evaluation = error.
     */
    private boolean evalMacroCondition(List<SpnParseToken> cond, SpnParseToken directiveTok,
                                       SpnParseToken macroCallTok) {
        try {
            MacroCondEval ev = new MacroCondEval(cond);
            Object v = ev.parseExpr();
            if (ev.pos != cond.size()) {
                throw new RuntimeException("Trailing tokens in condition");
            }
            if (!(v instanceof Boolean b)) {
                throw new RuntimeException("Condition did not evaluate to bool");
            }
            return b;
        } catch (RuntimeException rex) {
            throw error("Macro condition: " + rex.getMessage(), directiveTok, macroCallTok);
        }
    }

    /** Small recursive-descent evaluator over condition tokens. */
    private static final class MacroCondEval {
        final List<SpnParseToken> toks;
        int pos = 0;
        MacroCondEval(List<SpnParseToken> toks) { this.toks = toks; }

        SpnParseToken peek() { return pos < toks.size() ? toks.get(pos) : null; }
        SpnParseToken advance() { return toks.get(pos++); }
        boolean matchText(String t) {
            if (peek() != null && peek().text().equals(t)) { pos++; return true; }
            return false;
        }

        Object parseExpr() { return parseOr(); }

        Object parseOr() {
            Object v = parseAnd();
            while (matchText("||")) {
                Object r = parseAnd();
                v = asBool(v) || asBool(r);
            }
            return v;
        }

        Object parseAnd() {
            Object v = parseNot();
            while (matchText("&&")) {
                Object r = parseNot();
                v = asBool(v) && asBool(r);
            }
            return v;
        }

        Object parseNot() {
            if (matchText("!")) return !asBool(parseNot());
            return parseComp();
        }

        Object parseComp() {
            Object left = parseAtom();
            SpnParseToken op = peek();
            if (op != null) {
                String t = op.text();
                if (t.equals("==") || t.equals("!=") || t.equals("<")
                        || t.equals(">") || t.equals("<=") || t.equals(">=")) {
                    advance();
                    Object right = parseAtom();
                    return compare(t, left, right);
                }
            }
            return left;
        }

        Object parseAtom() {
            SpnParseToken t = advance();
            if (t.type() == TokenType.NUMBER) return Long.parseLong(t.text());
            if (t.type() == TokenType.STRING) {
                String s = t.text();
                // strip surrounding quotes
                return s.length() >= 2 ? s.substring(1, s.length() - 1) : "";
            }
            if (t.type() == TokenType.SYMBOL) return t.text(); // ":foo"
            if (t.text().equals("true")) return Boolean.TRUE;
            if (t.text().equals("false")) return Boolean.FALSE;
            if (t.text().equals("(")) {
                Object v = parseExpr();
                if (!matchText(")")) throw new RuntimeException("Expected ')'");
                return v;
            }
            throw new RuntimeException("unexpected token '" + t.text() + "'");
        }

        static boolean asBool(Object o) {
            if (o instanceof Boolean b) return b;
            throw new RuntimeException("expected bool, got " + (o == null ? "null" : o.getClass().getSimpleName()));
        }

        static boolean compare(String op, Object a, Object b) {
            if (a instanceof Long la && b instanceof Long lb) {
                return switch (op) {
                    case "==" -> la.equals(lb);
                    case "!=" -> !la.equals(lb);
                    case "<"  -> la < lb;
                    case ">"  -> la > lb;
                    case "<=" -> la <= lb;
                    case ">=" -> la >= lb;
                    default -> false;
                };
            }
            if (op.equals("==")) return java.util.Objects.equals(a, b);
            if (op.equals("!=")) return !java.util.Objects.equals(a, b);
            throw new RuntimeException("ordering operator '" + op + "' requires ints");
        }
    }

    /** Build a SpnParseException tagged with the macro call site. */
    private SpnParseException error(String msg, SpnParseToken at, SpnParseToken macroCallTok) {
        SpnParseException e = new SpnParseException(msg, sourceName, at.line(), at.col());
        e.pushMacroFrame("conditional", sourceName, macroCallTok.line());
        return e;
    }

    // ── Import declarations ────────────────────────────────────────────────

    /**
     * Parses an import declaration:
     *   import Math
     *   import Math (abs, sqrt, pow)
     *   import Math as M
     *   import spn.mylib.utils
     *   import spn.mylib.utils (helperA, helperB)
     *   import String (join as glue)
     */
    /**
     * Parse a module declaration (module com.mysite.mymodule) and record the
     * namespace for ownership checks on `register @fqn` declarations.
     * Module declarations are primarily meaningful in module.spn files
     * (handled by ModuleParser), but source files may also carry one to
     * claim a namespace for qualified-key registration.
     */
    private void skipModuleDecl() {
        tokens.expect("module");
        StringBuilder ns = new StringBuilder();
        ns.append(tokens.advance().text()); // first segment
        while (tokens.match(".")) {
            ns.append('.').append(tokens.advance().text());
        }
        this.currentModuleNamespace = ns.toString();
    }

    /** Skip a version declaration (version "1.0.0" or version 1.0.0). */
    private void skipVersionDecl() {
        tokens.expect("version");
        tokens.advance(); // the version string or first number segment
        // Consume additional .segment parts for unquoted versions like 1.0.0
        while (tokens.match(".")) {
            tokens.advance();
        }
    }

    /** Skip a require declaration (require "com.other.lib"). */
    private void skipRequireDecl() {
        tokens.expect("require");
        tokens.advance(); // the dependency string
    }

    private void parseImportDecl() {
        // Position of the `import` keyword — used so go-to-def can land on
        // the import statement when the clicked type was brought in by it.
        SpnParseToken importTok = tokens.peek();
        tokens.expect("import");

        // Parse the module path
        StringBuilder path = new StringBuilder();
        SpnParseToken first = tokens.peek();
        if (first == null) throw tokens.error("Expected module name after 'import'");

        if (first.type() == TokenType.TYPE_NAME) {
            // Short name: import Math, import Canvas
            path.append(tokens.advance().text());
        } else {
            // FQ namespace: import spn.mylib.utils
            path.append(tokens.expectType(TokenType.IDENTIFIER).text());
            while (tokens.check(".")) {
                // Peek past the dot — if followed by '(', we're at the
                // qualified-key selective form `import pkg.(key)`, not an
                // additional path segment. Stop path accumulation.
                SpnParseToken afterDot = tokens.peek(1);
                if (afterDot != null && afterDot.text().equals("(")) break;
                tokens.advance(); // consume '.'
                SpnParseToken segment = tokens.advance();
                path.append(".").append(segment.text());
            }
        }

        // Qualified-key import form: `import a.b.(key1, key2)` — aliases each
        // name to the full `@a.b.name` qualified key for use in method-call
        // position. This is syntactically distinct from the regular selective
        // form (no space between path and paren).
        if (tokens.check(".") && tokens.peek(1) != null
                && tokens.peek(1).text().equals("(")) {
            tokens.advance(); // '.'
            tokens.expect("(");
            while (!tokens.check(")")) {
                SpnParseToken nameTok = tokens.advance();
                String shortName = nameTok.text();
                String fullKey = "@" + path + "." + shortName;
                qualifiedKeyAliases.put(shortName, fullKey);
                tokens.match(",");
            }
            tokens.expect(")");
            return;
        }

        // Optional selective import list: (abs, min, max)
        List<ImportDirective.ImportedName> names = null;
        if (tokens.check("(")) {
            tokens.advance();
            names = new ArrayList<>();
            while (!tokens.check(")")) {
                String name = tokens.advance().text();
                String alias = null;
                if (tokens.check("as")) {
                    tokens.advance(); // consume 'as' contextually
                    alias = tokens.advance().text();
                }
                names.add(new ImportDirective.ImportedName(name, alias));
                tokens.match(",");
            }
            tokens.expect(")");
        }

        // Optional qualifier: as M
        String qualifier = null;
        if (tokens.check("as")) {
            tokens.advance(); // consume 'as' contextually
            qualifier = tokens.advance().text();
        }

        ImportDirective directive = new ImportDirective(path.toString(), names, qualifier);

        // Snapshot type-related registries so we can record which names this
        // import brought into scope, and attribute them to this statement for
        // go-to-def on imported types.
        Set<String> typesBefore = new HashSet<>(typeRegistry.keySet());
        Set<String> structsBefore = new HashSet<>(structRegistry.keySet());
        Set<String> variantsBefore = new HashSet<>(variantRegistry.keySet());

        resolveAndApplyImport(directive);

        spn.source.SourceRange importRange = importTok.range();
        recordNewImportedNames(typesBefore, typeRegistry.keySet(), importRange);
        recordNewImportedNames(structsBefore, structRegistry.keySet(), importRange);
        recordNewImportedNames(variantsBefore, variantRegistry.keySet(), importRange);
    }

    /** Add every name in {@code after \ before} to {@link #importedTypeRanges}
     *  pointing at {@code range}. First import of a given name wins. */
    private void recordNewImportedNames(Set<String> before, Set<String> after,
                                        spn.source.SourceRange range) {
        for (String name : after) {
            if (!before.contains(name)) {
                importedTypeRanges.putIfAbsent(name, range);
            }
        }
    }

    /**
     * Resolves an import directive and applies it to the current parser's registries.
     */
    private void resolveAndApplyImport(ImportDirective directive) {
        if (moduleRegistry == null) {
            throw tokens.error("Module system not available — cannot resolve import '"
                    + directive.modulePath() + "'");
        }

        // Look up the module (native short name, then FQ namespace, then loaders)
        SpnModule module = moduleRegistry.lookupNative(directive.modulePath())
                .or(() -> moduleRegistry.resolve(directive.modulePath()))
                .orElse(null);

        if (module == null) {
            throw tokens.error("Unknown module: " + directive.modulePath());
        }

        if (directive.isQualified()) {
            // import X as Q — store module for qualified access
            qualifiedModules.put(directive.qualifier(), module);
            // Also apply selective imports if both qualifier and selective list are present
            if (directive.isSelective()) {
                applySelectiveImport(module, directive);
            }
        } else if (directive.isSelective()) {
            // import X (a, b, c) — copy only named exports
            applySelectiveImport(module, directive);
        } else {
            // import X — copy all exports
            applyFullImport(module);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyFullImport(SpnModule module) {
        functionRegistry.putAll(module.getFunctions());
        builtinRegistry.putAll(module.getBuiltinFactories());
        typeRegistry.putAll(module.getTypes());
        structRegistry.putAll(module.getStructs());
        variantRegistry.putAll(module.getVariants());
        if (module.isImpure()) {
            impureBuiltins.addAll(module.getBuiltinFactories().keySet());
            impureBuiltins.addAll(module.getFunctions().keySet());
        }
        // Per-function impurity: modules that are mostly-pure can still flag
        // specific builtins as impure via the "impureFunctions" extras set.
        Set<String> perFnImpure = module.getExtra("impureFunctions");
        if (perFnImpure != null) impureBuiltins.addAll(perFnImpure);
        // Import extended registries (methods, factories, operators, descriptors)
        Map<String, MethodEntry> methods = module.getExtra("methods");
        if (methods != null) methodRegistry.putAll(methods);
        Map<String, spn.node.BuiltinFactory> methodFacs = module.getExtra("methodFactories");
        if (methodFacs != null) methodFactories.putAll(methodFacs);
        Map<String, SpnFunctionDescriptor> methodFacDescs = module.getExtra("methodFactoryDescriptors");
        if (methodFacDescs != null) methodFactoryDescriptors.putAll(methodFacDescs);
        Map<String, List<FactoryEntry>> factories = module.getExtra("factories");
        if (factories != null) {
            for (var entry : factories.entrySet()) {
                factoryRegistry.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .addAll(entry.getValue());
            }
        }
        Map<String, List<OperatorOverload>> operators = module.getExtra("operators");
        if (operators != null) {
            for (var entry : operators.entrySet()) {
                operatorRegistry.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                        .addAll(entry.getValue());
            }
        }
        Map<String, SpnFunctionDescriptor> descriptors = module.getExtra("descriptors");
        if (descriptors != null) functionDescriptorRegistry.putAll(descriptors);
        Map<String, ConstantEntry> constants = module.getExtra("constants");
        if (constants != null) constantRegistry.putAll(constants);
        Map<String, MacroDef> macros = module.getExtra("macros");
        if (macros != null) macroRegistry.putAll(macros);
        List<Promotion> promotions = module.getExtra("promotions");
        if (promotions != null) promotionRegistry.addAll(promotions);
        // IDE go-to-def: copy source positions for imported types so clicks
        // on a cross-module type name land on the declaration, not the import.
        Map<String, spn.language.TypeDeclPos> typeDecls = module.getExtra("typeDeclarations");
        if (typeDecls != null) {
            for (var entry : typeDecls.entrySet()) {
                importedTypeDeclarations.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        Map<CallTarget, spn.language.TypeDeclPos> factoryDecls =
                module.getExtra("factoryDeclarations");
        if (factoryDecls != null) {
            for (var entry : factoryDecls.entrySet()) {
                importedFactoryDeclarations.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        Map<String, spn.language.TypeDeclPos> constDecls =
                module.getExtra("constantDeclarations");
        if (constDecls != null) {
            for (var entry : constDecls.entrySet()) {
                importedConstantDeclarations.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        Map<CallTarget, spn.language.TypeDeclPos> methodDecls =
                module.getExtra("methodDeclarations");
        if (methodDecls != null) {
            for (var entry : methodDecls.entrySet()) {
                importedMethodDeclarations.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        Map<String, spn.language.TypeDeclPos> fieldDecls =
                module.getExtra("fieldDeclarations");
        if (fieldDecls != null) {
            for (var entry : fieldDecls.entrySet()) {
                importedFieldDeclarations.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        Map<CallTarget, spn.language.TypeDeclPos> opDecls =
                module.getExtra("operatorDeclarations");
        if (opDecls != null) {
            for (var entry : opDecls.entrySet()) {
                importedOperatorDeclarations.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applySelectiveImport(SpnModule module, ImportDirective directive) {
        for (ImportDirective.ImportedName imp : directive.selectiveNames()) {
            String src = imp.name();
            String dst = imp.localName();

            CallTarget fn = module.getFunction(src);
            if (fn != null) {
                functionRegistry.put(dst, fn);
                if (module.isImpure()) impureBuiltins.add(dst);
                continue;
            }

            spn.node.BuiltinFactory bf = module.getBuiltinFactory(src);
            if (bf != null) {
                builtinRegistry.put(dst, bf);
                if (module.isImpure()) impureBuiltins.add(dst);
                continue;
            }

            SpnTypeDescriptor td = module.getType(src);
            if (td != null) { typeRegistry.put(dst, td); }

            SpnStructDescriptor sd = module.getStruct(src);
            if (sd != null) { structRegistry.put(dst, sd); }

            SpnVariantSet vs = module.getVariant(src);
            if (vs != null) { variantRegistry.put(dst, vs); }

            // IDE go-to-def: if this module carries source positions for the
            // imported type, record it under the LOCAL alias so clicks on
            // the name in this file route to the original declaration.
            if (td != null || sd != null || vs != null) {
                Map<String, spn.language.TypeDeclPos> typeDecls = module.getExtra("typeDeclarations");
                if (typeDecls != null) {
                    spn.language.TypeDeclPos pos = typeDecls.get(src);
                    if (pos != null) importedTypeDeclarations.putIfAbsent(dst, pos);
                }
            }

            // If this is a type/struct, also import all associated behavior
            if (td != null || sd != null) {
                importTypeAssociations(module, src);
                continue;
            }

            if (td == null && sd == null && vs == null) {
                throw tokens.error("Module '" + module.getNamespace()
                        + "' does not export '" + src + "'");
            }
        }
    }

    /** Import methods, factories, constants, and operator overloads associated with a type. */
    @SuppressWarnings("unchecked")
    private void importTypeAssociations(SpnModule module, String typeName) {
        // Fields: keys like "TypeName.fieldName" or "TypeName.0" for positional.
        // Pull in just the fields of the type being imported.
        Map<String, spn.language.TypeDeclPos> fieldDecls =
                module.getExtra("fieldDeclarations");
        if (fieldDecls != null) {
            String prefix = typeName + ".";
            for (var entry : fieldDecls.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    importedFieldDeclarations.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }

        // Methods: keys like "TypeName.methodName"
        Map<String, MethodEntry> methods = module.getExtra("methods");
        Map<CallTarget, spn.language.TypeDeclPos> methodDecls =
                module.getExtra("methodDeclarations");
        if (methods != null) {
            for (var entry : methods.entrySet()) {
                if (entry.getKey().startsWith(typeName + ".")) {
                    MethodEntry me = entry.getValue();
                    methodRegistry.put(entry.getKey(), me);
                    if (methodDecls != null && me.callTarget() != null) {
                        spn.language.TypeDeclPos pos = methodDecls.get(me.callTarget());
                        if (pos != null) {
                            importedMethodDeclarations.putIfAbsent(me.callTarget(), pos);
                        }
                    }
                }
            }
        }

        // Factories: keyed by type name
        Map<String, List<FactoryEntry>> factories = module.getExtra("factories");
        Map<CallTarget, spn.language.TypeDeclPos> factoryDecls =
                module.getExtra("factoryDeclarations");
        if (factories != null) {
            List<FactoryEntry> typeFactories = factories.get(typeName);
            if (typeFactories != null) {
                factoryRegistry.computeIfAbsent(typeName, k -> new ArrayList<>())
                        .addAll(typeFactories);
                // Pull in source positions for the factories we just imported so
                // ctrl+click on `TypeName(args)` lands on the correct overload.
                if (factoryDecls != null) {
                    for (FactoryEntry fe : typeFactories) {
                        spn.language.TypeDeclPos pos = factoryDecls.get(fe.callTarget());
                        if (pos != null) {
                            importedFactoryDeclarations.putIfAbsent(fe.callTarget(), pos);
                        }
                    }
                }
            }
        }

        // Constants: keys like "TypeName.constName"
        Map<String, ConstantEntry> constants = module.getExtra("constants");
        Map<String, spn.language.TypeDeclPos> constDecls =
                module.getExtra("constantDeclarations");
        if (constants != null) {
            for (var entry : constants.entrySet()) {
                if (entry.getKey().startsWith(typeName + ".")) {
                    constantRegistry.put(entry.getKey(), entry.getValue());
                    if (constDecls != null) {
                        spn.language.TypeDeclPos pos = constDecls.get(entry.getKey());
                        if (pos != null) {
                            importedConstantDeclarations.putIfAbsent(entry.getKey(), pos);
                        }
                    }
                }
            }
        }

        // Operator overloads: import any overload where the type participates
        Map<String, List<OperatorOverload>> operators = module.getExtra("operators");
        Map<CallTarget, spn.language.TypeDeclPos> opDecls =
                module.getExtra("operatorDeclarations");
        if (operators != null) {
            for (var entry : operators.entrySet()) {
                for (OperatorOverload ov : entry.getValue()) {
                    boolean involves = false;
                    for (FieldType pt : ov.paramTypes()) {
                        String desc = pt.describe();
                        if (desc.equals(typeName)) { involves = true; break; }
                    }
                    if (involves) {
                        operatorRegistry.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                                .add(ov);
                        if (opDecls != null && ov.callTarget() != null) {
                            spn.language.TypeDeclPos pos = opDecls.get(ov.callTarget());
                            if (pos != null) {
                                importedOperatorDeclarations.putIfAbsent(ov.callTarget(), pos);
                            }
                        }
                    }
                }
            }
        }

        // Function descriptors for the type's functions
        Map<String, SpnFunctionDescriptor> descriptors = module.getExtra("descriptors");
        if (descriptors != null) {
            for (var entry : descriptors.entrySet()) {
                if (entry.getKey().startsWith(typeName + ".") || entry.getKey().equals(typeName)) {
                    functionDescriptorRegistry.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    // ── Type declarations ──────────────────────────────────────────────────

    /**
     * Parse: {@code stateful type Name(field1: T1, field2: T2, ...)}
     *
     * <p>Registers the type name as stateful. Construction is only allowed
     * via the block form {@code Name(args) { body }} — not as a bare call.
     * Inside the block body, {@code this.*} accesses mutable fields on a
     * heap-allocated instance whose lifetime is bounded by the block.
     *
     * <p>Ad-hoc fields (not in the declared list) are permitted — they're
     * inferred sequentially during block-body parsing via {@code this.x = expr}.
     */
    private void parseStatefulTypeDecl() {
        SpnParseToken statefulTok = tokens.expect("stateful");
        tokens.expect("type");
        SpnParseToken nameTok = tokens.expectType(TokenType.TYPE_NAME);
        String name = nameTok.text();

        tokens.expect("(");
        LinkedHashMap<String, FieldType> declared = new LinkedHashMap<>();
        while (!tokens.check(")")) {
            SpnParseToken fieldTok = tokens.advance();
            String fieldName = fieldTok.text();
            tokens.expect(":");
            FieldType ft = parseFieldType();
            declared.put(fieldName, ft);
            recordFieldNode(name, fieldName, fieldTok);
            tokens.match(",");
        }
        tokens.expect(")");

        statefulTypes.add(name);
        statefulDeclaredShapes.put(name, declared);
        typeGraph.add(TypeGraph.Node.builder(name, TypeGraph.Kind.TYPE)
                .file(sourceName).nameRange(nameTok.range()).build());
    }

    private void parseTypeDecl() {
        SpnParseToken typeTok = tokens.expect("type");
        SpnParseToken nameTok = tokens.expectType(TokenType.TYPE_NAME);
        String name = nameTok.text();

        // type Name = macroCall<args>  OR  type Name = BaseType [where ...]
        if (tokens.match("=")) {
            // Check for macro call: type X = macroName<args>
            SpnParseToken rhsTok = tokens.peek();
            SpnParseToken rhsNext = tokens.peek(1);
            if (rhsTok != null && rhsNext != null
                    && macroRegistry.containsKey(rhsTok.text())
                    && rhsNext.text().equals("<")) {
                // Expand the macro — it will emit a type under its internal name.
                expandMacroInvocation();
                // Find the emitted type and re-register it under the user's chosen name.
                renameMacroEmittedType(name, nameTok);
                return;
            }

            // Plain type alias: type Name = BaseType [where validatorClosure]
            SpnTypeDescriptor.Builder builder = SpnTypeDescriptor.builder(name);
            String baseType = tokens.advance().text();
            builder.baseType(baseType);
            if (tokens.match("where")) {
                builder.validatorExpr(parseValidatorClosure());
            }
            SpnTypeDescriptor td = builder.build();
            typeRegistry.put(name, td);
            typeGraph.add(TypeGraph.Node.builder(name, TypeGraph.Kind.TYPE)
                    .file(sourceName).nameRange(nameTok.range())
                    .typeDescriptor(td).build());
            return;
        }

        // type Name where validatorClosure
        if (tokens.match("where")) {
            SpnTypeDescriptor.Builder builder = SpnTypeDescriptor.builder(name);
            builder.validatorExpr(parseValidatorClosure());
            if (tokens.match("with")) {
                String elemName = tokens.expectType(TokenType.TYPE_NAME).text();
                builder.element(new SpnDistinguishedElement(elemName));
            }
            SpnTypeDescriptor td = builder.build();
            typeRegistry.put(name, td);
            typeGraph.add(TypeGraph.Node.builder(name, TypeGraph.Kind.TYPE)
                    .file(sourceName).nameRange(nameTok.range())
                    .typeDescriptor(td).build());
            return;
        }

        // type Name(components...) [where validatorClosure]
        if (tokens.check("(")) {
            tokens.advance();
            SpnTypeDescriptor.Builder builder = SpnTypeDescriptor.builder(name);
            SpnStructDescriptor.Builder structBuilder = SpnStructDescriptor.builder(name);
            int position = 0;
            while (!tokens.check(")")) {
                SpnParseToken second = tokens.peek(1);
                if (second != null && second.text().equals(":")) {
                    SpnParseToken compTok = tokens.advance();
                    String compName = compTok.text();
                    tokens.expect(":");
                    FieldType ft = parseFieldType();
                    builder.component(compName, ft);
                    structBuilder.field(compName, ft);
                    recordFieldNode(name, compName, compTok);
                } else {
                    // Positional component: capture the type token's position so
                    // ctrl+click on `r.<index>` lands on the declared component.
                    SpnParseToken compTypeTok = tokens.peek();
                    FieldType ft = parseFieldType();
                    String compName = "_" + position;
                    builder.component(compName, ft);
                    structBuilder.field(compName, ft);
                    recordFieldNode(name, String.valueOf(position), compTypeTok);
                }
                position++;
                tokens.match(",");
            }
            tokens.expect(")");
            if (tokens.match("where")) {
                builder.validatorExpr(parseValidatorClosure());
            }
            SpnTypeDescriptor td = builder.build();
            SpnStructDescriptor sd = structBuilder.build();
            typeRegistry.put(name, td);
            structRegistry.put(name, sd);
            typeGraph.add(TypeGraph.Node.builder(name, TypeGraph.Kind.TYPE)
                    .file(sourceName).nameRange(nameTok.range())
                    .typeDescriptor(td).structDescriptor(sd).build());
            return;
        }

        // Bare opaque type: `type TypeName` — no fields declared.
        // Fields are defined via `let this.field = expr` in the constructor.
        // Creates an empty struct descriptor that gets populated by the factory.
        SpnStructDescriptor sd = SpnStructDescriptor.builder(name).build();
        structRegistry.put(name, sd);
        typeGraph.add(TypeGraph.Node.builder(name, TypeGraph.Kind.STRUCT)
                .file(sourceName).nameRange(nameTok.range())
                .structDescriptor(sd).build());
    }

    /**
     * Parse a validator closure: (params) { body }
     * Creates a function body node with its own scope. The closure takes
     * the type's value(s) as parameters and returns a truthy/falsy result.
     */
    private SpnExpressionNode parseValidatorClosure() {
        tokens.expect("(");
        List<String> paramNames = new ArrayList<>();
        while (!tokens.check(")")) {
            String paramName = tokens.advance().text();
            if (!paramName.equals("_")) {
                paramNames.add(paramName);
            } else {
                paramNames.add("_" + paramNames.size());
            }
            tokens.match(",");
        }
        tokens.expect(")");

        pushScope();
        int[] paramSlots = new int[paramNames.size()];
        for (int i = 0; i < paramNames.size(); i++) {
            paramSlots[i] = currentScope.addLocal(paramNames.get(i));
        }

        tokens.expect("{");
        SpnExpressionNode body = parseBlockBody();
        tokens.expect("}");

        FrameDescriptor frame = popScope().buildFrame();

        // Wrap body in a function root node for potential future evaluation
        SpnFunctionDescriptor.Builder descBuilder = SpnFunctionDescriptor.pure("__validator__");
        for (String pn : paramNames) {
            descBuilder.param(pn);
        }
        SpnFunctionDescriptor descriptor = descBuilder.build();

        SpnFunctionRootNode fnRoot = new SpnFunctionRootNode(
                language, frame, descriptor, paramSlots, body);
        return new SpnFunctionRefNode(fnRoot.getCallTarget());
    }

    /**
     * Parse: promote SourceType -> TargetType = (val) { conversion expr }
     * Registers a promotion rule for compile-time operator dispatch.
     */
    private void parsePromoteDecl() {
        SpnParseToken promoteTok = tokens.peek();
        tokens.expect("promote");
        FieldType sourceType = parseFieldType();
        tokens.expect("->");
        FieldType targetType = parseFieldType();
        tokens.expect("=");

        // Parse with type-aware closure so destructuring works on sourceType
        SpnExpressionNode converter = parsePromoteClosure(sourceType);

        // The converter is a SpnFunctionRefNode wrapping a CallTarget
        if (converter instanceof SpnFunctionRefNode ref) {
            promotionRegistry.add(new Promotion(
                    sourceType.describe(), targetType.describe(), ref.getCallTarget()));
            typeGraph.add(TypeGraph.Node.builder(
                    "promote " + sourceType.describe() + " -> " + targetType.describe(),
                    TypeGraph.Kind.PROMOTION)
                    .file(sourceName)
                    .nameRange(promoteTok != null ? promoteTok.range() : spn.source.SourceRange.UNKNOWN)
                    .callTarget(ref.getCallTarget())
                    .paramTypes(sourceType).returnType(targetType)
                    .build());
        }
    }

    /**
     * Parse a promote closure: (params) { body }
     * Like parseValidatorClosure but with type-aware parameter handling so
     * destructured bindings infer field types from the source type.
     */
    private SpnExpressionNode parsePromoteClosure(FieldType sourceType) {
        tokens.expect("(");
        List<ParamDecl> params = new ArrayList<>();
        while (!tokens.check(")")) {
            if (tokens.check("(")) {
                // Destructured param: (a, b) — fields of sourceType
                tokens.advance();
                List<String> bindings = new ArrayList<>();
                List<SpnParseToken> bindToks = new ArrayList<>();
                while (!tokens.check(")")) {
                    SpnParseToken t = tokens.advance();
                    bindings.add(t.text());
                    bindToks.add(t);
                    tokens.match(",");
                }
                tokens.expect(")");
                params.add(ParamDecl.destructured(bindings, bindToks));
            } else {
                params.add(ParamDecl.simple(tokens.advance()));
            }
            tokens.match(",");
        }
        tokens.expect(")");

        pushScope();

        // Promote takes exactly one parameter of sourceType
        List<FieldType> paramTypes = List.of(sourceType);
        List<String> paramNames = new ArrayList<>();
        int[] paramSlots = new int[params.size()];
        List<spn.node.local.SpnDestructureNode> destructureNodes = new ArrayList<>();

        for (int i = 0; i < params.size(); i++) {
            ParamDecl p = params.get(i);
            if (p.isDestructured()) {
                String hiddenName = "__param_" + i + "__";
                paramSlots[i] = currentScope.addLocal(hiddenName);
                paramNames.add(hiddenName);

                SpnStructDescriptor desc = null;
                if (i < paramTypes.size()) {
                    desc = structRegistry.get(paramTypes.get(i).describe());
                }

                int[] bindSlots = new int[p.destructureBindings().size()];
                for (int j = 0; j < p.destructureBindings().size(); j++) {
                    String bn = p.destructureBindings().get(j);
                    if (bn.equals("_")) {
                        bindSlots[j] = -1;
                    } else {
                        FieldType fieldType = (desc != null && j < desc.fieldCount())
                                ? desc.fieldType(j) : null;
                        bindSlots[j] = currentScope.addLocal(bn, fieldType);
                        if (p.destructureToks() != null && j < p.destructureToks().size()) {
                            recordLocalBinding(bn, p.destructureToks().get(j),
                                    TypeGraph.Kind.PARAMETER);
                        }
                    }
                }
                destructureNodes.add(new spn.node.local.SpnDestructureNode(
                        SpnReadLocalVariableNodeGen.create(paramSlots[i]), bindSlots));
            } else {
                FieldType ft = i < paramTypes.size() ? paramTypes.get(i) : null;
                paramSlots[i] = currentScope.addLocal(p.name(), ft);
                paramNames.add(p.name());
                recordLocalBinding(p.name(), p.nameTok(), TypeGraph.Kind.PARAMETER);
            }
        }

        tokens.expect("{");
        SpnExpressionNode body = parseBlockBody();
        tokens.expect("}");

        if (!destructureNodes.isEmpty()) {
            List<SpnStatementNode> stmts = new ArrayList<>(destructureNodes);
            stmts.add(body);
            body = new SpnBlockExprNode(stmts.toArray(new SpnStatementNode[0]));
        }

        FrameDescriptor frame = popScope().buildFrame();

        SpnFunctionDescriptor.Builder descBuilder = SpnFunctionDescriptor.pure("__promote__");
        for (int i = 0; i < paramNames.size(); i++) {
            FieldType ft = i < paramTypes.size() ? paramTypes.get(i) : FieldType.UNTYPED;
            descBuilder.param(paramNames.get(i), ft);
        }
        SpnFunctionDescriptor descriptor = descBuilder.build();

        SpnFunctionRootNode fnRoot = new SpnFunctionRootNode(
                language, frame, descriptor, paramSlots, body);
        return new SpnFunctionRefNode(fnRoot.getCallTarget());
    }

    private void parseDataDecl() {
        tokens.expect("data");
        SpnParseToken nameTok = tokens.expectType(TokenType.TYPE_NAME);
        String name = nameTok.text();
        tokens.match("="); // optional — data blocks can use just pipes

        // Data declarations group existing types into a tagged union.
        // All variant types must be defined before the data declaration.
        List<SpnStructDescriptor> variants = new ArrayList<>();
        do {
            tokens.match("|"); // optional leading pipe
            SpnParseToken variantTok = tokens.expectType(TokenType.TYPE_NAME);
            String variantName = variantTok.text();

            SpnStructDescriptor desc = structRegistry.get(variantName);
            if (desc == null) {
                throw tokens.error("Unknown type '" + variantName
                        + "' in data declaration. Define the type before referencing it in a data union.",
                        variantTok);
            }
            variants.add(desc);
        } while (tokens.match("|"));

        SpnVariantSet vs = new SpnVariantSet(name, variants.toArray(new SpnStructDescriptor[0]));
        variantRegistry.put(name, vs);
        typeGraph.add(TypeGraph.Node.builder(name, TypeGraph.Kind.VARIANT)
                .file(sourceName).nameRange(nameTok.range())
                .variantSet(vs).build());
    }

    /**
     * Parse: const TypeName.name = expr
     * Compiles the expression into a zero-arg function (thunk) stored in constantRegistry.
     * The value is computed once at module load time and the CallTarget is exportable.
     */
    private void parseConstDecl() {
        tokens.expect("const");
        SpnParseToken typeTok = tokens.expectType(TokenType.TYPE_NAME);
        String typeName = typeTok.text();
        tokens.expect(".");
        SpnParseToken constTok = tokens.expectType(TokenType.IDENTIFIER);
        String constName = constTok.text();
        tokens.expect("=");

        SpnExpressionNode value = parseExpression();
        FieldType inferredType = inferType(value);

        // Wrap in a zero-arg pure function so the value is exportable as a CallTarget
        String key = typeName + "." + constName;
        pushScope();
        Scope constScope = currentScope;
        popScope();

        SpnFunctionDescriptor descriptor = SpnFunctionDescriptor.pure(key).returns(
                inferredType != null ? inferredType : FieldType.UNTYPED).build();
        SpnFunctionRootNode fnRoot = new SpnFunctionRootNode(
                language, constScope.buildFrame(), descriptor, new int[0], value);
        CallTarget callTarget = fnRoot.getCallTarget();

        constantRegistry.put(key, new ConstantEntry(callTarget, inferredType));

        // Record for IDE go-to-def. Store under the composite "Type.name" key
        // so parsePrimary can match a ctrl+click on `Type.const` access.
        typeGraph.add(TypeGraph.Node.builder(key, TypeGraph.Kind.CONSTANT)
                .file(sourceName).nameRange(constTok.range())
                .returnType(inferredType).callTarget(callTarget).build());
    }

    /** Parse 'struct' as an alias for 'type' — both produce the same result. */
    private void parseStructAsType() {
        // Consume 'struct', then reuse type parsing logic by
        // manually handling the product type form
        tokens.expect("struct");
        String name = tokens.expectType(TokenType.TYPE_NAME).text();

        // optional type params <T, U>
        List<String> typeParams = new ArrayList<>();
        if (tokens.match("<")) {
            do { typeParams.add(tokens.advance().text()); } while (tokens.match(","));
            tokens.expect(">");
        }

        tokens.expect("(");
        SpnTypeDescriptor.Builder typeBuilder = SpnTypeDescriptor.builder(name);
        SpnStructDescriptor.Builder structBuilder = SpnStructDescriptor.builder(name);
        for (String tp : typeParams) structBuilder.typeParam(tp);

        while (!tokens.check(")")) {
            SpnParseToken fieldTok = tokens.expectType(TokenType.IDENTIFIER);
            String fieldName = fieldTok.text();
            if (tokens.match(":")) {
                FieldType ft = parseFieldType();
                typeBuilder.component(fieldName, ft);
                structBuilder.field(fieldName, ft);
            } else {
                typeBuilder.component(fieldName, FieldType.UNTYPED);
                structBuilder.field(fieldName);
            }
            recordFieldNode(name, fieldName, fieldTok);
            tokens.match(",");
        }
        tokens.expect(")");

        if (tokens.match("where")) {
            typeBuilder.validatorExpr(parseValidatorClosure());
        }

        typeRegistry.put(name, typeBuilder.build());
        structRegistry.put(name, structBuilder.build());
    }

    // ── Function declarations and definitions ──────────────────────────────

    private SpnStatementNode parseFuncDecl(boolean isPure) {
        tokens.advance(); // consume 'pure' or 'action'
        SpnParseToken nameTok = tokens.peek();
        if (nameTok == null) throw tokens.error("Expected function name after declaration keyword");

        // Dispatch by declaration form
        if (nameTok.type() == TokenType.TYPE_NAME && tokens.peek(1) != null) {
            String next = tokens.peek(1).text();
            if (next.equals(".") || next.equals("[")) return parseMethodDecl(isPure, nameTok);
            if (next.equals("(")) return parseFactoryDecl(isPure, nameTok);
        }
        if (nameTok.type() == TokenType.OPERATOR) return parseOperatorOrFuncDecl(isPure, true);
        return parseOperatorOrFuncDecl(isPure, false);
    }

    /**
     * Parse: pure TypeName.method(args) -> ReturnType = (params) { body }
     *    or: pure TypeName.@qualified.key(args) -> ReturnType = (params) { body }
     *    or: pure TypeName[](args) -> ReturnType = (params) { body }
     *
     * <p>The qualified-key form implements a globally-named dispatch slot on
     * the type. Stored in the method registry under {@code TypeName.@fqn} so
     * lookup is uniform with regular methods.
     *
     * <p>The {@code []} form attaches a subscript method to the type.
     * Registered under {@code TypeName.[]} — same registry, dispatched by
     * the subscript expression {@code arr[i]} in parsePostfix when the
     * receiver is not a raw array.
     */
    private SpnStatementNode parseMethodDecl(boolean isPure, SpnParseToken nameTok) {
        String typeName = tokens.advance().text(); // consume TypeName
        String methodName;
        SpnParseToken methodTok;
        if (tokens.check("[")) {
            methodTok = tokens.advance(); // '['
            tokens.expect("]");
            methodName = "[]";
        } else {
            tokens.advance(); // consume '.'
            methodTok = tokens.peek();
            if (methodTok != null && methodTok.type() == TokenType.QUALIFIED_KEY) {
                methodName = tokens.advance().text(); // keep the @ prefix in the name
            } else {
                methodName = expectIdentifier().text();
            }
        }

        List<FieldType> paramTypes = parseParamTypeList();
        FieldType returnType = parseOptionalReturnType();

        // Prepend receiver type
        FieldType receiverType = resolveTypeByName(typeName);
        if (receiverType == null) throw tokens.error("Unknown type for method: " + typeName, nameTok);
        paramTypes.add(0, receiverType);

        String methodKey = typeName + "." + methodName;

        // Declaration only (no =), just register the signature
        if (!tokens.check("=")) {
            rejectOrphanBody(nameTok, "method '" + methodKey + "'");
            SpnFunctionDescriptor.Builder db = isPure
                    ? SpnFunctionDescriptor.pure(methodKey) : SpnFunctionDescriptor.impure(methodKey);
            db.param("this", paramTypes.get(0));
            for (int i = 1; i < paramTypes.size(); i++) db.param("_" + (i - 1), paramTypes.get(i));
            db.returns(returnType);
            functionDescriptorRegistry.put(methodKey, db.build());
            return null;
        }

        tokens.expect("=");
        String outerMethodType = currentMethodTypeName;
        currentMethodTypeName = typeName;
        parseFunctionBody(methodKey, paramTypes, returnType, isPure, true, methodTok);
        currentMethodTypeName = outerMethodType;
        return null;
    }

    /** Parse: pure TypeName(args) -> ReturnType = (params) { body } */
    private SpnStatementNode parseFactoryDecl(boolean isPure, SpnParseToken nameTok) {
        String typeName = tokens.advance().text(); // consume TypeName

        List<FieldType> paramTypes = parseParamTypeList();
        FieldType returnType = parseOptionalReturnType();

        if (!tokens.check("=")) {
            rejectOrphanBody(nameTok, "factory '" + typeName + "'");
            return null;
        }
        tokens.expect("=");

        // Use arity-qualified name to prevent collisions between overloaded factories
        String qualifiedName = typeName + "/" + paramTypes.size();

        // Set factory context so this(args) resolves to raw construction
        String outerFactory = currentFactoryTypeName;
        int outerArity = currentFactoryArity;
        currentFactoryTypeName = typeName;
        currentFactoryArity = paramTypes.size();

        parseFunctionBody(qualifiedName, paramTypes, returnType, isPure, false, nameTok);

        currentFactoryTypeName = outerFactory;
        currentFactoryArity = outerArity;

        // Move from functionRegistry to factoryRegistry
        CallTarget ct = functionRegistry.get(qualifiedName);
        SpnFunctionDescriptor desc = functionDescriptorRegistry.get(qualifiedName);
        if (ct != null && desc != null) {
            factoryRegistry.computeIfAbsent(typeName, k -> new ArrayList<>())
                    .add(new FactoryEntry(ct, paramTypes.size(), desc));
            functionRegistry.remove(qualifiedName);
        }
        return null;
    }

    /** Parse a regular function or operator overload declaration. */
    private SpnStatementNode parseOperatorOrFuncDecl(boolean isPure, boolean isOperator) {
        SpnParseToken declTok = tokens.peek();
        String name = isOperator ? tokens.advance().text()
                : expectIdentifier().text();

        List<FieldType> paramTypes = parseParamTypeList();
        FieldType returnType = parseOptionalReturnType();

        if (!tokens.check("=")) {
            rejectOrphanBody(declTok, (isOperator ? "operator '" : "function '") + name + "'");
            return null;
        }
        tokens.expect("=");

        parseFunctionBody(name, paramTypes, returnType, isPure, false, declTok);

        // Register operator overload + enforce the unary/binary mutex.
        if (isOperator && !paramTypes.isEmpty()) {
            CallTarget ct = functionRegistry.get(name);
            if (ct != null) {
                FieldType firstType = paramTypes.get(0);
                int newArity = paramTypes.size();
                List<OperatorOverload> existing = operatorRegistry.get(name);
                if (existing != null) {
                    for (OperatorOverload ov : existing) {
                        if (ov.paramTypes().length != newArity
                                && ov.paramTypes().length > 0
                                && typesMatch(ov.paramTypes()[0], firstType)) {
                            String other = ov.paramTypes().length == 1 ? "unary" : "binary";
                            String self  = newArity == 1 ? "unary" : "binary";
                            throw tokens.error("Cannot define both " + other + " and " + self
                                    + " '" + name + "' for " + firstType.describe()
                                    + " — pick one; use a .neg()/.inv() method for the other form");
                        }
                    }
                }
                FieldType[] pts = paramTypes.toArray(new FieldType[0]);
                operatorRegistry.computeIfAbsent(name, k -> new ArrayList<>())
                        .add(new OperatorOverload(pts, returnType, ct));
                typeGraph.add(TypeGraph.Node.builder(name, TypeGraph.Kind.OPERATOR)
                        .file(sourceName).nameRange(declTok.range())
                        .callTarget(ct)
                        .paramTypes(pts).returnType(returnType).pure(isPure)
                        .build());
            }
        }

        return null;
    }

    /** Shared: parse (Type, Type, ...) parameter type list. */
    private List<FieldType> parseParamTypeList() {
        tokens.expect("(");
        List<FieldType> paramTypes = new ArrayList<>();
        while (!tokens.check(")")) {
            paramTypes.add(parseFieldType());
            tokens.match(",");
        }
        tokens.expect(")");
        return paramTypes;
    }

    /** Shared: parse optional -> ReturnType. */
    private FieldType parseOptionalReturnType() {
        if (tokens.match("->")) return parseFieldType();
        return FieldType.UNTYPED;
    }

    /**
     * Called on declaration-only paths (no {@code =} before the body). If the
     * next token is {@code {}, the user almost certainly forgot the {@code =}
     * before a lambda body — error with a helpful message rather than silently
     * treating the block as an unrelated anonymous expression.
     */
    private void rejectOrphanBody(SpnParseToken declTok, String declName) {
        if (tokens.check("{")) {
            throw tokens.error("Expected '=' before body for " + declName
                    + ". Use '= (params) { ... }' for an implementation, or remove the '{ ... }'"
                    + " for a signature-only declaration.", declTok);
        }
    }

    /** A parameter binding: either a simple name or a positional destructure
     *  (a, b, c). Tokens are retained so TypeGraph nodes can record the name
     *  position; they may be null for synthesized params ({@code this}). */
    private record ParamDecl(
            String name, SpnParseToken nameTok,
            List<String> destructureBindings, List<SpnParseToken> destructureToks) {
        boolean isDestructured() { return destructureBindings != null; }
        static ParamDecl simple(String name) { return new ParamDecl(name, null, null, null); }
        static ParamDecl simple(SpnParseToken tok) {
            return new ParamDecl(tok.text(), tok, null, null);
        }
        static ParamDecl destructured(List<String> bindings, List<SpnParseToken> toks) {
            return new ParamDecl(null, null, bindings, toks);
        }
    }

    private SpnStatementNode parseFunctionBody(String name, List<FieldType> paramTypes,
                                                FieldType returnType, boolean isPure) {
        return parseFunctionBody(name, paramTypes, returnType, isPure, false, null);
    }

    private SpnStatementNode parseFunctionBody(String name, List<FieldType> paramTypes,
                                                FieldType returnType, boolean isPure, boolean isMethod) {
        return parseFunctionBody(name, paramTypes, returnType, isPure, isMethod, null);
    }

    /**
     * @param nameTok position of the function's name identifier. When non-null,
     *   the TypeGraph node records the name's line/col so go-to-def lands on
     *   the declaration name itself rather than the opening brace of the body.
     */
    private SpnStatementNode parseFunctionBody(String name, List<FieldType> paramTypes,
                                                FieldType returnType, boolean isPure, boolean isMethod,
                                                SpnParseToken nameTok) {
        tokens.expect("(");
        List<ParamDecl> params = new ArrayList<>();

        // For methods, add implicit 'this' as the first parameter (receiver)
        if (isMethod) {
            params.add(ParamDecl.simple("this"));
        }

        while (!tokens.check(")")) {
            if (tokens.check("(")) {
                // Destructured param: (a, b, c) — positional, type known from signature
                tokens.advance();
                List<String> bindings = new ArrayList<>();
                List<SpnParseToken> bindToks = new ArrayList<>();
                while (!tokens.check(")")) {
                    SpnParseToken t = tokens.advance();
                    bindings.add(t.text());
                    bindToks.add(t);
                    tokens.match(",");
                }
                tokens.expect(")");
                params.add(ParamDecl.destructured(bindings, bindToks));
            } else {
                // Simple param: name
                params.add(ParamDecl.simple(tokens.expectType(TokenType.IDENTIFIER)));
            }
            tokens.match(",");
        }
        tokens.expect(")");

        // Parse body in new scope
        pushScope();

        // Allocate param slots — destructured params get a hidden slot, then field bindings
        List<String> paramNames = new ArrayList<>();
        int[] userParamSlots = new int[params.size()];
        List<spn.node.local.SpnDestructureNode> destructureNodes = new ArrayList<>();

        for (int i = 0; i < params.size(); i++) {
            ParamDecl p = params.get(i);
            if (p.isDestructured()) {
                // Hidden param slot receives the whole struct/product value
                String hiddenName = "__param_" + i + "__";
                userParamSlots[i] = currentScope.addLocal(hiddenName);
                paramNames.add(hiddenName);

                // Look up struct descriptor from the type signature for field type inference
                SpnStructDescriptor desc = null;
                if (i < paramTypes.size()) {
                    FieldType ft = paramTypes.get(i);
                    String typeName = ft.describe();
                    desc = structRegistry.get(typeName);
                }

                // Allocate binding slots for each destructured field
                int[] bindSlots = new int[p.destructureBindings().size()];
                for (int j = 0; j < p.destructureBindings().size(); j++) {
                    String bn = p.destructureBindings().get(j);
                    if (bn.equals("_")) {
                        bindSlots[j] = -1;
                    } else {
                        FieldType fieldType = (desc != null && j < desc.fieldCount())
                                ? desc.fieldType(j) : null;
                        bindSlots[j] = currentScope.addLocal(bn, fieldType);
                        if (p.destructureToks() != null && j < p.destructureToks().size()) {
                            recordLocalBinding(bn, p.destructureToks().get(j),
                                    TypeGraph.Kind.PARAMETER);
                        }
                    }
                }
                destructureNodes.add(new spn.node.local.SpnDestructureNode(
                        SpnReadLocalVariableNodeGen.create(userParamSlots[i]), bindSlots));
            } else {
                FieldType ft = i < paramTypes.size() ? paramTypes.get(i) : null;
                userParamSlots[i] = currentScope.addLocal(p.name(), ft);
                paramNames.add(p.name());
                recordLocalBinding(p.name(), p.nameTok(), TypeGraph.Kind.PARAMETER);
            }
        }
        // Yield context slot — always allocated in frame, used by yield nodes
        int yieldCtxSlot = currentScope.addLocal("__yieldCtx__");

        Scope fnScope = currentScope;

        // Parse body — function name is visible for recursion via deferred lookup
        deferredFunctionName = name;
        deferredReturnType = returnType;
        containsYield = false;
        boolean outerPurity = currentFunctionIsPure;
        currentFunctionIsPure = isPure;
        tokens.expect("{");
        SpnParseToken bodyTok = tokens.lastConsumed();
        SpnExpressionNode body = parseBlockBody();
        tokens.expect("}");
        deferredFunctionName = null;
        deferredReturnType = null;
        currentFunctionIsPure = outerPurity;
        boolean isProducer = containsYield;

        // Prepend destructure nodes to the body if any params were destructured
        if (!destructureNodes.isEmpty()) {
            List<SpnStatementNode> stmts = new ArrayList<>(destructureNodes);
            stmts.add(body);
            body = new SpnBlockExprNode(stmts.toArray(new SpnStatementNode[0]));
        }

        popScope();

        // Build the function descriptor — include yield ctx param only for producers
        SpnFunctionDescriptor.Builder descBuilder = isPure
                ? SpnFunctionDescriptor.pure(name)
                : SpnFunctionDescriptor.impure(name);
        for (int i = 0; i < paramNames.size(); i++) {
            FieldType ft = i < paramTypes.size() ? paramTypes.get(i) : FieldType.UNTYPED;
            descBuilder.param(paramNames.get(i), ft);
        }
        if (isProducer) {
            descBuilder.param("__yieldCtx__");
        }
        descBuilder.returns(returnType);
        SpnFunctionDescriptor descriptor = descBuilder.build();

        // paramSlots must match descriptor param count
        int[] paramSlots;
        if (isProducer) {
            paramSlots = new int[userParamSlots.length + 1];
            System.arraycopy(userParamSlots, 0, paramSlots, 0, userParamSlots.length);
            paramSlots[userParamSlots.length] = yieldCtxSlot;
        } else {
            paramSlots = userParamSlots;
        }

        SpnFunctionRootNode fnRoot = new SpnFunctionRootNode(
                language, fnScope.buildFrame(), descriptor, paramSlots, body);
        if (bodyTok != null) {
            fnRoot.setSourcePosition(sourceName, bodyTok.line(), bodyTok.col());
        }
        CallTarget callTarget = fnRoot.getCallTarget();
        if (isMethod) {
            methodRegistry.put(name, new MethodEntry(callTarget, descriptor));
        } else {
            // If a function with this name already exists, promote BOTH to
            // the overload registry for type-dispatched multiple dispatch.
            CallTarget existing = functionRegistry.get(name);
            if (existing != null) {
                List<OperatorOverload> overloads = functionOverloads.computeIfAbsent(
                        name, k -> new ArrayList<>());
                // Add the existing one if this is the first collision
                if (overloads.isEmpty()) {
                    SpnFunctionDescriptor existingDesc = functionDescriptorRegistry.get(name);
                    if (existingDesc != null) {
                        FieldDescriptor[] ep = existingDesc.getParams();
                        FieldType[] existingPts = new FieldType[ep.length];
                        for (int i = 0; i < ep.length; i++) existingPts[i] = ep[i].type();
                        overloads.add(new OperatorOverload(existingPts,
                                existingDesc.getReturnType(), existing));
                    }
                }
                // Add the new one
                overloads.add(new OperatorOverload(
                        paramTypes.toArray(new FieldType[0]), returnType, callTarget));
            }
            functionRegistry.put(name, callTarget);
        }
        functionDescriptorRegistry.put(name, descriptor);

        // Record in TypeGraph. The name token is the canonical go-to-def
        // landing point; bodyTok is a last-resort fallback if no caller
        // captured a name (shouldn't happen for well-formed declarations).
        TypeGraph.Kind gKind = isMethod ? TypeGraph.Kind.METHOD : TypeGraph.Kind.FUNCTION;
        SpnParseToken posTok = nameTok != null ? nameTok : bodyTok;
        typeGraph.add(TypeGraph.Node.builder(name, gKind)
                .file(sourceName).pure(isPure).callTarget(callTarget)
                .functionDescriptor(descriptor).returnType(returnType)
                .paramTypes(paramTypes.toArray(new FieldType[0]))
                .nameRange(posTok != null ? posTok.range() : spn.source.SourceRange.UNKNOWN)
                .build());

        // Patch any deferred self-calls
        patchDeferredCalls(name, callTarget);

        // Return null — function definitions don't produce a runtime statement
        return null;
    }

    // Support for recursive function references, yield detection, purity tracking, and factories
    private String deferredFunctionName;
    private FieldType deferredReturnType; // return type of the function being defined (for self-call type tracking)
    private boolean containsYield;
    private boolean currentFunctionIsPure;
    private String currentFactoryTypeName; // non-null when inside a factory body
    private String currentMethodTypeName;  // non-null when inside a method body (for private field access)
    private int currentFactoryArity;      // arity of the factory being parsed
    private final List<SpnDeferredInvokeNode> deferredCalls = new ArrayList<>();

    // Stateful types (`stateful type T(fields)`) — heap-allocated, mutable,
    // block-scoped instances. Declared fields are stored per type; the
    // currently-open stateful block (if any) tracks the in-scope `this`
    // slot and the growing shape for ad-hoc field inference.
    private final Set<String> statefulTypes = new HashSet<>();
    private final Map<String, LinkedHashMap<String, FieldType>> statefulDeclaredShapes = new LinkedHashMap<>();
    private StatefulBlockContext currentStatefulBlock;

    private static final class StatefulBlockContext {
        final String typeName;
        final LinkedHashMap<String, FieldType> shape;
        final int thisSlot;
        boolean inDoBody;

        StatefulBlockContext(String typeName, LinkedHashMap<String, FieldType> shape, int thisSlot) {
            this.typeName = typeName;
            this.shape = shape;
            this.thisSlot = thisSlot;
            this.inDoBody = false;
        }
    }

    private void patchDeferredCalls(String name, CallTarget target) {
        var it = deferredCalls.iterator();
        while (it.hasNext()) {
            SpnDeferredInvokeNode node = it.next();
            if (node.name.equals(name)) {
                node.resolve(target);
                it.remove();
            }
        }
    }

    // ── Private instance fields ────────────────────────────────────────────

    /**
     * Parse: {@code let this.fieldName = expr} inside a constructor body.
     *
     * <p>Adds a PRIVATE field to the type's struct descriptor. The field is
     * accessible from methods on the same type (via {@code this.fieldName})
     * but not from external code (compile error).
     *
     * <p>At runtime, the factory body collects all {@code this.field}
     * assignments. The final {@code this(...)} raw-construction call then
     * includes these values positionally. For the MVP, each
     * {@code let this.field = expr} is compiled as a local variable; the
     * factory must end with {@code this(field1, field2, ...)} to construct
     * the struct with the collected values.
     */
    private SpnStatementNode parseLetThisField() {
        tokens.advance(); // consume 'this'
        tokens.advance(); // consume '.'
        SpnParseToken fieldTok = expectIdentifier();
        String fieldName = fieldTok.text();
        tokens.expect("=");
        SpnExpressionNode value = parseExpression();

        FieldType inferredType = inferType(value);

        // Add the private field to the existing struct descriptor (mutate in place
        // so all references — including the factory's return type — stay consistent).
        SpnStructDescriptor desc = structRegistry.get(currentFactoryTypeName);
        if (desc != null) {
            if (desc.fieldIndex(fieldName) >= 0) {
                throw tokens.error("Duplicate private field '" + fieldName + "' on " + currentFactoryTypeName, fieldTok);
            }
            desc.addPrivateField(fieldName, inferredType != null ? inferredType : FieldType.UNTYPED);
        }

        // Compile as a regular local variable — the factory will use it
        // in the final this(...) construction.
        int slot = currentScope.addLocal(fieldName, inferredType);
        var writeNode = SpnWriteLocalVariableNodeGen.create(value, slot);
        writeNode.setVariableName(fieldName);
        return writeNode;
    }

    // ── Let bindings ───────────────────────────────────────────────────────

    private SpnStatementNode parseLetBinding() {
        tokens.expect("let");

        // Check for destructuring: let TypeName(a, b, c) = expr
        SpnParseToken first = tokens.peek();
        if (first == null) throw tokens.error("Expected variable name after 'let'");
        if (first.type() == TokenType.TYPE_NAME && tokens.peek(1) != null
                && tokens.peek(1).text().equals("(")) {
            return parseLetDestructure();
        }

        // Check for private instance field: let this.field = expr (inside a factory/constructor)
        if (first.text().equals("this") && tokens.peek(1) != null
                && tokens.peek(1).text().equals(".") && currentFactoryTypeName != null) {
            return parseLetThisField();
        }

        // Check for tuple/positional destructuring: let (a, b) = expr
        if (first.text().equals("(")) {
            return parseLetTupleDestructure();
        }

        // Check for associated constant: let TypeName.name = expr
        if (first.type() == TokenType.TYPE_NAME && tokens.peek(1) != null
                && tokens.peek(1).text().equals(".")) {
            String typeName = tokens.advance().text(); // consume TypeName
            tokens.advance(); // consume '.'
            String constName = tokens.expectType(TokenType.IDENTIFIER).text();
            tokens.expect("=");
            SpnExpressionNode value = parseExpression();
            String key = typeName + "." + constName;
            FieldType inferredType = inferType(value);
            int slot = currentScope.addLocal(key, inferredType);
            typeConstantSlots.put(key, slot);
            if (inferredType != null) typeConstantTypes.put(key, inferredType);
            return SpnWriteLocalVariableNodeGen.create(value, slot);
        }

        SpnParseToken nameTok = expectIdentifier();
        String name = nameTok.text();

        // Optional type annotation
        FieldType annotatedType = null;
        if (tokens.match(":")) {
            annotatedType = parseFieldType();
        }

        tokens.expect("=");
        SpnExpressionNode value = parseExpression();

        // Infer the type for compile-time operator dispatch
        FieldType inferredType = annotatedType != null ? annotatedType : inferType(value);
        int slot = currentScope.addLocal(name, inferredType);
        recordLocalBinding(name, nameTok, TypeGraph.Kind.LOCAL_BINDING);
        var writeNode = SpnWriteLocalVariableNodeGen.create(value, slot);
        writeNode.setVariableName(name);
        return writeNode;
    }

    /** Emit a TypeGraph node for a local binding or parameter. Exposes the
     *  name's source position so go-to-def can land on it without text
     *  scanning. Only records when a nameTok is available (internal bindings
     *  like {@code __yieldCtx__} and {@code __param_0__} are skipped). */
    private void recordLocalBinding(String name, SpnParseToken nameTok, TypeGraph.Kind kind) {
        if (nameTok == null || name == null || name.startsWith("__")) return;
        typeGraph.add(TypeGraph.Node.builder(name, kind)
                .file(sourceName)
                .nameRange(nameTok.range())
                .build());
    }

    /** Emit a TypeGraph FIELD node for a struct component. Stored with the
     *  composite name {@code TypeName.fieldName} so field-access dispatches
     *  can look it up unambiguously across types. */
    private void recordFieldNode(String typeName, String fieldName, SpnParseToken nameTok) {
        if (nameTok == null || typeName == null || fieldName == null) return;
        typeGraph.add(TypeGraph.Node.builder(typeName + "." + fieldName, TypeGraph.Kind.FIELD)
                .file(sourceName)
                .nameRange(nameTok.range())
                .build());
    }

    /**
     * Annotations for field-access sites like {@code state.counter}. Parser
     * populates this as it resolves accesses; {@link IncrementalParser} merges
     * them into the final {@link IncrementalParser.DispatchAnnotation} list so
     * the editor can go-to-def on field names.
     *
     * <p>Raw form (not yet converted to editor coords): accessRange is the
     * field-name token's range in this file, targetRange is the field
     * declaration's nameRange (both in parser convention).
     */
    record FieldAccessSite(spn.source.SourceRange accessRange, String description,
                           String targetFile, spn.source.SourceRange targetRange) {}

    private final List<FieldAccessSite> fieldAccessSites = new ArrayList<>();

    public List<FieldAccessSite> getFieldAccessSites() {
        return fieldAccessSites;
    }

    /** Record a field access for IDE go-to-def. Called from the field-access
     *  parser once the owner type and field are resolved. No-op if the owner
     *  type has no FIELD node in the TypeGraph (e.g., tuples, opaque types). */
    private void recordFieldAccess(SpnParseToken nameTok, FieldType receiverType, String fieldName) {
        if (nameTok == null || receiverType == null || fieldName == null) return;
        String typeName = resolver.resolveTypeName(receiverType);
        if (typeName == null) return;
        String key = typeName + "." + fieldName;
        // Same-module lookup first; fall back to imported map for fields
        // whose declaring type came from another module's source.
        String targetFile = null;
        spn.source.SourceRange targetRange = null;
        TypeGraph.Node decl = typeGraph.lookupFirst(key);
        if (decl != null && decl.nameRange().isKnown() && decl.file() != null) {
            targetFile = decl.file();
            targetRange = decl.nameRange();
        } else {
            spn.language.TypeDeclPos pos = importedFieldDeclarations.get(key);
            if (pos != null && pos.range() != null && pos.range().isKnown()) {
                targetFile = pos.file();
                targetRange = pos.range();
            }
        }
        if (targetFile == null || targetRange == null) return;
        fieldAccessSites.add(new FieldAccessSite(
                nameTok.range(), key, targetFile, targetRange));
    }

    /**
     * A type-use site in the current file, resolved for IDE go-to-def.
     * Emitted by {@link TypeParser} for every named type reference in
     * signatures, let ascriptions, struct field types, composite-type
     * element types, and variant payloads.
     *
     * <p>{@code useSite} is the type-name token's range in this file.
     * {@code targetFile}/{@code targetRange} point at the declaration when
     * the type was declared in a file the parser saw (locally or — in
     * future — in another module's source). {@code importRange} points at
     * the {@code import} keyword of the import statement that brought the
     * name into scope, when applicable. Any field may be null; the editor
     * prefers {@code targetRange} over {@code importRange}.
     *
     * <p>Raw form (parser convention); converted to editor coords by
     * {@link IncrementalParser} at the parser/UI boundary.
     */
    record TypeReferenceSite(spn.source.SourceRange useSite, String typeName,
                             String targetFile, spn.source.SourceRange targetRange,
                             spn.source.SourceRange importRange) {}

    private final List<TypeReferenceSite> typeReferenceSites = new ArrayList<>();

    /** Maps a name brought into scope by an import to the SourceRange of
     *  that import's keyword. Populated in {@link #parseImportDecl()} by
     *  snapshotting the type/struct/variant registries before/after each
     *  directive applies. First import wins when a name is imported twice. */
    private final Map<String, spn.source.SourceRange> importedTypeRanges = new LinkedHashMap<>();

    /** Maps a name brought into scope by an import to the declaration's
     *  source position in the defining module. Populated by
     *  {@link #applyFullImport} and {@link #applySelectiveImport} from the
     *  module's {@code typeDeclarations} extra, so go-to-def on an imported
     *  type can open the defining file instead of just the import line. */
    private final Map<String, spn.language.TypeDeclPos> importedTypeDeclarations = new LinkedHashMap<>();

    /** Maps an imported factory's CallTarget to the factory declaration's
     *  source position. Factories are overloaded (same type name, different
     *  signatures) so CallTarget identity is the disambiguator. Populated
     *  from the module's {@code factoryDeclarations} extra. */
    private final java.util.IdentityHashMap<CallTarget, spn.language.TypeDeclPos>
            importedFactoryDeclarations = new java.util.IdentityHashMap<>();

    /** Maps an imported constant's composite name (e.g. "Rational.zero") to
     *  the declaration's source position. Populated from the module's
     *  {@code constantDeclarations} extra. */
    private final Map<String, spn.language.TypeDeclPos>
            importedConstantDeclarations = new LinkedHashMap<>();

    /** Maps an imported method's CallTarget to the method declaration's
     *  source position. Populated from the module's {@code methodDeclarations}
     *  extra. CallTarget identity is the disambiguator across overloads. */
    private final java.util.IdentityHashMap<CallTarget, spn.language.TypeDeclPos>
            importedMethodDeclarations = new java.util.IdentityHashMap<>();

    /** Maps an imported field's composite name (e.g. "Rational.num" or
     *  "Rational.0" for positional) to its declaration source position.
     *  Populated from the module's {@code fieldDeclarations} extra. */
    private final Map<String, spn.language.TypeDeclPos>
            importedFieldDeclarations = new LinkedHashMap<>();

    /** Maps an imported operator overload's CallTarget to its source position.
     *  Populated from the module's {@code operatorDeclarations} extra; used by
     *  {@link IncrementalParser#extractDispatches} as a fallback when
     *  {@link TypeGraph#byCallTarget} misses for a cross-module operator. */
    private final java.util.IdentityHashMap<CallTarget, spn.language.TypeDeclPos>
            importedOperatorDeclarations = new java.util.IdentityHashMap<>();

    public List<TypeReferenceSite> getTypeReferenceSites() {
        return typeReferenceSites;
    }

    /** Record a factory call use site for IDE go-to-def. Called from
     *  {@link #parseTypeConstructor} once overload resolution has picked a
     *  specific {@link FactoryEntry} — the {@code nameTok} is the TypeName
     *  token at the call site, and {@code fe.callTarget()} identifies the
     *  chosen overload. Navigation lands on that factory's declaration, not
     *  the type declaration, because a ctrl+click on a constructor call
     *  reads as "where is this constructor?". */
    private void recordFactoryCall(SpnParseToken nameTok, String typeName, FactoryEntry fe) {
        if (nameTok == null || fe == null || fe.callTarget() == null) return;
        String targetFile = null;
        spn.source.SourceRange targetRange = null;
        // Same-module: the factory lives in this file's TypeGraph, keyed by
        // its CallTarget. Cross-module: consult the imported map populated
        // by applyFullImport/applySelectiveImport.
        TypeGraph.Node decl = typeGraph.byCallTarget(fe.callTarget());
        if (decl != null && decl.file() != null && decl.nameRange().isKnown()) {
            targetFile = decl.file();
            targetRange = decl.nameRange();
        } else {
            spn.language.TypeDeclPos pos = importedFactoryDeclarations.get(fe.callTarget());
            if (pos != null && pos.range() != null && pos.range().isKnown()) {
                targetFile = pos.file();
                targetRange = pos.range();
            }
        }
        spn.source.SourceRange importRange = importedTypeRanges.get(typeName);
        if (targetFile == null && targetRange == null && importRange == null) return;
        typeReferenceSites.add(new TypeReferenceSite(
                nameTok.range(), typeName, targetFile, targetRange, importRange));
    }

    /** Record an instance method call ({@code expr.method(args)}) as a use
     *  site over the method-name token. Called from {@link #parsePostfix}
     *  once {@code resolveMethod} has picked the {@link MethodEntry}. The
     *  receiver type tells us which owning type's import statement to use
     *  for the cross-module fallback. */
    private void recordMethodCall(SpnParseToken methodNameTok, FieldType receiverType,
                                  String methodName, MethodEntry method) {
        if (methodNameTok == null || method == null || method.callTarget() == null) return;
        String targetFile = null;
        spn.source.SourceRange targetRange = null;
        TypeGraph.Node decl = typeGraph.byCallTarget(method.callTarget());
        if (decl != null && decl.file() != null && decl.nameRange().isKnown()) {
            targetFile = decl.file();
            targetRange = decl.nameRange();
        } else {
            spn.language.TypeDeclPos pos = importedMethodDeclarations.get(method.callTarget());
            if (pos != null && pos.range() != null && pos.range().isKnown()) {
                targetFile = pos.file();
                targetRange = pos.range();
            }
        }
        // For the import-line fallback, attribute the method to its owning
        // type's import statement (methods don't have their own import).
        spn.source.SourceRange importRange = null;
        String typeName = resolver.resolveTypeName(receiverType);
        if (typeName != null) importRange = importedTypeRanges.get(typeName);
        if (targetFile == null && targetRange == null && importRange == null) return;
        String key = (typeName != null ? typeName + "." : "") + methodName;
        typeReferenceSites.add(new TypeReferenceSite(
                methodNameTok.range(), key, targetFile, targetRange, importRange));
    }

    /** Record a qualified constant access ({@code Type.name}) as two separate
     *  use sites — one over the type token (navigates to the type declaration)
     *  and one over the constant name token (navigates to the constant
     *  declaration). Called from {@link #parsePrimary} once a ConstantEntry
     *  has been resolved. */
    private void recordConstantAccess(SpnParseToken typeTok, String typeName,
                                      SpnParseToken constTok, String constName) {
        // (1) the type token: reuse the type-reference machinery.
        recordTypeReference(typeTok, typeName);

        // (2) the const name token: locate its declaration position.
        if (constTok == null) return;
        String key = typeName + "." + constName;
        String targetFile = null;
        spn.source.SourceRange targetRange = null;
        TypeGraph.Node decl = typeGraph.lookupFirst(key);
        if (decl != null && decl.file() != null && decl.nameRange().isKnown()) {
            targetFile = decl.file();
            targetRange = decl.nameRange();
        } else {
            spn.language.TypeDeclPos pos = importedConstantDeclarations.get(key);
            if (pos != null && pos.range() != null && pos.range().isKnown()) {
                targetFile = pos.file();
                targetRange = pos.range();
            }
        }
        // An imported constant has no dedicated import statement, so fall
        // back to the import that brought in its owning type.
        spn.source.SourceRange importRange = importedTypeRanges.get(typeName);
        if (targetFile == null && targetRange == null && importRange == null) return;
        typeReferenceSites.add(new TypeReferenceSite(
                constTok.range(), key, targetFile, targetRange, importRange));
    }

    /** Record a type use site for IDE go-to-def. Called from {@link TypeParser}
     *  when a named type token resolves to a known type. Skips unresolved
     *  names, primitives, and composite-type builtins (Array/Set/Dict/Tuple),
     *  which the caller filters out. */
    private void recordTypeReference(SpnParseToken nameTok, String typeName) {
        if (nameTok == null || typeName == null) return;
        String targetFile = null;
        spn.source.SourceRange targetRange = null;
        // Prefer a local declaration (same file) — the TypeGraph has its
        // exact position. Fall back to the imported-declarations map when
        // the type was brought in from another module's source.
        TypeGraph.Node decl = typeGraph.lookupFirst(typeName);
        if (decl != null && decl.file() != null && decl.nameRange().isKnown()) {
            targetFile = decl.file();
            targetRange = decl.nameRange();
        } else {
            spn.language.TypeDeclPos pos = importedTypeDeclarations.get(typeName);
            if (pos != null && pos.range() != null && pos.range().isKnown()) {
                targetFile = pos.file();
                targetRange = pos.range();
            }
        }
        spn.source.SourceRange importRange = importedTypeRanges.get(typeName);
        if (targetFile == null && targetRange == null && importRange == null) return;
        typeReferenceSites.add(new TypeReferenceSite(
                nameTok.range(), typeName, targetFile, targetRange, importRange));
    }

    /**
     * Parse: let TypeName(a, b, c) = expr
     * Destructures a struct/product value into local bindings.
     */
    private SpnStatementNode parseLetDestructure() {
        String typeName = tokens.advance().text(); // consume type name
        tokens.expect("(");
        List<String> bindNames = new ArrayList<>();
        List<SpnParseToken> bindToks = new ArrayList<>();
        while (!tokens.check(")")) {
            SpnParseToken bindTok = tokens.advance();
            bindNames.add(bindTok.text());
            bindToks.add(bindTok);
            tokens.match(",");
        }
        tokens.expect(")");
        tokens.expect("=");
        SpnExpressionNode value = parseExpression();

        // Allocate slots for each binding
        int[] slots = new int[bindNames.size()];
        // Look up the struct/type descriptor to infer field types
        SpnStructDescriptor desc = structRegistry.get(typeName);
        for (int i = 0; i < bindNames.size(); i++) {
            String bn = bindNames.get(i);
            if (bn.equals("_")) {
                slots[i] = -1; // skip
            } else {
                // Infer field type from the descriptor if available
                FieldType fieldType = null;
                if (desc != null && i < desc.fieldCount()) {
                    fieldType = desc.fieldType(i);
                }
                slots[i] = currentScope.addLocal(bn, fieldType);
                recordLocalBinding(bn, bindToks.get(i), TypeGraph.Kind.LOCAL_BINDING);
            }
        }

        return new spn.node.local.SpnDestructureNode(value, slots);
    }

    /**
     * Parse: let (a, b, c) = expr
     * Destructures a tuple, array, struct, or product value into local bindings.
     * Infers field types from the value expression when possible.
     */
    private SpnStatementNode parseLetTupleDestructure() {
        tokens.expect("(");
        List<String> bindNames = new ArrayList<>();
        List<SpnParseToken> bindToks = new ArrayList<>();
        while (!tokens.check(")")) {
            SpnParseToken bindTok = tokens.advance();
            bindNames.add(bindTok.text());
            bindToks.add(bindTok);
            tokens.match(",");
        }
        tokens.expect(")");
        tokens.expect("=");
        SpnExpressionNode value = parseExpression();

        // Try to infer field types from the value's type. Three cases:
        //   * OfStruct / OfConstrainedType / OfProduct → use the struct descriptor's field types
        //   * OfTuple → use the tuple descriptor's element types directly
        //   * anything else → leave bindings untyped
        SpnStructDescriptor desc = null;
        FieldType[] tupleElementTypes = null;
        FieldType valueType = inferType(value);
        if (valueType != null) {
            desc = resolveStructDescriptor(valueType);
            if (valueType instanceof FieldType.OfTuple ot) {
                tupleElementTypes = ot.descriptor().getElementTypes();
            }
        }

        int[] slots = new int[bindNames.size()];
        for (int i = 0; i < bindNames.size(); i++) {
            String bn = bindNames.get(i);
            if (bn.equals("_")) {
                slots[i] = -1;
            } else {
                FieldType fieldType = null;
                if (desc != null && i < desc.fieldCount()) {
                    fieldType = desc.fieldType(i);
                } else if (tupleElementTypes != null && i < tupleElementTypes.length) {
                    fieldType = tupleElementTypes[i];
                }
                slots[i] = currentScope.addLocal(bn, fieldType);
                recordLocalBinding(bn, bindToks.get(i), TypeGraph.Kind.LOCAL_BINDING);
            }
        }

        return new spn.node.local.SpnDestructureNode(value, slots);
    }

    /**
     * Infer the compile-time type of an expression for operator dispatch.
     * Returns null if the type cannot be determined.
     */
    // ── Inference stubs ────────────────────────────────────────────────────
    //
    // All type-inference machinery has been stripped — the parser now produces
    // an untyped AST. The helpers below remain as permissive no-ops so existing
    // call sites keep compiling. A future inference pass will walk the AST and
    // reintroduce type checking, operator dispatch, promotion insertion, and
    // pattern-category validation. Until then:
    //   - inferType always returns null
    //   - typesMatch always returns true (optimistic match)
    //   - isPrimitive / isDefinitelyNonPrimitive are optimistic
    //   - trackType / trackArithmeticType / trackFieldType are no-ops
    //   - require*Operand / requireTyped / checkUntypedArgs are no-ops
    //   - unifyTypes returns null
    //
    // The parser will now happily accept ill-typed programs that used to be
    // rejected; the inference pass will re-enforce those rules when it arrives.

    // ── Resolver delegates ────────────────────────────────────────────────
    // Thin wrappers around the TypeResolver so existing parser call sites
    // don't need to change. Each forwards to the resolver.

    private FieldType inferType(SpnExpressionNode expr) { return resolver.inferType(expr); }

    private void trackType(SpnExpressionNode expr, FieldType type) { resolver.trackType(expr, type); }

    private FieldType unifyTypes(FieldType a, FieldType b) { return resolver.unifyTypes(a, b); }

    private boolean isDefinitelyNonPrimitive(FieldType t) { return resolver.isDefinitelyNonPrimitive(t); }

    private boolean isPrimitive(FieldType type) { return resolver.isPrimitive(type); }

    private boolean typesMatch(FieldType declared, FieldType inferred) { return resolver.typesMatch(declared, inferred); }

    private String describeTypeOrUnknown(FieldType t) { return t != null ? t.describe() : "?"; }

    private String patternCategoryMismatch(MatchPattern pat, FieldType subjectType) {
        return resolver.patternCategoryMismatch(pat, subjectType);
    }

    private void requireTyped(SpnExpressionNode expr, String operation) {
        FieldType type = inferType(expr);
        if (type instanceof FieldType.Untyped) {
            throw tokens.error("Untyped (_) value cannot be used in " + operation);
        }
    }

    private void requirePrimitiveOperand(SpnExpressionNode expr, String operation) {
        FieldType type = inferType(expr);
        if (type instanceof FieldType.Untyped) {
            throw tokens.error("Untyped (_) value cannot be used in " + operation);
        }
        if (isDefinitelyNonPrimitive(type)) {
            throw tokens.error("No overload of " + operation
                    + " for " + type.describe()
                    + " — define " + operation + " for this type");
        }
    }

    private void checkUntypedArgs(List<SpnExpressionNode> args, String funcName, SpnParseToken callTok) {
        SpnFunctionDescriptor desc = functionDescriptorRegistry.get(funcName);
        if (desc == null) return;
        FieldDescriptor[] params = desc.getParams();
        for (int i = 0; i < args.size() && i < params.length; i++) {
            FieldType argType = inferType(args.get(i));
            if (argType instanceof FieldType.Untyped && !(params[i].type() instanceof FieldType.Untyped)) {
                throw tokens.error("Cannot pass untyped (_) value to typed parameter '"
                        + params[i].name() + "' (expected " + params[i].type().describe() + ")", callTok);
            }
        }
    }

    /**
     * Check that a pure function does not call an action function.
     * Looks up the callee in the descriptor registry (user functions) and
     * impure builtin set (canvas, I/O, etc.).
     */
    private void checkPurityViolation(String calleeName, SpnParseToken callTok) {
        if (!currentFunctionIsPure) return; // action functions can call anything
        // Check user-defined functions
        SpnFunctionDescriptor desc = functionDescriptorRegistry.get(calleeName);
        if (desc != null && !desc.isPure()) {
            throw tokens.error("Pure function cannot call action function '" + calleeName + "'", callTok);
        }
        // Check builtins known to be impure
        if (impureBuiltins.contains(calleeName)) {
            throw tokens.error("Pure function cannot call action builtin '" + calleeName + "'", callTok);
        }
    }

    private void checkIndirectCallArgs(List<SpnExpressionNode> args, FieldType.OfFunction fnType,
                                        String calleeName, SpnParseToken callTok) {
        FieldType[] paramTypes = fnType.paramTypes();
        for (int i = 0; i < args.size() && i < paramTypes.length; i++) {
            FieldType argType = inferType(args.get(i));
            if (argType instanceof FieldType.Untyped && !(paramTypes[i] instanceof FieldType.Untyped)) {
                throw tokens.error("Cannot pass untyped (_) value to parameter " + (i + 1)
                        + " of '" + calleeName + "' (expected " + paramTypes[i].describe() + ")", callTok);
            }
        }
    }

    /**
     * Expect an identifier token, also accepting PATTERN_KW tokens (contains,
     * length, etc.) which are contextual keywords — valid as identifiers
     * outside of pattern context.
     */
    private SpnParseToken expectIdentifier() {
        SpnParseToken tok = tokens.peek();
        if (tok != null && (tok.type() == TokenType.IDENTIFIER || tok.type() == TokenType.PATTERN_KW)) {
            return tokens.advance();
        }
        return tokens.expectType(TokenType.IDENTIFIER); // will throw with a good message
    }

    /** Attach source span from the last consumed token (start = end = that token). */
    private <T extends SpnExpressionNode> T at(T node) {
        SpnParseToken tok = tokens.lastConsumed();
        if (tok != null) {
            node.setSourceSpan(sourceName, tok.line(), tok.col(), tok.line(), tok.endCol());
        }
        return node;
    }

    /**
     * Attach source span: starts at {@code tok}, ends at the last consumed token.
     * This captures the full expression span for binary ops, function calls, etc.
     */
    private <T extends SpnExpressionNode> T at(T node, SpnParseToken tok) {
        if (tok != null) {
            SpnParseToken end = tokens.lastConsumed();
            if (end != null) {
                node.setSourceSpan(sourceName, tok.line(), tok.col(), end.line(), end.endCol());
            } else {
                node.setSourceSpan(sourceName, tok.line(), tok.col(), tok.line(), tok.endCol());
            }
        }
        return node;
    }

    // ── While / do ─────────────────────────────────────────────────────────

    private SpnStatementNode parseWhileStatement() {
        tokens.expect("while");

        // while {condition} do { body }  OR  while producer(args) do (params) { body }
        if (tokens.check("{")) {
            // Condition lambda: while {condition} do { body }
            tokens.advance();
            SpnExpressionNode condition = parseExpression();
            tokens.expect("}");
            tokens.expect("do");

            if (tokens.check("(")) {
                // while {cond} do (params) { body } — shouldn't normally have params
                // but handle it gracefully
                return parseStreamDo(condition);
            }

            tokens.expect("{");
            SpnExpressionNode body = parseBlockBody();
            tokens.expect("}");
            return new SpnWhileNode(condition, body);
        }

        // while producer(args) do (params) { body }
        // Parse the producer call
        String producerName = tokens.expectType(TokenType.IDENTIFIER).text();
        tokens.expect("(");
        List<SpnExpressionNode> args = new ArrayList<>();
        while (!tokens.check(")")) {
            args.add(parseExpression());
            tokens.match(",");
        }
        tokens.expect(")");
        tokens.expect("do");

        return parseStreamDo(producerName, args);
    }

    private SpnStatementNode parseStreamDo(String producerName, List<SpnExpressionNode> args) {
        tokens.expect("(");
        String paramName = tokens.expectType(TokenType.IDENTIFIER).text();
        tokens.expect(")");

        int paramSlot = currentScope.addLocal(paramName);

        tokens.expect("{");
        SpnExpressionNode lambdaBody = parseBlockBody();
        tokens.expect("}");

        SpnLambdaNode lambda = new SpnLambdaNode(lambdaBody, paramSlot);

        CallTarget producerTarget = functionRegistry.get(producerName);
        if (producerTarget == null) {
            throw tokens.error("Unknown producer function: " + producerName);
        }

        return new SpnStreamBlockNode(lambda, producerTarget,
                args.toArray(new SpnExpressionNode[0]));
    }

    private SpnStatementNode parseStreamDo(SpnExpressionNode condition) {
        // This is a plain while loop with condition, no streaming
        tokens.expect("{");
        SpnExpressionNode body = parseBlockBody();
        tokens.expect("}");
        return new SpnWhileNode(condition, body);
    }

    // ── Yield / Return ─────────────────────────────────────────────────────

    private SpnExpressionNode parseYieldStatement() {
        String keyword = tokens.advance().text(); // "yield" or "return"
        SpnExpressionNode value = parseExpression();
        containsYield = true;
        int yieldSlot = currentScope.lookupLocal("__yieldCtx__");
        if (yieldSlot < 0) {
            throw tokens.error(keyword + " used outside of a function body");
        }
        return new SpnYieldNode(value, yieldSlot);
    }

    // ── Block body (multiple statements, last is the expression value) ─────

    private SpnExpressionNode parseBlockBody() {
        List<SpnStatementNode> stmts = new ArrayList<>();

        while (tokens.hasMore() && !tokens.check("}")) {
            SpnParseToken tok = tokens.peek();
            if (tok == null) throw tokens.error("Unexpected end of block");
            SpnStatementNode stmt = switch (tok.text()) {
                case "let" -> parseLetBinding();
                case "while" -> parseWhileStatement();
                case "yield" -> parseYieldStatement();
                case "return" -> parseYieldStatement();
                default -> parseExpressionStatement();
            };
            if (stmt != null) stmts.add(stmt);
        }

        if (stmts.isEmpty()) {
            return new SpnLongLiteralNode(0);
        }
        if (stmts.size() == 1 && stmts.getFirst() instanceof SpnExpressionNode expr) {
            return expr;
        }
        SpnExpressionNode block = new SpnBlockExprNode(stmts.toArray(new SpnStatementNode[0]));
        // Propagate the last expression's type to the block
        SpnStatementNode last = stmts.getLast();
        if (last instanceof SpnExpressionNode lastExpr) {
            FieldType lastType = inferType(lastExpr);
            if (lastType != null) trackType(block, lastType);
        }
        return block;
    }

    // ── Expression statement ───────────────────────────────────────────────

    private SpnExpressionNode parseExpressionStatement() {
        SpnExpressionNode expr = parseExpression();

        // Handle reassignment: x = expr
        if (tokens.match("=")) {
            // The left side should be a variable read — extract the slot
            // and create a write node instead
            if (expr instanceof SpnReadLocalVariableNodeWrapper wrapper) {
                SpnExpressionNode value = parseExpression();
                return SpnWriteLocalVariableNodeGen.create(value, wrapper.slot);
            }
            // this.x = expr  — stateful field write. If the field is new,
            // register it in the current shape (block-body only; do() bodies
            // can't introduce new fields).
            if (expr instanceof spn.node.stateful.SpnStatefulFieldReadNode fieldRead
                    && currentStatefulBlock != null) {
                SpnExpressionNode value = parseExpression();
                String fieldName = fieldRead.getFieldName();
                if (!currentStatefulBlock.shape.containsKey(fieldName)) {
                    if (currentStatefulBlock.inDoBody) {
                        throw tokens.error("Cannot introduce new field '" + fieldName
                                + "' inside a do() body; add it in the block body first");
                    }
                    FieldType inferred = inferType(value);
                    currentStatefulBlock.shape.put(fieldName,
                            inferred != null ? inferred : FieldType.UNTYPED);
                }
                return new spn.node.stateful.SpnStatefulFieldWriteNode(
                        fieldRead.getInstanceExpr(), fieldName, value);
            }
            throw tokens.error("Invalid assignment target");
        }

        return expr;
    }

    // ── Expression parsing (Pratt precedence climbing) ─────────────────────
    //
    // Single loop + table. Each binary operator has a precedence (higher = tighter
    // binding) and a fallback strategy for when no user-defined overload matches.
    // Qualified infixes (e.g., `*_dot`, `+_proj`) inherit the precedence of their
    // base operator. Two special cases are handled inline:
    //   - `-` may be unary (isUnaryMinus detects this and breaks the binary loop)
    //   - `1 / x` tries the multiplicative-inverse method fallback first
    //
    // Adding a new binary operator is a single INFIX_OPS entry.

    private static final int PREC_OR   = 1;  // ||
    private static final int PREC_AND  = 2;  // &&
    private static final int PREC_EQ   = 3;  // == !=
    private static final int PREC_CMP  = 4;  // < > <= >=
    private static final int PREC_CAT  = 5;  // ++
    private static final int PREC_ADD  = 6;  // + -
    private static final int PREC_MUL  = 7;  // * / %

    // ── Binary operator infrastructure ─────────────────────────────────────
    //
    // Each operator's fallback describes what happens when no user overload matches:
    //   ARITHMETIC       — trackArithmeticType (rejects non-primitives, infers result)
    //   REQUIRE_PRIMITIVE — requirePrimitiveOperand on both sides before built-in
    //   ALLOW_ANY        — no check (equality works on any type)
    //   NO_DISPATCH      — skip user-overload lookup entirely (for || and &&,
    //                      which have fixed boolean semantics)

    @FunctionalInterface
    private interface BinaryNodeFactory {
        SpnExpressionNode create(SpnExpressionNode left, SpnExpressionNode right);
    }

    // BinaryFallback is kept as a placeholder field on InfixOp so the
    // upcoming inference pass knows how each operator should be lowered
    // (arithmetic promotion vs primitive-only vs any-type vs no-user-dispatch).
    // The parser itself no longer consults it — it only emits the primitive node.
    private enum BinaryFallback { ARITHMETIC, REQUIRE_PRIMITIVE, ALLOW_ANY, NO_DISPATCH }

    /**
     * Dispatch a binary operator. Tries user-defined overload (via TypeResolver)
     * first, then falls back to the built-in primitive node. Records the dispatch
     * decision for IDE display.
     */
    private SpnExpressionNode dispatchBinaryOp(String symbol,
            SpnExpressionNode left, SpnExpressionNode right,
            BinaryNodeFactory factory, BinaryFallback fallback, FieldType resultType) {
        SpnParseToken opTok = tokens.lastConsumed();
        SpnExpressionNode dispatched = fallback == BinaryFallback.NO_DISPATCH
                ? null : tryOperatorDispatch(symbol, left, right);
        if (dispatched != null) {
            // Tag the resolved node with the operator's source position so the
            // IDE can show dispatch annotations at the right location.
            at(dispatched, opTok);
        }
        if (dispatched == null) {
            dispatched = switch (fallback) {
                case ARITHMETIC -> {
                    FieldType lt = inferType(left), rt = inferType(right);
                    if (lt instanceof FieldType.Untyped || rt instanceof FieldType.Untyped) {
                        throw tokens.error("Untyped (_) value cannot be used in arithmetic");
                    }
                    if (isDefinitelyNonPrimitive(lt) || isDefinitelyNonPrimitive(rt)) {
                        throw tokens.error("No overload for " + symbol + "("
                                + describeTypeOrUnknown(lt) + ", " + describeTypeOrUnknown(rt) + ")");
                    }
                    SpnExpressionNode node = at(factory.create(left, right), opTok);
                    // Track arithmetic result type
                    if (lt != null && rt != null && lt == rt) trackType(node, lt);
                    else if ((lt == FieldType.LONG && rt == FieldType.DOUBLE)
                          || (lt == FieldType.DOUBLE && rt == FieldType.LONG))
                        trackType(node, FieldType.DOUBLE);
                    yield node;
                }
                case REQUIRE_PRIMITIVE -> {
                    requirePrimitiveOperand(left, "'" + symbol + "'");
                    requirePrimitiveOperand(right, "'" + symbol + "'");
                    yield at(factory.create(left, right), opTok);
                }
                case ALLOW_ANY, NO_DISPATCH -> at(factory.create(left, right), opTok);
            };
        }
        if (resultType != null) trackType(dispatched, resultType);
        return dispatched;
    }

    /** One infix operator's precedence-table entry. */
    private record InfixOp(int prec, BinaryFallback fallback,
                           FieldType resultType, BinaryNodeFactory factory) {}

    /**
     * Precedence/dispatch table for all binary operators. Qualified variants
     * (e.g. {@code *_dot}) inherit the precedence of their base; look them up
     * via {@link #infixOpForBase(String)}.
     */
    private static final java.util.Map<String, InfixOp> INFIX_OPS = java.util.Map.ofEntries(
            java.util.Map.entry("||", new InfixOp(PREC_OR,  BinaryFallback.NO_DISPATCH,      FieldType.BOOLEAN, SpnOrNode::new)),
            java.util.Map.entry("&&", new InfixOp(PREC_AND, BinaryFallback.NO_DISPATCH,      FieldType.BOOLEAN, SpnAndNode::new)),
            java.util.Map.entry("==", new InfixOp(PREC_EQ,  BinaryFallback.ALLOW_ANY,        FieldType.BOOLEAN, SpnEqualNodeGen::create)),
            java.util.Map.entry("!=", new InfixOp(PREC_EQ,  BinaryFallback.ALLOW_ANY,        FieldType.BOOLEAN, SpnNotEqualNodeGen::create)),
            java.util.Map.entry("<",  new InfixOp(PREC_CMP, BinaryFallback.REQUIRE_PRIMITIVE, FieldType.BOOLEAN, SpnLessThanNodeGen::create)),
            java.util.Map.entry(">",  new InfixOp(PREC_CMP, BinaryFallback.REQUIRE_PRIMITIVE, FieldType.BOOLEAN, SpnGreaterThanNodeGen::create)),
            java.util.Map.entry("<=", new InfixOp(PREC_CMP, BinaryFallback.REQUIRE_PRIMITIVE, FieldType.BOOLEAN, SpnLessEqualNodeGen::create)),
            java.util.Map.entry(">=", new InfixOp(PREC_CMP, BinaryFallback.REQUIRE_PRIMITIVE, FieldType.BOOLEAN, SpnGreaterEqualNodeGen::create)),
            java.util.Map.entry("++", new InfixOp(PREC_CAT, BinaryFallback.REQUIRE_PRIMITIVE, FieldType.STRING,  SpnStringConcatNodeGen::create)),
            java.util.Map.entry("+",  new InfixOp(PREC_ADD, BinaryFallback.ARITHMETIC,        null,              SpnAddNodeGen::create)),
            java.util.Map.entry("-",  new InfixOp(PREC_ADD, BinaryFallback.ARITHMETIC,        null,              SpnSubtractNodeGen::create)),
            java.util.Map.entry("*",  new InfixOp(PREC_MUL, BinaryFallback.ARITHMETIC,        null,              SpnMultiplyNodeGen::create)),
            java.util.Map.entry("/",  new InfixOp(PREC_MUL, BinaryFallback.ARITHMETIC,        null,              SpnDivideNodeGen::create)),
            java.util.Map.entry("%",  new InfixOp(PREC_MUL, BinaryFallback.ARITHMETIC,        null,              SpnModuloNodeGen::create))
    );

    /** Look up the InfixOp for a base symbol, or null if not an infix operator. */
    private static InfixOp infixOpForBase(String sym) {
        return INFIX_OPS.get(sym);
    }

    /**
     * If the peeked token is a qualified infix (e.g., {@code *_dot}), returns
     * the full operator text. Otherwise null. Does NOT consume the token.
     */
    private String peekQualifiedInfix() {
        SpnParseToken tok = tokens.peek();
        if (tok == null || tok.type() != TokenType.OPERATOR) return null;
        String text = tok.text();
        int underscore = text.indexOf('_');
        if (underscore <= 0) return null;
        String base = text.substring(0, underscore);
        return INFIX_OPS.containsKey(base) ? text : null;
    }

    private SpnExpressionNode parseExpression() {
        return parseExpr(0);
    }

    /**
     * Pratt-style precedence climbing: parse a unary expression, then consume
     * left-associative infix operators whose precedence is at least
     * {@code minPrec}, combining into the left expression.
     */
    private SpnExpressionNode parseExpr(int minPrec) {
        SpnExpressionNode left = parseUnary();

        while (true) {
            SpnParseToken tok = tokens.peek();
            if (tok == null) break;

            String opText = tok.text();
            String resolved = opText;
            InfixOp op = INFIX_OPS.get(opText);

            // Qualified infix: *_dot borrows precedence from *.
            if (op == null) {
                String qual = peekQualifiedInfix();
                if (qual == null) break;
                resolved = qual;
                op = INFIX_OPS.get(qual.substring(0, qual.indexOf('_')));
                if (op == null) break;
            }

            // `-` at start of a new line is unary (isUnaryMinus); break so the
            // outer caller can re-enter parseExpr for the next statement.
            if (opText.equals("-") && isUnaryMinus()) break;

            if (op.prec() < minPrec) break;
            tokens.advance(); // consume the operator

            // Left-associative: RHS binds one precedence higher.
            SpnExpressionNode right = parseExpr(op.prec() + 1);

            // Special: `1 / x` → try multiplicative-inverse dispatch.
            if (resolved.equals("/")) {
                SpnExpressionNode dispatched = tryOperatorDispatch("/", left, right);
                if (dispatched == null) dispatched = tryUnaryInverse(left, right);
                if (dispatched != null) {
                    at(dispatched, tokens.lastConsumed());
                    if (op.resultType() != null) trackType(dispatched, op.resultType());
                    left = dispatched;
                    continue;
                }
            }

            // Qualified infixes must dispatch to a user overload — no built-in.
            if (!resolved.equals(opText)) {
                SpnExpressionNode dispatched = tryOperatorDispatch(resolved, left, right);
                if (dispatched == null) {
                    throw tokens.error("No overload found for qualified operator: " + resolved);
                }
                at(dispatched, tokens.lastConsumed());
                left = dispatched;
                continue;
            }

            left = dispatchBinaryOp(resolved, left, right,
                    op.factory(), op.fallback(), op.resultType());
        }

        return left;
    }


    private SpnExpressionNode tryOperatorDispatch(String op,
                                                    SpnExpressionNode left,
                                                    SpnExpressionNode right) {
        return resolver.tryOperatorDispatch(op, left, right);
    }

    private SpnExpressionNode tryUnaryInverse(SpnExpressionNode left, SpnExpressionNode right) {
        return resolver.tryUnaryInverse(left, right);
    }

    private void promoteArgs(List<SpnExpressionNode> args, String funcName) {
        resolver.promoteArgs(args, funcName);
    }

    private SpnExpressionNode parseUnary() {
        if (tokens.match("-")) {
            SpnParseToken minusTok = tokens.lastConsumed();
            SpnExpressionNode operand = parseUnary();
            // Try compile-time dispatch: unary overload or .neg() method
            SpnExpressionNode dispatched = resolver.tryUnaryDispatch("-", operand);
            if (dispatched != null) return at(dispatched, minusTok);
            // Primitive negation (int/float)
            return at(SpnNegateNodeGen.create(operand));
        }
        return parsePostfix();
    }

    private SpnExpressionNode parsePostfix() {
        SpnExpressionNode expr = parsePrimary();

        while (true) {
            // Indexing: expr[index]
            //
            // Two dispatch paths, in priority order:
            //   1. User-defined subscript — `pure TypeName[](args) -> ret`
            //      registered in methodRegistry under "TypeName.[]". Produces
            //      a SpnMethodInvokeNode; the method's return type propagates
            //      statically so chains like `arr[i].field` typecheck.
            //      Skipped when receiver is OfArray (raw arrays use path 2).
            //   2. Builtin subscript — SpnArrayAccessNode handles OfArray at
            //      compile time with element-type propagation, and falls
            //      through at runtime to SpnArrayValue / SpnDictionaryValue
            //      specializations. Runs for any receiver not caught by path 1.
            //
            // Typed wrappers from Collections.spn (Array<T>/Dict<K,V>) go
            // through path 1; bare literals (`[1, 2]`, `[:k v]`) go through
            // path 2. Both coexist and don't interfere.
            if (tokens.check("[")) {
                requireTyped(expr, "indexing");
                // Capture the receiver type BEFORE the access node replaces expr,
                // so we can propagate the element type if it's known.
                FieldType receiverType = inferType(expr);
                SpnParseToken openTok = tokens.advance();
                SpnExpressionNode index = parseExpression();
                tokens.expect("]");

                if (!(receiverType instanceof FieldType.OfArray)) {
                    MethodEntry method = resolveMethod(receiverType, "[]");
                    if (method != null) {
                        recordMethodCall(openTok, receiverType, "[]", method);
                        List<SpnExpressionNode> args = new ArrayList<>();
                        args.add(index);
                        if (method.descriptor() != null) {
                            resolver.promoteMethodArgs(args, method.descriptor());
                        }
                        expr = new spn.node.func.SpnMethodInvokeNode(
                                method.callTarget(), expr,
                                args.toArray(new SpnExpressionNode[0]));
                        if (method.descriptor().hasTypedReturn()) {
                            trackType(expr, method.descriptor().getReturnType());
                        }
                        continue;
                    }
                }

                expr = SpnArrayAccessNodeGen.create(expr, index);
                if (receiverType instanceof FieldType.OfArray oa) {
                    trackType(expr, oa.elementType());
                }
                continue;
            }

            // Dot access: expr.field, expr.0, or expr.method(args)
            if (tokens.check(".")) {
                SpnParseToken dotTok = tokens.advance(); // consume '.'
                SpnParseToken nextTok = tokens.peek();
                if (nextTok == null) throw tokens.error("Expected field name or index after '.'");

                // Positional access: expr.0, expr.1, etc.
                if (nextTok.type() == TokenType.NUMBER && !nextTok.text().contains(".")) {
                    tokens.advance();
                    int index = Integer.parseInt(nextTok.text());
                    FieldType receiverType = inferType(expr);
                    expr = createPositionalAccess(expr, index, receiverType, nextTok);
                    continue;
                }

                SpnParseToken nameTok = tokens.advance(); // field or method name
                String memberName = nameTok.text();

                if (tokens.check("(")) {
                    // Method call: expr.method(args)
                    tokens.advance();
                    List<SpnExpressionNode> args = new ArrayList<>();
                    while (!tokens.check(")")) {
                        args.add(parseExpression());
                        tokens.match(",");
                    }
                    tokens.expect(")");

                    // Look up method by receiver type
                    FieldType receiverType = inferType(expr);
                    MethodEntry method = resolveMethod(receiverType, memberName);
                    // Fallback: if no direct method exists and the short name
                    // was imported as a qualified-key alias, retry with the
                    // expanded `@a.b.name` form.
                    if (method == null) {
                        String aliased = qualifiedKeyAliases.get(memberName);
                        if (aliased != null) {
                            method = resolveMethod(receiverType, aliased);
                        }
                    }
                    if (method != null) {
                        recordMethodCall(nameTok, receiverType, memberName, method);
                        // Promote args to match method parameter types.
                        // Method descriptors have 'this' as param[0], so we promote
                        // against params[1:] (skipping the receiver).
                        if (method.descriptor() != null) {
                            resolver.promoteMethodArgs(args, method.descriptor());
                        }
                        expr = new spn.node.func.SpnMethodInvokeNode(
                                method.callTarget(), expr,
                                args.toArray(new SpnExpressionNode[0]));
                        if (method.descriptor().hasTypedReturn()) {
                            trackType(expr, method.descriptor().getReturnType());
                        }
                    } else {
                        // Fallback: method factory for higher-order stdlib
                        // methods like arr.map(fn). The factory bakes the
                        // function arg into a per-call-site CallTarget, same
                        // pattern as its flat-function twin.
                        spn.node.BuiltinFactory factory = lookupMethodFactory(receiverType, memberName);
                        if (factory == null) {
                            String aliased = qualifiedKeyAliases.get(memberName);
                            if (aliased != null) {
                                factory = lookupMethodFactory(receiverType, aliased);
                            }
                        }
                        if (factory != null) {
                            SpnExpressionNode[] combined = new SpnExpressionNode[args.size() + 1];
                            combined[0] = expr;
                            for (int i = 0; i < args.size(); i++) combined[i + 1] = args.get(i);
                            expr = factory.create(combined);
                            // Track return type from the paired descriptor so
                            // chains like arr.map(fn).length() can dispatch
                            // on the result.
                            String typeName = resolver.resolveTypeName(receiverType);
                            if (typeName != null) {
                                SpnFunctionDescriptor methodDesc =
                                        methodFactoryDescriptors.get(typeName + "." + memberName);
                                if (methodDesc != null && methodDesc.hasTypedReturn()) {
                                    trackType(expr, methodDesc.getReturnType());
                                }
                            }
                        } else {
                            throw tokens.error("Unknown method '" + memberName + "'"
                                    + (receiverType != null ? " on type " + receiverType.describe() : ""), nameTok);
                        }
                    }
                } else {
                    // Field access: expr.field
                    FieldType receiverType = inferType(expr);
                    int fieldIndex = resolveFieldIndex(expr, memberName);
                    expr = spn.node.struct.SpnFieldAccessNodeGen.create(expr, fieldIndex);

                    // Track the field's type if known (from the receiver's descriptor)
                    trackFieldType(expr, memberName, receiverType);

                    // Record for go-to-def so the editor can jump to the
                    // field's declaration in the type definition.
                    recordFieldAccess(nameTok, receiverType, memberName);
                }
                continue;
            }

            break;
        }

        return expr;
    }

    // ── Type dispatch helpers ─────────────────────────────────────────────

    // ── Structural + resolver-backed helpers ──────────────────────────────

    private SpnStructDescriptor resolveStructDescriptor(FieldType type) {
        return resolver.resolveStructDescriptor(type);
    }

    private String resolveTypeName(FieldType type) { return resolver.resolveTypeName(type); }

    private SpnExpressionNode createPositionalAccess(SpnExpressionNode expr, int index,
                                                      FieldType receiverType, SpnParseToken tok) {
        if (receiverType instanceof FieldType.OfTuple ot) {
            SpnExpressionNode node = spn.node.struct.SpnTupleElementAccessNodeGen.create(expr, index);
            var desc = ot.descriptor();
            if (index < desc.arity()) {
                FieldType elemType = desc.elementType(index);
                if (elemType != null) trackType(node, elemType);
            }
            return node;
        }
        SpnStructDescriptor sd = resolveStructDescriptor(receiverType);
        if (sd != null) {
            if (index >= sd.fieldCount()) {
                throw tokens.error("Positional index " + index + " out of bounds for "
                        + sd.getName() + " (has " + sd.fieldCount() + " fields)", tok);
            }
            SpnExpressionNode node = spn.node.struct.SpnFieldAccessNodeGen.create(expr, index);
            FieldType ft = sd.fieldType(index);
            if (ft != null) trackType(node, ft);
            // Record for go-to-def: jump to the i-th component in the type
            // declaration. Reuses the field-access machinery — positional
            // components are stored in the TypeGraph under composite key
            // "TypeName.<index>".
            recordFieldAccess(tok, receiverType, String.valueOf(index));
            return node;
        }
        // Fallback: generic field access by index
        return spn.node.struct.SpnFieldAccessNodeGen.create(expr, index);
    }

    private int resolveFieldIndex(SpnExpressionNode expr, String fieldName) {
        FieldType type = inferType(expr);
        int idx = resolver.resolveFieldIndex(type, fieldName);
        if (idx >= 0) {
            // Privacy check: reject access to private fields from outside the type's methods.
            // Inside a method (currentFactoryTypeName set), private fields of the SAME type are OK.
            SpnStructDescriptor sd = resolver.resolveStructDescriptor(type);
            if (sd != null && sd.isFieldPrivate(idx)) {
                String typeName = sd.getName();
                // Allow access from methods/factories on the same type
                boolean isOwnFactory = typeName.equals(currentFactoryTypeName);
                boolean isOwnMethod = typeName.equals(currentMethodTypeName);
                if (!isOwnFactory && !isOwnMethod) {
                    throw tokens.error("Field '" + fieldName + "' on " + typeName
                            + " is private (defined via let this." + fieldName + " in constructor)");
                }
            }
            return idx;
        }
        throw tokens.error("Cannot resolve field '" + fieldName + "'"
                + (type != null ? " on type " + type.describe() : " (unknown type)"));
    }

    private void trackFieldType(SpnExpressionNode accessNode, String fieldName, FieldType receiverType) {
        FieldType ft = resolver.resolveFieldType(receiverType, fieldName);
        if (ft != null) trackType(accessNode, ft);
    }

    /** Look up a stdlib method factory for higher-order calls like arr.map(fn). */
    private spn.node.BuiltinFactory lookupMethodFactory(FieldType receiverType, String methodName) {
        if (receiverType == null || methodFactories.isEmpty()) return null;
        String typeName = resolver.resolveTypeName(receiverType);
        if (typeName == null) return null;
        return methodFactories.get(typeName + "." + methodName);
    }

    private MethodEntry resolveMethod(FieldType receiverType, String methodName) {
        return resolver.resolveMethod(receiverType, methodName);
    }

    private FieldType resolveTypeByName(String typeName) {
        SpnStructDescriptor sd = structRegistry.get(typeName);
        if (sd != null) return FieldType.ofStruct(sd);
        SpnTypeDescriptor td = typeRegistry.get(typeName);
        if (td != null) return td.isProduct()
                ? FieldType.ofProduct(td) : FieldType.ofConstrainedType(td);
        return null;
    }

    // ── Primary expressions ────────────────────────────────────────────────

    private SpnExpressionNode parsePrimary() {
        SpnParseToken tok = tokens.peek();
        if (tok == null) throw tokens.error("Unexpected end of input");

        // Number literals
        if (tok.type() == TokenType.NUMBER) {
            tokens.advance();
            String text = tok.text();
            if (text.contains(".")) {
                return new SpnDoubleLiteralNode(Double.parseDouble(text));
            }
            return new SpnLongLiteralNode(Long.parseLong(text));
        }

        // String literal
        if (tok.type() == TokenType.STRING) {
            tokens.advance();
            String raw = tok.text();
            return new SpnStringLiteralNode(unescapeString(raw));
        }

        // Symbol literal :name
        if (tok.type() == TokenType.SYMBOL) {
            tokens.advance();
            String symName = tok.text().substring(1); // strip leading ':'
            return new SpnSymbolLiteralNode(symbolTable.intern(symName));
        }

        // Match expression
        if (tok.text().equals("match")) {
            return parseMatchExpression();
        }

        // Collection literal [...]
        if (tok.text().equals("[")) {
            return parseCollectionLiteral();
        }

        // Lambda expression: (params) -> body   OR   parenthesized expression / tuple
        if (tok.text().equals("(")) {
            if (looksLikeLambda()) {
                return parseLambda();
            }
            return parseParenOrTuple();
        }

        // do(params) { body } — closure bound to the current stateful `this`.
        // Only valid inside a stateful block; otherwise `do` is parsed elsewhere.
        if (tok.text().equals("do")) {
            SpnParseToken next = tokens.peek(1);
            if (next != null && next.text().equals("(")) {
                return parseDoClosure();
            }
        }

        // Block: { statements... expr }
        if (tok.text().equals("{")) {
            tokens.advance();
            SpnExpressionNode block = parseBlockBody();
            tokens.expect("}");
            return block;
        }

        // Type name — constructor TypeName(args) or associated constant TypeName.name
        if (tok.type() == TokenType.TYPE_NAME) {
            SpnParseToken next = tokens.peek(1);
            if (next != null && next.text().equals(".")) {
                SpnParseToken nameAfterDot = tokens.peek(2);
                if (nameAfterDot != null && nameAfterDot.type() == TokenType.IDENTIFIER) {
                    String key = tok.text() + "." + nameAfterDot.text();

                    // Check exportable constants (from 'const' declarations)
                    ConstantEntry constEntry = constantRegistry.get(key);
                    if (constEntry != null) {
                        tokens.advance(); // TypeName
                        tokens.advance(); // .
                        tokens.advance(); // name
                        recordConstantAccess(tok, tok.text(), nameAfterDot, nameAfterDot.text());
                        SpnExpressionNode callNode = new spn.node.func.SpnInvokeNode(
                                constEntry.callTarget());
                        if (constEntry.type() != null) trackType(callNode, constEntry.type());
                        return callNode;
                    }

                    // Legacy frame-slot constants (from 'let TypeName.name = expr')
                    Integer slot = typeConstantSlots.get(key);
                    if (slot != null) {
                        tokens.advance(); // TypeName
                        tokens.advance(); // .
                        tokens.advance(); // name
                        SpnExpressionNode readNode = new SpnReadLocalVariableNodeWrapper(slot);
                        FieldType constType = typeConstantTypes.get(key);
                        if (constType != null) trackType(readNode, constType);
                        return readNode;
                    }
                }
            }
            return parseTypeConstructor();
        }

        // Identifier — variable read or function call
        // PATTERN_KW tokens (length, contains, etc.) are contextual keywords
        // that can also be used as identifiers in expression context.
        if (tok.type() == TokenType.IDENTIFIER || tok.type() == TokenType.PATTERN_KW) {
            return parseIdentifierExpr();
        }

        // Boolean literals (they show up as identifiers in the lexer)
        if (tok.text().equals("true")) {
            tokens.advance();
            return new SpnBooleanLiteralNode(true);
        }
        if (tok.text().equals("false")) {
            tokens.advance();
            return new SpnBooleanLiteralNode(false);
        }

        // Wildcard
        if (tok.text().equals("_")) {
            tokens.advance();
            return new SpnLongLiteralNode(0); // wildcard as expression = unit
        }

        throw tokens.error("Unexpected token: " + tok.text(), tok);
    }

    private SpnExpressionNode parseIdentifierExpr() {
        SpnParseToken tok = tokens.advance();
        String name = tok.text();

        // true/false
        if (name.equals("true")) return new SpnBooleanLiteralNode(true);
        if (name.equals("false")) return new SpnBooleanLiteralNode(false);

        // this.x inside a stateful block → read field from the in-scope instance.
        // Writes (`this.x = expr`) are rewritten in parseExpressionStatement.
        if (name.equals("this") && currentStatefulBlock != null && tokens.check(".")) {
            return parseStatefulThisAccess(tok);
        }

        // this(args) inside a factory body → raw construction
        if (name.equals("this") && currentFactoryTypeName != null && tokens.check("(")) {
            tokens.advance(); // consume '('
            List<SpnExpressionNode> args = new ArrayList<>();
            while (!tokens.check(")")) {
                args.add(parseExpression());
                tokens.match(",");
            }
            tokens.expect(")");
            // Emit raw struct/product construction
            SpnStructDescriptor desc = structRegistry.get(currentFactoryTypeName);
            if (desc != null) {
                return new SpnStructConstructNode(desc, args.toArray(new SpnExpressionNode[0]));
            }
            SpnTypeDescriptor typeDesc = typeRegistry.get(currentFactoryTypeName);
            if (typeDesc != null && typeDesc.isProduct()) {
                return new spn.node.type.SpnProductConstructNode(typeDesc,
                        args.toArray(new SpnExpressionNode[0]));
            }
            throw tokens.error("Cannot resolve raw constructor for " + currentFactoryTypeName, tok);
        }

        // Qualified module access: M.name or M.name(args)
        if (tokens.check(".") && qualifiedModules.containsKey(name)) {
            return parseQualifiedAccess(name);
        }

        // Function call: name(args) — only if '(' is on the same line (avoids
        // treating a tuple expression on the next line as function arguments)
        if (tokens.check("(") && tokens.peek().line() == tok.line()) {
            tokens.advance();
            List<SpnExpressionNode> args = new ArrayList<>();
            while (!tokens.check(")")) {
                args.add(parseExpression());
                tokens.match(",");
            }
            tokens.expect(")");

            // Check for overloaded function (multiple dispatch)
            List<OperatorOverload> overloads = functionOverloads.get(name);
            if (overloads != null && !overloads.isEmpty()) {
                // Use the same dispatch logic as operators: exact type match,
                // then promotion-based match.
                SpnExpressionNode dispatched = resolver.tryFunctionDispatch(
                        name, overloads, args);
                if (dispatched != null) return dispatched;
            }

            // Single-function lookup (no overloading)
            CallTarget target = functionRegistry.get(name);
            if (target != null) {
                // If overloads exist but dispatch failed, we still try the last-registered
                // version (backward compat for non-overloaded functions).
                checkPurityViolation(name, tok);
                checkUntypedArgs(args, name, tok);
                promoteArgs(args, name);
                SpnExpressionNode callNode = new SpnInvokeNode(target, args.toArray(new SpnExpressionNode[0]));
                // Track the return type so downstream dispatch (operators, methods) works
                SpnFunctionDescriptor desc = functionDescriptorRegistry.get(name);
                if (desc != null && desc.hasTypedReturn()) {
                    trackType(callNode, desc.getReturnType());
                }
                return callNode;
            }

            // Builtin function (stdlib, canvas, etc.)
            spn.node.BuiltinFactory builtin = builtinRegistry.get(name);
            if (builtin != null) {
                checkPurityViolation(name, tok);
                SpnExpressionNode node = builtin.create(args.toArray(new SpnExpressionNode[0]));
                // Track return type from descriptor so chained calls can
                // dispatch (e.g. arr.map(fn).length()).
                SpnFunctionDescriptor builtinDesc = functionDescriptorRegistry.get(name);
                if (builtinDesc != null && builtinDesc.hasTypedReturn()) {
                    trackType(node, builtinDesc.getReturnType());
                }
                return node;
            }

            // Self-recursive call — the function is being defined right now.
            // Track the declared return type so downstream operators can dispatch.
            if (name.equals(deferredFunctionName)) {
                SpnDeferredInvokeNode deferred = new SpnDeferredInvokeNode(
                        name, args.toArray(new SpnExpressionNode[0]));
                deferredCalls.add(deferred);
                if (deferredReturnType != null) {
                    trackType(deferred, deferredReturnType);
                }
                return deferred;
            }

            // Indirect call — variable might hold a function value
            int callSlot = currentScope.lookupLocal(name);
            if (callSlot >= 0) {
                FieldType calleeType = currentScope.lookupType(name);
                if (calleeType instanceof FieldType.Untyped) {
                    throw tokens.error("Untyped (_) value '" + name + "' cannot be called as a function; declare a function type", tok);
                }
                SpnExpressionNode callNode = new SpnIndirectInvokeNode(
                        new SpnReadLocalVariableNodeWrapper(callSlot),
                        args.toArray(new SpnExpressionNode[0]));
                // If the variable has a function type, track the return type and check args
                if (calleeType instanceof FieldType.OfFunction fnType) {
                    trackType(callNode, fnType.returnType());
                    checkIndirectCallArgs(args, fnType, name, tok);
                }
                return callNode;
            }

            throw tokens.error("Unknown function: " + name, tok);
        }

        // Variable read
        int slot = currentScope.lookupLocal(name);
        if (slot >= 0) {
            // Inside a do() body, only locals declared within that body are
            // valid — outer-scope locals don't exist in the closure's frame.
            // Walk-up lookups would silently produce out-of-bounds at runtime.
            if (currentStatefulBlock != null && currentStatefulBlock.inDoBody
                    && currentScope.lookupLocalImmediate(name) < 0) {
                throw tokens.error("do() body cannot reference outer local '"
                        + name + "' — only `this.*` and locals declared inside the do() are visible. "
                        + "Move the value onto the stateful type, or build the closure where the value is local.",
                        tok);
            }
            SpnExpressionNode readNode = new SpnReadLocalVariableNodeWrapper(slot);
            FieldType varType = currentScope.lookupType(name);
            if (varType != null) trackType(readNode, varType);
            return readNode;
        }

        // Function reference as value (bare name, no call)
        CallTarget refTarget = functionRegistry.get(name);
        if (refTarget != null) {
            SpnExpressionNode refNode = new SpnFunctionRefNode(refTarget);
            SpnFunctionDescriptor refDesc = functionDescriptorRegistry.get(name);
            if (refDesc != null) {
                FieldDescriptor[] refParams = refDesc.getParams();
                FieldType[] refParamTypes = new FieldType[refParams.length];
                for (int i = 0; i < refParams.length; i++) refParamTypes[i] = refParams[i].type();
                trackType(refNode, FieldType.ofFunction(refParamTypes, refDesc.getReturnType()));
            }
            return refNode;
        }

        throw tokens.error("Undefined variable: " + name, tok);
    }

    /**
     * Parses qualified module access: M.name or M.name(args...)
     * Called when we've seen an identifier that matches a qualified module prefix
     * and the next token is '.'.
     */
    private SpnExpressionNode parseQualifiedAccess(String qualifier) {
        tokens.expect("."); // consume the dot
        SpnParseToken memberTok = tokens.advance();
        String memberName = memberTok.text();
        SpnModule module = qualifiedModules.get(qualifier);

        // Qualified function call: M.name(args...)
        if (tokens.check("(")) {
            tokens.advance();
            List<SpnExpressionNode> args = new ArrayList<>();
            while (!tokens.check(")")) {
                args.add(parseExpression());
                tokens.match(",");
            }
            tokens.expect(")");

            CallTarget target = module.getFunction(memberName);
            if (target != null) {
                return new SpnInvokeNode(target, args.toArray(new SpnExpressionNode[0]));
            }

            spn.node.BuiltinFactory builtin = module.getBuiltinFactory(memberName);
            if (builtin != null) {
                return builtin.create(args.toArray(new SpnExpressionNode[0]));
            }

            throw tokens.error("Module '" + qualifier + "' (" + module.getNamespace()
                    + ") does not export function '" + memberName + "'", memberTok);
        }

        // Qualified function reference: M.name (no call)
        CallTarget refTarget = module.getFunction(memberName);
        if (refTarget != null) {
            return new SpnFunctionRefNode(refTarget);
        }

        throw tokens.error("Module '" + qualifier + "' (" + module.getNamespace()
                + ") does not export '" + memberName + "'", memberTok);
    }

    private SpnExpressionNode parseTypeConstructor() {
        SpnParseToken nameTok = tokens.advance();
        String name = nameTok.text();

        // Optional generic params <T, U> — skip them for now
        if (tokens.match("<")) {
            while (!tokens.check(">")) tokens.advance();
            tokens.expect(">");
        }

        tokens.expect("(");
        List<SpnExpressionNode> args = new ArrayList<>();
        while (!tokens.check(")")) {
            args.add(parseExpression());
            tokens.match(",");
        }
        tokens.expect(")");

        // Stateful block: T(args) { body } where T was declared `stateful type`.
        // Only valid when followed by `{`. Bare T(args) on a stateful type is an error.
        if (statefulTypes.contains(name)) {
            if (!tokens.check("{")) {
                throw tokens.error("Stateful type '" + name
                        + "' must be constructed in block form: " + name + "(args) { body }",
                        nameTok);
            }
            return parseStatefulBlock(name, args, nameTok);
        }

        // Inside a factory body, TypeName(args) with same type AND arity is recursive — reject it.
        // The user should use this(args) for raw construction.
        // Different arity is fine (chaining between overloads).
        if (name.equals(currentFactoryTypeName) && args.size() == currentFactoryArity) {
            throw tokens.error("Recursive factory call: use this("
                    + args.size() + " args) for raw construction inside a factory", nameTok);
        }

        // Check for factory — TypeName(args) goes through factory if defined.
        // Try type-based dispatch first (for overloaded factories), then arity match.
        List<FactoryEntry> factories = factoryRegistry.get(name);
        if (factories != null) {
            // Collect arg types for dispatch
            FieldType[] argTypes = new FieldType[args.size()];
            for (int i = 0; i < args.size(); i++) argTypes[i] = inferType(args.get(i));

            // Try exact type match first
            for (FactoryEntry fe : factories) {
                if (fe.arity() != args.size()) continue;
                FieldDescriptor[] params = fe.descriptor().getParams();
                boolean match = true;
                for (int i = 0; i < args.size() && i < params.length; i++) {
                    if (argTypes[i] != null && !typesMatch(params[i].type(), argTypes[i])) {
                        match = false; break;
                    }
                }
                if (match) {
                    recordFactoryCall(nameTok, name, fe);
                    SpnExpressionNode callNode = new spn.node.func.SpnInvokeNode(
                            fe.callTarget(), args.toArray(new SpnExpressionNode[0]));
                    if (fe.descriptor().hasTypedReturn()) {
                        trackType(callNode, fe.descriptor().getReturnType());
                    }
                    return at(callNode, nameTok);
                }
            }

            // Arity match with promotion
            for (FactoryEntry fe : factories) {
                if (fe.arity() == args.size()) {
                    recordFactoryCall(nameTok, name, fe);
                    String qualifiedName = name + "/" + args.size();
                    promoteArgs(args, qualifiedName);
                    SpnExpressionNode callNode = new spn.node.func.SpnInvokeNode(
                            fe.callTarget(), args.toArray(new SpnExpressionNode[0]));
                    if (fe.descriptor().hasTypedReturn()) {
                        trackType(callNode, fe.descriptor().getReturnType());
                    }
                    return at(callNode, nameTok);
                }
            }
        }

        // Raw construction (no factory, or inside own factory body)
        SpnStructDescriptor desc = structRegistry.get(name);
        if (desc != null) {
            if (args.size() != desc.fieldCount()) {
                throw tokens.error(name + " expects " + desc.fieldCount()
                        + " argument(s), got " + args.size(), nameTok);
            }
            return new SpnStructConstructNode(desc, args.toArray(new SpnExpressionNode[0]));
        }

        SpnTypeDescriptor typeDesc = typeRegistry.get(name);
        if (typeDesc != null && typeDesc.isProduct()) {
            if (args.size() != typeDesc.componentCount()) {
                throw tokens.error(name + " expects " + typeDesc.componentCount()
                        + " argument(s), got " + args.size(), nameTok);
            }
            return new spn.node.type.SpnProductConstructNode(typeDesc,
                    args.toArray(new SpnExpressionNode[0]));
        }

        throw tokens.error("Unknown type: " + name, nameTok);
    }

    // ── Collection literal [...]  ──────────────────────────────────────────

    private SpnExpressionNode parseCollectionLiteral() {
        tokens.expect("[");

        if (tokens.check("]")) {
            tokens.advance();
            SpnArrayLiteralNode empty = new SpnArrayLiteralNode(FieldType.UNTYPED);
            // Track as Array with untyped elements so method dispatch on the
            // literal (e.g. `[].append(1)`) finds Array.append.
            trackType(empty, FieldType.ofArray(FieldType.UNTYPED));
            return empty;
        }

        // Peek to determine: is this a dict (first element is :key value)?
        SpnParseToken first = tokens.peek();
        if (first == null) throw tokens.error("Expected collection element after '['");
        if (first.type() == TokenType.SYMBOL) {
            // Could be dict [:key value, ...] or set [:a, :b, :c]
            SpnParseToken second = tokens.peek(1);
            if (second != null && !second.text().equals(",") && !second.text().equals("]")) {
                // It's a dict: :key value
                return parseDictLiteral();
            }
        }

        // Array/set literal
        List<SpnExpressionNode> elements = new ArrayList<>();
        while (!tokens.check("]")) {
            elements.add(parseExpression());
            tokens.match(",");
        }
        tokens.expect("]");

        SpnArrayLiteralNode lit = new SpnArrayLiteralNode(FieldType.UNTYPED,
                elements.toArray(new SpnExpressionNode[0]));
        trackType(lit, FieldType.ofArray(FieldType.UNTYPED));
        return lit;
    }

    private SpnExpressionNode parseDictLiteral() {
        List<SpnSymbol> keys = new ArrayList<>();
        List<SpnExpressionNode> values = new ArrayList<>();

        while (!tokens.check("]")) {
            SpnParseToken symTok = tokens.expectType(TokenType.SYMBOL);
            String keyName = symTok.text().substring(1);
            keys.add(symbolTable.intern(keyName));
            values.add(parseExpression());
            tokens.match(",");
        }
        tokens.expect("]");

        return new SpnDictionaryLiteralNode(FieldType.UNTYPED,
                keys.toArray(new SpnSymbol[0]),
                values.toArray(new SpnExpressionNode[0]));
    }

    // ── Lambda expression ──────────────────────────────────────────────────
    //
    // Syntax: (params) -> expr
    //   - zero args:  () -> expr
    //   - one arg:    (x) -> expr
    //   - multi:      (x, y, z) -> expr
    //
    // Params are untyped. Body is a single expression (use a {...} block for
    // multi-statement bodies). Lambdas do NOT close over outer locals —
    // they run in their own frame. Referencing a name defined in the
    // enclosing scope from inside a lambda body will fail with "Unknown
    // variable". If you need a closure, declare a named function and pass
    // its reference.

    /**
     * Peek ahead to decide if the current '(' begins a lambda. A lambda's
     * header matches the grammar {@code ( ) | ( ident ( , ident )* )}
     * and is immediately followed by {@code ->}. Anything else (typed
     * params, expressions, tuple literals) falls back to parenOrTuple.
     */
    private boolean looksLikeLambda() {
        int i = 1;
        SpnParseToken t = tokens.peek(i);
        if (t == null) return false;
        if (t.text().equals(")")) {
            SpnParseToken next = tokens.peek(i + 1);
            return next != null && next.text().equals("->");
        }
        while (true) {
            t = tokens.peek(i);
            if (t == null) return false;
            if (t.type() != TokenType.IDENTIFIER) return false;
            i++;
            t = tokens.peek(i);
            if (t == null) return false;
            if (t.text().equals(")")) {
                SpnParseToken next = tokens.peek(i + 1);
                return next != null && next.text().equals("->");
            }
            if (!t.text().equals(",")) return false;
            i++;
        }
    }

    private SpnExpressionNode parseLambda() {
        SpnParseToken openTok = tokens.expect("(");
        List<String> paramNames = new ArrayList<>();
        while (!tokens.check(")")) {
            SpnParseToken pTok = tokens.advance();
            paramNames.add(pTok.text());
            tokens.match(",");
        }
        tokens.expect(")");
        tokens.expect("->");

        pushScope();
        int[] paramSlots = new int[paramNames.size()];
        for (int i = 0; i < paramNames.size(); i++) {
            paramSlots[i] = currentScope.addLocal(paramNames.get(i));
        }

        SpnExpressionNode body = parseExpression();

        FrameDescriptor frame = popScope().buildFrame();

        SpnFunctionDescriptor.Builder descBuilder = SpnFunctionDescriptor.pure("__lambda__");
        for (String pn : paramNames) {
            descBuilder.param(pn);
        }
        SpnFunctionDescriptor descriptor = descBuilder.build();

        SpnFunctionRootNode fnRoot = new SpnFunctionRootNode(
                language, frame, descriptor, paramSlots, body);
        return at(new SpnFunctionRefNode(fnRoot.getCallTarget()), openTok);
    }

    // ── do(params) { body } closure ────────────────────────────────────────
    //
    // Produces a SpnBoundClosure over the enclosing stateful block's `this`.
    // Body runs in its own frame where `this` is the first implicit arg.
    // Inside the body, `this.*` reads/writes the instance; new ad-hoc fields
    // cannot be introduced (block body only).

    private SpnExpressionNode parseDoClosure() {
        SpnParseToken doTok = tokens.expect("do");
        if (currentStatefulBlock == null) {
            throw tokens.error(
                    "do() closure is only valid inside a stateful block", doTok);
        }
        tokens.expect("(");
        List<String> paramNames = new ArrayList<>();
        while (!tokens.check(")")) {
            paramNames.add(tokens.advance().text());
            tokens.match(",");
        }
        tokens.expect(")");
        tokens.expect("{");

        // Fresh scope: the closure has its own frame. `__this__` is slot 0;
        // user params follow.
        pushScope();
        int bodyThisSlot = currentScope.addLocal("__this__");
        int[] paramSlots = new int[paramNames.size()];
        for (int i = 0; i < paramNames.size(); i++) {
            paramSlots[i] = currentScope.addLocal(paramNames.get(i));
        }
        int[] allSlots = new int[paramNames.size() + 1];
        allSlots[0] = bodyThisSlot;
        System.arraycopy(paramSlots, 0, allSlots, 1, paramNames.size());

        // Swap in a do()-body context: shape is shared (can't be extended),
        // thisSlot points to the body-frame slot.
        StatefulBlockContext outer = currentStatefulBlock;
        StatefulBlockContext bodyCtx = new StatefulBlockContext(
                outer.typeName, outer.shape, bodyThisSlot);
        bodyCtx.inDoBody = true;
        currentStatefulBlock = bodyCtx;

        // Closure bodies are action-scoped — allow impure calls.
        boolean outerPure = currentFunctionIsPure;
        currentFunctionIsPure = false;

        SpnExpressionNode body;
        try {
            body = parseBlockBody();
        } finally {
            currentStatefulBlock = outer;
            currentFunctionIsPure = outerPure;
        }
        tokens.expect("}");

        FrameDescriptor frame = popScope().buildFrame();

        SpnFunctionDescriptor.Builder descBuilder = SpnFunctionDescriptor.impure("__do__");
        descBuilder.param("__this__");
        for (String pn : paramNames) descBuilder.param(pn);
        SpnFunctionDescriptor desc = descBuilder.build();

        SpnFunctionRootNode fnRoot = new SpnFunctionRootNode(
                language, frame, desc, allSlots, body);
        CallTarget target = fnRoot.getCallTarget();

        // At runtime, wrap the shared CallTarget with the current this-ref.
        SpnExpressionNode outerThisRead = SpnReadLocalVariableNodeGen.create(outer.thisSlot);
        return at(new spn.node.stateful.SpnDoClosureNode(outerThisRead, target), doTok);
    }

    // ── this.* field access inside a stateful block ────────────────────────

    private SpnExpressionNode parseStatefulThisAccess(SpnParseToken thisTok) {
        tokens.expect(".");
        SpnParseToken fieldTok = tokens.advance();
        String fieldName = fieldTok.text();

        // Reads reference the current shape; writes are handled in
        // parseExpressionStatement where we still have the `=` token.
        // Here, for reads we verify the field exists.
        if (!currentStatefulBlock.shape.containsKey(fieldName)
                && !tokens.check("=")) {
            throw tokens.error("Unknown field '" + fieldName + "' on stateful "
                    + currentStatefulBlock.typeName, fieldTok);
        }

        SpnExpressionNode instanceRead = SpnReadLocalVariableNodeGen.create(
                currentStatefulBlock.thisSlot);
        return at(new spn.node.stateful.SpnStatefulFieldReadNode(instanceRead, fieldName), thisTok);
    }

    // ── Stateful block: T(args) { body } ───────────────────────────────────

    private SpnExpressionNode parseStatefulBlock(String typeName,
                                                 List<SpnExpressionNode> initArgs,
                                                 SpnParseToken nameTok) {
        if (currentFunctionIsPure) {
            throw tokens.error("Pure function cannot create a stateful instance of "
                    + typeName, nameTok);
        }

        LinkedHashMap<String, FieldType> declared = statefulDeclaredShapes.get(typeName);
        if (declared == null) {
            throw tokens.error("Stateful type '" + typeName + "' has no declared fields",
                    nameTok);
        }
        if (initArgs.size() != declared.size()) {
            throw tokens.error("Stateful " + typeName + " expects "
                    + declared.size() + " init args, got " + initArgs.size(), nameTok);
        }

        // Allocate a local slot in the CURRENT frame for the instance —
        // the block body runs in the enclosing scope, not a nested one.
        String thisSlotName = "__this_" + typeName + "_" + System.identityHashCode(nameTok);
        int thisSlot = currentScope.addLocal(thisSlotName);

        // Fresh shape = declared fields (copied — block body may extend).
        LinkedHashMap<String, FieldType> shape = new LinkedHashMap<>(declared);

        StatefulBlockContext ctx = new StatefulBlockContext(typeName, shape, thisSlot);
        StatefulBlockContext outer = currentStatefulBlock;
        currentStatefulBlock = ctx;
        try {
            tokens.expect("{");
            SpnExpressionNode body = parseBlockBody();
            tokens.expect("}");
            return at(new spn.node.stateful.SpnStatefulBlockNode(
                    typeName,
                    declared.keySet().toArray(new String[0]),
                    initArgs.toArray(new SpnExpressionNode[0]),
                    thisSlot,
                    body), nameTok);
        } finally {
            currentStatefulBlock = outer;
        }
    }

    // ── Parenthesized expression or tuple ──────────────────────────────────

    private SpnExpressionNode parseParenOrTuple() {
        tokens.expect("(");
        if (tokens.check(")")) {
            tokens.advance();
            return new SpnLongLiteralNode(0); // unit / empty tuple
        }

        SpnExpressionNode first = parseExpression();

        if (tokens.check(")")) {
            tokens.advance();
            return first; // plain parenthesized expression
        }

        // Tuple: (a, b, c)
        List<SpnExpressionNode> elements = new ArrayList<>();
        elements.add(first);
        while (tokens.match(",")) {
            elements.add(parseExpression());
        }
        tokens.expect(")");

        // Build a tuple descriptor that carries each element's inferred type
        // (falling back to UNTYPED when inference didn't determine one). This
        // lets destructuring propagate element types into the bindings.
        FieldType[] elementTypes = new FieldType[elements.size()];
        for (int i = 0; i < elements.size(); i++) {
            FieldType t = inferType(elements.get(i));
            elementTypes[i] = t != null ? t : FieldType.UNTYPED;
        }
        return new spn.node.struct.SpnTupleConstructNode(
                new SpnTupleDescriptor(elementTypes),
                elements.toArray(new SpnExpressionNode[0]));
    }

    // ── Match expression ───────────────────────────────────────────────────

    private SpnExpressionNode parseMatchExpression() {
        SpnParseToken matchTok = tokens.expect("match");

        // Subject-less guard form: `match | cond -> expr | _ -> default`.
        // Each arm's condition is evaluated in order; the first true one wins,
        // and the required wildcard `| _ -> ...` arm acts as the default.
        // Compiled to a SpnMatchNode with `true` as a synthetic subject, each
        // non-wildcard arm being (Wildcard, guard=cond), and the wildcard arm
        // having no guard.
        if (tokens.check("|")) {
            return parseGuardMatch(matchTok);
        }

        SpnExpressionNode subject = parseExpression();
        FieldType subjectType = inferType(subject);

        List<SpnMatchBranchNode> branches = new ArrayList<>();
        FieldType resultType = null;
        while (tokens.match("|")) {
            SpnMatchBranchNode branch = parseMatchBranch();
            branches.add(branch);
            resultType = unifyTypes(resultType, inferType(branch.getBody()));
            // Categorical check: reject patterns that can't match the subject
            if (subjectType != null) {
                String mismatch = patternCategoryMismatch(branch.getPattern(), subjectType);
                if (mismatch != null) {
                    throw tokens.error("Pattern cannot match subject of type "
                            + subjectType.describe() + ": " + mismatch, matchTok);
                }
            }
        }

        if (branches.isEmpty()) {
            throw tokens.error("Match expression must have at least one branch");
        }

        // Exhaustiveness check on union/variant subjects
        if (subjectType instanceof FieldType.OfVariant ov) {
            MatchPattern[] patterns = new MatchPattern[branches.size()];
            for (int i = 0; i < branches.size(); i++) {
                patterns[i] = branches.get(i).getPattern();
            }
            SpnVariantSet vs = ov.variantSet();
            if (!vs.isCoveredBy(patterns)) {
                SpnStructDescriptor[] missing = vs.uncoveredVariants(patterns);
                var names = new StringBuilder();
                for (int i = 0; i < missing.length; i++) {
                    if (i > 0) names.append(", ");
                    names.append(missing[i].getName());
                }
                throw tokens.error("Non-exhaustive match on " + vs.getName()
                        + ": missing pattern(s) for " + names, matchTok);
            }
        }

        SpnExpressionNode matchNode = new SpnMatchNode(subject,
                branches.toArray(new SpnMatchBranchNode[0]));
        if (resultType != null) trackType(matchNode, resultType);
        return matchNode;
    }

    /**
     * Parse the subject-less guard form of match: {@code match | cond -> expr
     * | cond -> expr | _ -> default}. Each non-wildcard arm must be
     * {@code cond -> body} where {@code cond} is a boolean expression. The final
     * {@code | _ -> default} arm is required for totality.
     *
     * <p>Compiled as a plain {@link SpnMatchNode} with a synthetic {@code true}
     * subject. Each guard arm is {@code Wildcard + guard=cond}, which matches
     * iff the guard is true; the final wildcard arm has no guard and matches
     * unconditionally. The existing tryMatch semantics — first arm whose
     * pattern matches and whose guard (if any) is true wins — is exactly what
     * guard-match needs.
     */
    private SpnExpressionNode parseGuardMatch(SpnParseToken matchTok) {
        List<SpnMatchBranchNode> branches = new ArrayList<>();
        boolean sawWildcard = false;

        while (tokens.match("|")) {
            SpnExpressionNode guard;
            boolean thisArmIsWildcard = false;

            // Recognize `_ ->` as the unconditional default. We peek for the
            // arrow so an identifier that happens to be `_` inside a larger
            // expression doesn't get misinterpreted.
            SpnParseToken first = tokens.peek();
            SpnParseToken second = tokens.peek(1);
            if (first != null && "_".equals(first.text())
                    && second != null && "->".equals(second.text())) {
                tokens.advance(); // consume _
                thisArmIsWildcard = true;
                guard = null;
            } else {
                guard = parseExpression();
            }

            tokens.expect("->");
            SpnExpressionNode body = parseExpression();

            branches.add(new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[0], guard, body));

            if (thisArmIsWildcard) {
                sawWildcard = true;
                // A wildcard arm is unconditional — any later arms are dead.
                // Stop parsing further `|` arms so we don't consume them.
                break;
            }
        }

        if (branches.isEmpty()) {
            throw tokens.error("Guard-match needs at least one '| cond -> body' arm", matchTok);
        }
        if (!sawWildcard) {
            throw tokens.error(
                    "Guard-match must end with '| _ -> default' for totality", matchTok);
        }

        SpnExpressionNode subject = new spn.node.expr.SpnBooleanLiteralNode(true);
        return new SpnMatchNode(subject, branches.toArray(new SpnMatchBranchNode[0]));
    }

    private SpnMatchBranchNode parseMatchBranch() {
        PatternParser.ParsedPattern pp = patternParser.parsePattern();
        // Optional guard: | x | x > 0 -> ...
        SpnExpressionNode guard = null;
        if (tokens.check("|")) {
            tokens.advance();
            guard = parseExpression();
        }

        tokens.expect("->");
        SpnExpressionNode body = parseExpression();

        return new SpnMatchBranchNode(pp.pattern(), pp.bindingSlots(), guard, body);
    }

    // Pattern parsing lives in {@link PatternParser} (wired up in the
    // constructor). The parsePattern / parseNestedPattern / parseLiteralValue
    // methods moved there intact; currentScope.addLocal() is routed through a
    // ScopeProvider adapter so PatternParser never sees the Scope class.
    // Type-expression parsing lives in {@link TypeParser}. Thin delegates below
    // keep existing call sites unchanged; the real grammar is in that class.

    private FieldType parseFieldType() {
        return typeParser.parseFieldType();
    }

    private void collectUnionMembers(List<SpnStructDescriptor> variants, FieldType type) {
        typeParser.collectUnionMembers(variants, type);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private SpnSymbol parseSymbolValue() {
        SpnParseToken tok = tokens.expectType(TokenType.SYMBOL);
        return symbolTable.intern(tok.text().substring(1));
    }

    private String unescapeString(String raw) {
        // Strip quotes
        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        return raw.replace("\\n", "\n")
                  .replace("\\t", "\t")
                  .replace("\\\\", "\\")
                  .replace("\\\"", "\"");
    }

    private boolean isUnaryMinus() {
        // A '-' on a new line after an expression is the start of a new statement
        // (prefix negation), not a binary subtraction continuing the previous line.
        // Same principle as the same-line rule for function calls.
        SpnParseToken prev = tokens.lastConsumed();
        SpnParseToken minus = tokens.peek();
        if (prev != null && minus != null && minus.line() > prev.line()) {
            return true;
        }
        return false;
    }


    // ── Helper wrapper for tracking variable read slots ────────────────────

    /**
     * Thin wrapper around SpnReadLocalVariableNode that remembers its slot
     * so the parser can convert reads to writes for reassignment (x = expr).
     */
    private static final class SpnReadLocalVariableNodeWrapper extends SpnExpressionNode {
        final int slot;
        @Child private SpnExpressionNode delegate;

        SpnReadLocalVariableNodeWrapper(int slot) {
            this.slot = slot;
            this.delegate = SpnReadLocalVariableNodeGen.create(slot);
        }

        @Override
        public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
            return delegate.executeGeneric(frame);
        }

        @Override
        public long executeLong(com.oracle.truffle.api.frame.VirtualFrame frame)
                throws com.oracle.truffle.api.nodes.UnexpectedResultException {
            return delegate.executeLong(frame);
        }

        @Override
        public double executeDouble(com.oracle.truffle.api.frame.VirtualFrame frame)
                throws com.oracle.truffle.api.nodes.UnexpectedResultException {
            return delegate.executeDouble(frame);
        }

        @Override
        public boolean executeBoolean(com.oracle.truffle.api.frame.VirtualFrame frame)
                throws com.oracle.truffle.api.nodes.UnexpectedResultException {
            return delegate.executeBoolean(frame);
        }
    }

    // ── Deferred invoke (for recursive functions) ────────────────────────

    private static final class SpnDeferredInvokeNode extends SpnExpressionNode {
        final String name;
        @Children private final SpnExpressionNode[] argNodes;
        @Child private com.oracle.truffle.api.nodes.IndirectCallNode callNode =
                com.oracle.truffle.api.Truffle.getRuntime().createIndirectCallNode();
        private CallTarget target;

        SpnDeferredInvokeNode(String name, SpnExpressionNode[] argNodes) {
            this.name = name;
            this.argNodes = argNodes;
        }

        void resolve(CallTarget target) {
            this.target = target;
        }

        @Override
        @com.oracle.truffle.api.nodes.ExplodeLoop
        public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
            Object[] args = new Object[argNodes.length];
            for (int i = 0; i < argNodes.length; i++) {
                args[i] = argNodes[i].executeGeneric(frame);
            }
            return callNode.call(target, args);
        }
    }

    // ── Block expression (executes statements, returns last value) ──────────

    private static final class SpnBlockExprNode extends SpnExpressionNode {
        @Children private final SpnStatementNode[] statements;

        SpnBlockExprNode(SpnStatementNode[] statements) {
            this.statements = statements;
        }

        @Override
        @com.oracle.truffle.api.nodes.ExplodeLoop
        public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
            Object result = 0L;
            for (SpnStatementNode stmt : statements) {
                if (stmt instanceof SpnExpressionNode expr) {
                    result = expr.executeGeneric(frame);
                } else {
                    stmt.executeVoid(frame);
                }
            }
            return result;
        }
    }
}
