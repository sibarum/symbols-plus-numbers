package spn.lang;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import spn.language.ImportDirective;
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

    // Method registry: "TypeName.methodName" → (CallTarget, SpnFunctionDescriptor)
    record MethodEntry(CallTarget callTarget, SpnFunctionDescriptor descriptor) {}
    private final Map<String, MethodEntry> methodRegistry = new LinkedHashMap<>();

    // Factory registry: "TypeName" → list of (CallTarget, arity) for overloaded factories
    record FactoryEntry(CallTarget callTarget, int arity, SpnFunctionDescriptor descriptor) {}
    private final Map<String, List<FactoryEntry>> factoryRegistry = new LinkedHashMap<>();

    // Macro registry: "Name" → MacroDef (compile-time code template)
    record MacroDef(String name, List<String> paramNames, List<SpnParseToken> bodyTokens) {}

    // Macro expansion state
    private boolean insideMacroExpansion = false;
    private final Set<String> macroEmittedNames = new HashSet<>();
    private static final String MACRO_END_SENTINEL = "__MACRO_END__";
    private int macroExpansionCounter = 0; // unique suffix for internal type names
    private final Map<String, MacroDef> macroRegistry = new LinkedHashMap<>();

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
        this.typeParser = new TypeParser(
                tokens, structRegistry, typeRegistry, variantRegistry,
                functionDescriptorRegistry);
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
            "import", "module", "version", "require", "macro", "promote"
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
    public List<Promotion> getPromotionRegistry() { return promotionRegistry; }

    // ── Top-level parsing ──────────────────────────────────────────────────

    private SpnStatementNode parseTopLevel() {
        SpnParseToken tok = tokens.peek();
        if (tok == null) return null;

        // Macro invocation: Name(args) where Name is a registered macro.
        // Detect before the keyword switch so macro names can't collide with keywords.
        if (macroRegistry.containsKey(tok.text())) {
            SpnParseToken next = tokens.peek(1);
            if (next != null && next.text().equals("(")) {
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
            default -> parseExpressionStatement();
        };
    }

    // ── Macros ─────────────────────────────────────────────────────────────

    /**
     * Parse: macro Name(P1, P2, ...) = { body }
     * The body is captured as a raw token sequence; parameters are substituted
     * textually at invocation time.
     */
    private void parseMacroDecl() {
        tokens.expect("macro");
        SpnParseToken nameTok = tokens.advance();
        String name = nameTok.text();

        // Parameter list: (P1, P2, ...)
        tokens.expect("(");
        List<String> paramNames = new ArrayList<>();
        while (!tokens.check(")")) {
            paramNames.add(tokens.advance().text());
            tokens.match(",");
        }
        tokens.expect(")");

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
        macroRegistry.put(name, new MacroDef(name, paramNames, bodyTokens));
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

        tokens.expect("(");
        List<List<SpnParseToken>> args = new ArrayList<>();
        while (tokens.hasMore() && !tokens.check(")")) {
            List<SpnParseToken> arg = new ArrayList<>();
            int depth = 0;
            while (tokens.hasMore()) {
                String t = tokens.peek().text();
                if (depth == 0 && (t.equals(",") || t.equals(")"))) break;
                if (t.equals("(") || t.equals("[") || t.equals("{")) depth++;
                else if (t.equals(")") || t.equals("]") || t.equals("}")) depth--;
                arg.add(tokens.advance());
            }
            args.add(arg);
            if (!tokens.hasMore()) break;
            tokens.match(",");
        }
        tokens.expect(")");

        if (args.size() != macro.paramNames().size()) {
            throw tokens.error("Macro '" + macro.name() + "' expects "
                    + macro.paramNames().size() + " argument(s), got " + args.size(), nameTok);
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
        macroEmittedNames.clear();

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

        if (macroEmittedNames.isEmpty()) {
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
        for (String emitted : macroEmittedNames) {
            if (key.equals(emitted) || key.startsWith(emitted + ".")) return true;
        }
        return false;
    }

    /**
     * After a macro expansion in {@code type X = macroCall(T)} context, find
     * the emitted type (registered under its internal name) and re-register
     * it under the user's chosen name {@code X}. Also re-register any methods
     * and factories prefixed with the internal name.
     */
    private void renameMacroEmittedType(String targetName, SpnParseToken nameTok) {
        if (macroEmittedNames.isEmpty()) {
            throw tokens.error("Macro did not emit a type — use 'emit TypeName' in the macro body", nameTok);
        }

        // Take the first emitted name as the source type
        String internalName = macroEmittedNames.iterator().next();

        // Register the struct descriptor under the user's chosen name as an ALIAS.
        // Keep the internal name too — compiled CallTargets reference the original
        // descriptor, so lookups by the internal name must still work.
        SpnStructDescriptor sd = structRegistry.get(internalName);
        if (sd != null) {
            structRegistry.put(targetName, sd);
            typeGraph.add(TypeGraph.Node.builder(targetName, TypeGraph.Kind.TYPE)
                    .file(sourceName).line(nameTok.line()).col(nameTok.col())
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
     * <p>Only valid inside a macro expansion. Records the name as "emitted" so
     * it survives the macro scope revert. The emitted declaration (and any
     * methods prefixed with its name) persist into the caller's scope.
     */
    private void parseEmit() {
        SpnParseToken emitTok = tokens.expect("emit");
        if (!insideMacroExpansion) {
            throw tokens.error("'emit' can only be used inside a macro body", emitTok);
        }
        SpnParseToken nameTok = tokens.advance();
        macroEmittedNames.add(nameTok.text());
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
     * Skip a module declaration (module com.mysite.mymodule).
     * Module declarations are only meaningful in module.spn files,
     * which are parsed by ModuleParser. In regular source files
     * they are silently skipped.
     */
    private void skipModuleDecl() {
        tokens.expect("module");
        tokens.advance(); // first segment
        while (tokens.match(".")) {
            tokens.advance(); // next segment
        }
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
                tokens.advance(); // consume '.'
                SpnParseToken segment = tokens.advance();
                path.append(".").append(segment.text());
            }
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
        resolveAndApplyImport(directive);
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
        // Import extended registries (methods, factories, operators, descriptors)
        Map<String, MethodEntry> methods = module.getExtra("methods");
        if (methods != null) methodRegistry.putAll(methods);
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
        // Methods: keys like "TypeName.methodName"
        Map<String, MethodEntry> methods = module.getExtra("methods");
        if (methods != null) {
            for (var entry : methods.entrySet()) {
                if (entry.getKey().startsWith(typeName + ".")) {
                    methodRegistry.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // Factories: keyed by type name
        Map<String, List<FactoryEntry>> factories = module.getExtra("factories");
        if (factories != null) {
            List<FactoryEntry> typeFactories = factories.get(typeName);
            if (typeFactories != null) {
                factoryRegistry.computeIfAbsent(typeName, k -> new ArrayList<>())
                        .addAll(typeFactories);
            }
        }

        // Constants: keys like "TypeName.constName"
        Map<String, ConstantEntry> constants = module.getExtra("constants");
        if (constants != null) {
            for (var entry : constants.entrySet()) {
                if (entry.getKey().startsWith(typeName + ".")) {
                    constantRegistry.put(entry.getKey(), entry.getValue());
                }
            }
        }

        // Operator overloads: import any overload where the type participates
        Map<String, List<OperatorOverload>> operators = module.getExtra("operators");
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

    private void parseTypeDecl() {
        SpnParseToken typeTok = tokens.expect("type");
        SpnParseToken nameTok = tokens.expectType(TokenType.TYPE_NAME);
        String name = nameTok.text();

        // type Name = macroCall(args)  OR  type Name = BaseType [where validatorClosure]
        if (tokens.match("=")) {
            // Check for macro call: type X = macroName(args)
            SpnParseToken rhsTok = tokens.peek();
            SpnParseToken rhsNext = tokens.peek(1);
            if (rhsTok != null && rhsNext != null
                    && macroRegistry.containsKey(rhsTok.text())
                    && rhsNext.text().equals("(")) {
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
                    .file(sourceName).line(nameTok.line()).col(nameTok.col())
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
                    .file(sourceName).line(nameTok.line()).col(nameTok.col())
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
                    String compName = tokens.advance().text();
                    tokens.expect(":");
                    FieldType ft = parseFieldType();
                    builder.component(compName, ft);
                    structBuilder.field(compName, ft);
                } else {
                    FieldType ft = parseFieldType();
                    String compName = "_" + position;
                    builder.component(compName, ft);
                    structBuilder.field(compName, ft);
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
                    .file(sourceName).line(nameTok.line()).col(nameTok.col())
                    .typeDescriptor(td).structDescriptor(sd).build());
            return;
        }

        // Bare opaque type: `type TypeName` — no fields declared.
        // Fields are defined via `let this.field = expr` in the constructor.
        // Creates an empty struct descriptor that gets populated by the factory.
        SpnStructDescriptor sd = SpnStructDescriptor.builder(name).build();
        structRegistry.put(name, sd);
        typeGraph.add(TypeGraph.Node.builder(name, TypeGraph.Kind.STRUCT)
                .file(sourceName).line(nameTok.line()).col(nameTok.col())
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
                    .file(sourceName).callTarget(ref.getCallTarget())
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
                while (!tokens.check(")")) {
                    bindings.add(tokens.advance().text());
                    tokens.match(",");
                }
                tokens.expect(")");
                params.add(ParamDecl.destructured(bindings));
            } else {
                params.add(ParamDecl.simple(tokens.advance().text()));
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
                    }
                }
                destructureNodes.add(new spn.node.local.SpnDestructureNode(
                        SpnReadLocalVariableNodeGen.create(paramSlots[i]), bindSlots));
            } else {
                FieldType ft = i < paramTypes.size() ? paramTypes.get(i) : null;
                paramSlots[i] = currentScope.addLocal(p.name(), ft);
                paramNames.add(p.name());
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
                .file(sourceName).line(nameTok.line()).col(nameTok.col())
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
        String constName = tokens.expectType(TokenType.IDENTIFIER).text();
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
            String fieldName = tokens.expectType(TokenType.IDENTIFIER).text();
            if (tokens.match(":")) {
                FieldType ft = parseFieldType();
                typeBuilder.component(fieldName, ft);
                structBuilder.field(fieldName, ft);
            } else {
                typeBuilder.component(fieldName, FieldType.UNTYPED);
                structBuilder.field(fieldName);
            }
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
            if (next.equals(".")) return parseMethodDecl(isPure, nameTok);
            if (next.equals("(")) return parseFactoryDecl(isPure, nameTok);
        }
        if (nameTok.type() == TokenType.OPERATOR) return parseOperatorOrFuncDecl(isPure, true);
        return parseOperatorOrFuncDecl(isPure, false);
    }

    /** Parse: pure TypeName.method(args) -> ReturnType = (params) { body } */
    private SpnStatementNode parseMethodDecl(boolean isPure, SpnParseToken nameTok) {
        String typeName = tokens.advance().text(); // consume TypeName
        tokens.advance(); // consume '.'
        String methodName = expectIdentifier().text();

        List<FieldType> paramTypes = parseParamTypeList();
        FieldType returnType = parseOptionalReturnType();

        // Prepend receiver type
        FieldType receiverType = resolveTypeByName(typeName);
        if (receiverType == null) throw tokens.error("Unknown type for method: " + typeName, nameTok);
        paramTypes.add(0, receiverType);

        String methodKey = typeName + "." + methodName;

        // Declaration only (no =), just register the signature
        if (!tokens.check("=")) {
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
        parseFunctionBody(methodKey, paramTypes, returnType, isPure, true);
        currentMethodTypeName = outerMethodType;
        return null;
    }

    /** Parse: pure TypeName(args) -> ReturnType = (params) { body } */
    private SpnStatementNode parseFactoryDecl(boolean isPure, SpnParseToken nameTok) {
        String typeName = tokens.advance().text(); // consume TypeName

        List<FieldType> paramTypes = parseParamTypeList();
        FieldType returnType = parseOptionalReturnType();

        if (!tokens.check("=")) return null; // declaration only
        tokens.expect("=");

        // Use arity-qualified name to prevent collisions between overloaded factories
        String qualifiedName = typeName + "/" + paramTypes.size();

        // Set factory context so this(args) resolves to raw construction
        String outerFactory = currentFactoryTypeName;
        int outerArity = currentFactoryArity;
        currentFactoryTypeName = typeName;
        currentFactoryArity = paramTypes.size();

        parseFunctionBody(qualifiedName, paramTypes, returnType, isPure, false);

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
        String name = isOperator ? tokens.advance().text()
                : expectIdentifier().text();

        List<FieldType> paramTypes = parseParamTypeList();
        FieldType returnType = parseOptionalReturnType();

        if (!tokens.check("=")) return null; // declaration only
        tokens.expect("=");

        parseFunctionBody(name, paramTypes, returnType, isPure, false);

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
                        .file(sourceName).callTarget(ct)
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

    /** A parameter binding: either a simple name or a positional destructure (a, b, c). */
    private record ParamDecl(String name, List<String> destructureBindings) {
        boolean isDestructured() { return destructureBindings != null; }
        static ParamDecl simple(String name) { return new ParamDecl(name, null); }
        static ParamDecl destructured(List<String> bindings) { return new ParamDecl(null, bindings); }
    }

    private SpnStatementNode parseFunctionBody(String name, List<FieldType> paramTypes,
                                                FieldType returnType, boolean isPure) {
        return parseFunctionBody(name, paramTypes, returnType, isPure, false);
    }

    private SpnStatementNode parseFunctionBody(String name, List<FieldType> paramTypes,
                                                FieldType returnType, boolean isPure, boolean isMethod) {
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
                while (!tokens.check(")")) {
                    bindings.add(tokens.advance().text());
                    tokens.match(",");
                }
                tokens.expect(")");
                params.add(ParamDecl.destructured(bindings));
            } else {
                // Simple param: name
                params.add(ParamDecl.simple(tokens.expectType(TokenType.IDENTIFIER).text()));
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
                    }
                }
                destructureNodes.add(new spn.node.local.SpnDestructureNode(
                        SpnReadLocalVariableNodeGen.create(userParamSlots[i]), bindSlots));
            } else {
                FieldType ft = i < paramTypes.size() ? paramTypes.get(i) : null;
                userParamSlots[i] = currentScope.addLocal(p.name(), ft);
                paramNames.add(p.name());
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

        // Record in TypeGraph
        TypeGraph.Kind gKind = isMethod ? TypeGraph.Kind.METHOD : TypeGraph.Kind.FUNCTION;
        TypeGraph.Node.Builder gb = TypeGraph.Node.builder(name, gKind)
                .file(sourceName).pure(isPure).callTarget(callTarget)
                .functionDescriptor(descriptor).returnType(returnType)
                .paramTypes(paramTypes.toArray(new FieldType[0]));
        if (bodyTok != null) gb.line(bodyTok.line()).col(bodyTok.col());
        typeGraph.add(gb.build());

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

        String name = expectIdentifier().text();

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
        var writeNode = SpnWriteLocalVariableNodeGen.create(value, slot);
        writeNode.setVariableName(name);
        return writeNode;
    }

    /**
     * Parse: let TypeName(a, b, c) = expr
     * Destructures a struct/product value into local bindings.
     */
    private SpnStatementNode parseLetDestructure() {
        String typeName = tokens.advance().text(); // consume type name
        tokens.expect("(");
        List<String> bindNames = new ArrayList<>();
        while (!tokens.check(")")) {
            String bname = tokens.advance().text();
            bindNames.add(bname);
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
        while (!tokens.check(")")) {
            String bname = tokens.advance().text();
            bindNames.add(bname);
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
            if (tokens.check("[")) {
                requireTyped(expr, "indexing");
                tokens.advance();
                SpnExpressionNode index = parseExpression();
                tokens.expect("]");
                expr = SpnArrayAccessNodeGen.create(expr, index);
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
                    if (method != null) {
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
                        throw tokens.error("Unknown method '" + memberName + "'"
                                + (receiverType != null ? " on type " + receiverType.describe() : ""), nameTok);
                    }
                } else {
                    // Field access: expr.field
                    FieldType receiverType = inferType(expr);
                    int fieldIndex = resolveFieldIndex(expr, memberName);
                    expr = spn.node.struct.SpnFieldAccessNodeGen.create(expr, fieldIndex);

                    // Track the field's type if known (from the receiver's descriptor)
                    trackFieldType(expr, memberName, receiverType);
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

        // Parenthesized expression or tuple
        if (tok.text().equals("(")) {
            return parseParenOrTuple();
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
                return builtin.create(args.toArray(new SpnExpressionNode[0]));
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
            return new SpnArrayLiteralNode(FieldType.UNTYPED);
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

        return new SpnArrayLiteralNode(FieldType.UNTYPED,
                elements.toArray(new SpnExpressionNode[0]));
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
