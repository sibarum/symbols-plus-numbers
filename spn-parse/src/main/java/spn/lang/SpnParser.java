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
import spn.node.ctrl.SpnIfNode;
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

    // Promotion registry: sourceType → (targetType, conversionCallTarget)
    record Promotion(String sourceDesc, String targetDesc, CallTarget converter) {}
    private final List<Promotion> promotionRegistry = new ArrayList<>();

    // Compile-time type tracking for expressions (used by operator dispatch)
    private final java.util.IdentityHashMap<SpnExpressionNode, FieldType> exprTypes = new java.util.IdentityHashMap<>();

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
    }

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

    private void pushScope() {
        currentScope = new Scope(currentScope);
    }

    private Scope popScope() {
        Scope scope = currentScope;
        currentScope = currentScope.parent;
        return scope;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Parses the full source into an SpnRootNode ready for execution.
     * Type/struct/function declarations are registered; top-level statements
     * are collected into a block.
     */
    public SpnRootNode parse() {
        pushScope();
        List<SpnStatementNode> statements = new ArrayList<>();

        while (tokens.hasMore()) {
            SpnStatementNode stmt = parseTopLevel();
            if (stmt != null) {
                statements.add(stmt);
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
        return new SpnRootNode(language, scope.buildFrame(), body, "<main>");
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
            case "if" -> parseIfStatement();
            case "yield" -> parseYieldStatement();
            case "return" -> parseYieldStatement(); // return is syntactic sugar for yield
            case "macro" -> { parseMacroDecl(); yield null; }
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
     * Substitutes argument tokens for parameter occurrences in the body, then
     * injects the substituted tokens back into the stream for normal parsing.
     */
    private void expandMacroInvocation() {
        SpnParseToken nameTok = tokens.advance();
        MacroDef macro = macroRegistry.get(nameTok.text());

        tokens.expect("(");
        List<List<SpnParseToken>> args = new ArrayList<>();
        // Guard against EOF: must also check hasMore() so we don't spin forever
        // when the user is mid-typing and the closing ')' hasn't been written yet.
        while (tokens.hasMore() && !tokens.check(")")) {
            // Each argument is a single token sequence terminated by , or )
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
            // If we reached EOF without hitting a comma or ), bail rather than loop.
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

        // Substitute: walk body tokens, replace any whose text matches a param.
        // Re-stamp substituted tokens with the position of the parameter they
        // replaced, so the parser sees them as living "inside" the macro body
        // (line-aware checks like function-call detection work correctly).
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

        // Inject expanded tokens at current position; parser will pick them up
        tokens.injectAt(expanded);
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
        tokens.expect("type");
        String name = tokens.expectType(TokenType.TYPE_NAME).text();

        // type Name = BaseType [where validatorClosure]
        if (tokens.match("=")) {
            SpnTypeDescriptor.Builder builder = SpnTypeDescriptor.builder(name);
            String baseType = tokens.advance().text();
            builder.baseType(baseType);
            if (tokens.match("where")) {
                builder.validatorExpr(parseValidatorClosure());
            }
            typeRegistry.put(name, builder.build());
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
            typeRegistry.put(name, builder.build());
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
            typeRegistry.put(name, builder.build());
            structRegistry.put(name, structBuilder.build());
            return;
        }

        throw tokens.error("Expected '=', 'where', or '(' after type name '" + name + "'");
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

        // Parse the conversion closure: (param) { body }
        SpnExpressionNode converter = parseValidatorClosure();

        // The converter is a SpnFunctionRefNode wrapping a CallTarget
        if (converter instanceof SpnFunctionRefNode ref) {
            promotionRegistry.add(new Promotion(
                    sourceType.describe(), targetType.describe(), ref.getCallTarget()));
        }
    }

    private void parseDataDecl() {
        tokens.expect("data");
        String name = tokens.expectType(TokenType.TYPE_NAME).text();
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

        variantRegistry.put(name, new SpnVariantSet(name, variants.toArray(new SpnStructDescriptor[0])));
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
        String methodName = tokens.expectType(TokenType.IDENTIFIER).text();

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
        parseFunctionBody(methodKey, paramTypes, returnType, isPure, true);
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
                : tokens.expectType(TokenType.IDENTIFIER).text();

        List<FieldType> paramTypes = parseParamTypeList();
        FieldType returnType = parseOptionalReturnType();

        if (!tokens.check("=")) return null; // declaration only
        tokens.expect("=");

        parseFunctionBody(name, paramTypes, returnType, isPure, false);

        // Register operator overload for compile-time dispatch
        if (isOperator && !paramTypes.isEmpty()) {
            CallTarget ct = functionRegistry.get(name);
            if (ct != null) {
                operatorRegistry.computeIfAbsent(name, k -> new ArrayList<>())
                        .add(new OperatorOverload(
                                paramTypes.toArray(new FieldType[0]), returnType, ct));
            }
        }

        // Register inspect function on the struct descriptor
        if (name.equals("inspect") && paramTypes.size() == 1) {
            CallTarget ct = functionRegistry.get(name);
            if (ct != null) {
                String typeName = paramTypes.get(0).describe();
                SpnStructDescriptor desc = structRegistry.get(typeName);
                if (desc != null) {
                    desc.setInspectTarget(ct);
                }
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
        containsYield = false;
        boolean outerPurity = currentFunctionIsPure;
        currentFunctionIsPure = isPure;
        tokens.expect("{");
        SpnParseToken bodyTok = tokens.lastConsumed();
        SpnExpressionNode body = parseBlockBody();
        tokens.expect("}");
        deferredFunctionName = null;
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
            functionRegistry.put(name, callTarget);
        }
        functionDescriptorRegistry.put(name, descriptor);

        // Patch any deferred self-calls
        patchDeferredCalls(name, callTarget);

        // Return null — function definitions don't produce a runtime statement
        return null;
    }

    // Support for recursive function references, yield detection, purity tracking, and factories
    private String deferredFunctionName;
    private boolean containsYield;
    private boolean currentFunctionIsPure;
    private String currentFactoryTypeName; // non-null when inside a factory body
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

        String name = tokens.expectType(TokenType.IDENTIFIER).text();

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

        // Try to infer field types from the value's type
        SpnStructDescriptor desc = null;
        FieldType valueType = inferType(value);
        if (valueType != null) {
            desc = resolveStructDescriptor(valueType);
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
    private FieldType inferType(SpnExpressionNode expr) {
        // Check tracked type first (set by let bindings, variable reads, etc.)
        FieldType tracked = exprTypes.get(expr);
        if (tracked != null) return tracked;
        // Constructor call → the type name
        if (expr instanceof SpnStructConstructNode sc) {
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
        return null; // unknown — requires explicit coercion
    }

    /**
     * Track the result type of a built-in arithmetic node.
     * If both operands are the same primitive type, the result is that type.
     */
    private SpnExpressionNode trackArithmeticType(SpnExpressionNode result,
                                                    SpnExpressionNode left,
                                                    SpnExpressionNode right) {
        FieldType lt = inferType(left);
        FieldType rt = inferType(right);
        if (lt instanceof FieldType.Untyped || rt instanceof FieldType.Untyped) {
            throw tokens.error("Untyped (_) value cannot be used in arithmetic");
        }
        if (lt != null && rt != null && lt == rt) {
            trackType(result, lt);
        } else if (lt == FieldType.LONG && rt == FieldType.DOUBLE
                || lt == FieldType.DOUBLE && rt == FieldType.LONG) {
            trackType(result, FieldType.DOUBLE); // mixed int/float → float
        }
        return result;
    }

    /** Tag an expression with its inferred type for later operator dispatch. */
    private void trackType(SpnExpressionNode expr, FieldType type) {
        if (type != null) exprTypes.put(expr, type);
    }

    /**
     * Unify two types into a single type. Used for inferring the result type
     * of if/match expressions where branches may return different types.
     *
     * <ul>
     *   <li>Same type → that type</li>
     *   <li>null + T → T (best effort)</li>
     *   <li>Different structs → anonymous union (Circle | Rectangle)</li>
     *   <li>Struct + existing union → expanded union</li>
     *   <li>Union + union → merged union</li>
     *   <li>Incompatible (e.g., int + string) → null</li>
     * </ul>
     */
    private FieldType unifyTypes(FieldType a, FieldType b) {
        if (a == null) return b;
        if (b == null) return a;
        if (typesMatch(a, b)) return a;

        // Try to build a union from struct-compatible types
        List<SpnStructDescriptor> variants = new ArrayList<>();
        try {
            collectUnionMembers(variants, a);
            collectUnionMembers(variants, b);
        } catch (SpnParseException e) {
            return null; // incompatible types (primitives etc.) — can't form union
        }

        // Sort and deduplicate
        variants.sort(java.util.Comparator.comparing(SpnStructDescriptor::getName));
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

    /**
     * Ensure an expression does not have untyped (_) type.
     * Untyped values can only be passed to other _ parameters or narrowed via match;
     * they cannot be used in arithmetic, comparisons, indexing, or invocation.
     */
    private void requireTyped(SpnExpressionNode expr, String operation) {
        FieldType type = inferType(expr);
        if (type instanceof FieldType.Untyped) {
            throw tokens.error("Untyped (_) value cannot be used in " + operation);
        }
    }

    /**
     * Check that no untyped (_) arguments are passed to typed parameters.
     * Untyped arguments may only flow into untyped (_) parameters.
     */
    private void checkUntypedArgs(List<SpnExpressionNode> args, String funcName, SpnParseToken callTok) {
        SpnFunctionDescriptor desc = functionDescriptorRegistry.get(funcName);
        if (desc == null) return; // no descriptor available (e.g., imported) — skip
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

    /**
     * Check that no untyped (_) arguments are passed to typed parameters of an
     * indirect call whose callee has a known function type.
     */
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

    /** Attach source position from the last consumed token to a node. */
    private <T extends SpnExpressionNode> T at(T node) {
        SpnParseToken tok = tokens.lastConsumed();
        if (tok != null) {
            node.setSourcePosition(sourceName, tok.line(), tok.col());
        }
        return node;
    }

    /** Attach source position from a specific token to a node. */
    private <T extends SpnExpressionNode> T at(T node, SpnParseToken tok) {
        if (tok != null) {
            node.setSourcePosition(sourceName, tok.line(), tok.col());
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

    // ── If ─────────────────────────────────────────────────────────────────

    private SpnStatementNode parseIfStatement() {
        tokens.expect("if");
        SpnExpressionNode condition = parseExpression();
        tokens.expect("{");
        SpnExpressionNode thenBranch = parseBlockBody();
        tokens.expect("}");

        SpnStatementNode elseBranch = null;
        if (tokens.match("else")) {
            if (tokens.check("if")) {
                elseBranch = parseIfStatement();
            } else {
                tokens.expect("{");
                elseBranch = parseBlockBody();
                tokens.expect("}");
            }
        }

        SpnStatementNode ifNode = new SpnIfNode(condition, thenBranch, elseBranch);
        // Infer result type from branches (union if different)
        if (elseBranch instanceof SpnExpressionNode elseExpr) {
            FieldType unified = unifyTypes(inferType(thenBranch), inferType(elseExpr));
            if (unified != null && ifNode instanceof SpnExpressionNode ifExpr) {
                trackType(ifExpr, unified);
            }
        }
        return ifNode;
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
                case "if" -> parseIfStatement();
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

    // ── Expression parsing (precedence climbing) ───────────────────────────

    private SpnExpressionNode parseExpression() {
        return parseOr();
    }

    private SpnExpressionNode parseOr() {
        SpnExpressionNode left = parseAnd();
        while (tokens.match("||")) {
            SpnExpressionNode right = parseAnd();
            left = new SpnOrNode(left, right);
            trackType(left, FieldType.BOOLEAN);
        }
        return left;
    }

    private SpnExpressionNode parseAnd() {
        SpnExpressionNode left = parseEquality();
        while (tokens.match("&&")) {
            SpnExpressionNode right = parseEquality();
            left = new SpnAndNode(left, right);
            trackType(left, FieldType.BOOLEAN);
        }
        return left;
    }

    private SpnExpressionNode parseEquality() {
        SpnExpressionNode left = parseComparison();
        while (true) {
            if (tokens.match("==")) {
                SpnParseToken opTok = tokens.lastConsumed();
                SpnExpressionNode right = parseComparison();
                SpnExpressionNode dispatched = tryOperatorDispatch("==", left, right);
                left = dispatched != null ? dispatched : at(SpnEqualNodeGen.create(left, right), opTok);
                trackType(left, FieldType.BOOLEAN);
            } else if (tokens.match("!=")) {
                SpnParseToken opTok = tokens.lastConsumed();
                SpnExpressionNode right = parseComparison();
                SpnExpressionNode dispatched = tryOperatorDispatch("!=", left, right);
                left = dispatched != null ? dispatched : at(SpnNotEqualNodeGen.create(left, right), opTok);
                trackType(left, FieldType.BOOLEAN);
            } else {
                SpnExpressionNode qualified = tryQualifiedInfix(left, this::parseComparison, "==", "!=");
                if (qualified != null) { left = qualified; continue; }
                break;
            }
        }
        return left;
    }

    private SpnExpressionNode parseComparison() {
        SpnExpressionNode left = parseConcatenation();
        while (true) {
            if (tokens.match("<")) {
                SpnParseToken opTok = tokens.lastConsumed();
                SpnExpressionNode right = parseConcatenation();
                SpnExpressionNode dispatched = tryOperatorDispatch("<", left, right);
                if (dispatched != null) { left = dispatched; }
                else { requireTyped(left, "ordering comparison '<'"); requireTyped(right, "ordering comparison '<'"); left = at(SpnLessThanNodeGen.create(left, right), opTok); }
                trackType(left, FieldType.BOOLEAN);
            } else if (tokens.match(">")) {
                SpnParseToken opTok = tokens.lastConsumed();
                SpnExpressionNode right = parseConcatenation();
                SpnExpressionNode dispatched = tryOperatorDispatch(">", left, right);
                if (dispatched != null) { left = dispatched; }
                else { requireTyped(left, "ordering comparison '>'"); requireTyped(right, "ordering comparison '>'"); left = at(SpnGreaterThanNodeGen.create(left, right), opTok); }
                trackType(left, FieldType.BOOLEAN);
            } else if (tokens.match("<=")) {
                SpnParseToken opTok = tokens.lastConsumed();
                SpnExpressionNode right = parseConcatenation();
                SpnExpressionNode dispatched = tryOperatorDispatch("<=", left, right);
                if (dispatched != null) { left = dispatched; }
                else { requireTyped(left, "ordering comparison '<='"); requireTyped(right, "ordering comparison '<='"); left = at(SpnLessEqualNodeGen.create(left, right), opTok); }
                trackType(left, FieldType.BOOLEAN);
            } else if (tokens.match(">=")) {
                SpnParseToken opTok = tokens.lastConsumed();
                SpnExpressionNode right = parseConcatenation();
                SpnExpressionNode dispatched = tryOperatorDispatch(">=", left, right);
                if (dispatched != null) { left = dispatched; }
                else { requireTyped(left, "ordering comparison '>='"); requireTyped(right, "ordering comparison '>='"); left = at(SpnGreaterEqualNodeGen.create(left, right), opTok); }
                trackType(left, FieldType.BOOLEAN);
            } else {
                SpnExpressionNode qualified = tryQualifiedInfix(left, this::parseConcatenation, "<", ">", "<=", ">=");
                if (qualified != null) { left = qualified; continue; }
                break;
            }
        }
        return left;
    }

    private SpnExpressionNode parseConcatenation() {
        SpnExpressionNode left = parseAddSub();
        while (tokens.match("++")) {
            SpnParseToken opTok = tokens.lastConsumed();
            SpnExpressionNode right = parseAddSub();
            requireTyped(left, "string concatenation '++'");
            requireTyped(right, "string concatenation '++'");
            left = at(SpnStringConcatNodeGen.create(left, right), opTok);
            trackType(left, FieldType.STRING);
        }
        return left;
    }

    private SpnExpressionNode parseAddSub() {
        SpnExpressionNode left = parseMulDiv();
        while (true) {
            if (tokens.match("+")) {
                SpnParseToken opTok = tokens.lastConsumed();
                SpnExpressionNode right = parseMulDiv();
                SpnExpressionNode dispatched = tryOperatorDispatch("+", left, right);
                left = dispatched != null ? dispatched : trackArithmeticType(at(SpnAddNodeGen.create(left, right), opTok), left, right);
            } else if (tokens.check("-") && !isUnaryMinus()) {
                tokens.advance();
                SpnParseToken opTok = tokens.lastConsumed();
                SpnExpressionNode right = parseMulDiv();
                SpnExpressionNode dispatched = tryOperatorDispatch("-", left, right);
                left = dispatched != null ? dispatched : trackArithmeticType(at(SpnSubtractNodeGen.create(left, right), opTok), left, right);
            } else {
                SpnExpressionNode qualified = tryQualifiedInfix(left, this::parseMulDiv, "+", "-");
                if (qualified != null) { left = qualified; continue; }
                break;
            }
        }
        return left;
    }

    private SpnExpressionNode parseMulDiv() {
        SpnExpressionNode left = parseUnary();
        while (true) {
            if (tokens.match("*")) {
                SpnParseToken opTok = tokens.lastConsumed();
                SpnExpressionNode right = parseUnary();
                SpnExpressionNode dispatched = tryOperatorDispatch("*", left, right);
                left = dispatched != null ? dispatched : trackArithmeticType(at(SpnMultiplyNodeGen.create(left, right), opTok), left, right);
            } else if (tokens.match("/")) {
                SpnParseToken opTok = tokens.lastConsumed();
                SpnExpressionNode right = parseUnary();
                SpnExpressionNode dispatched = tryOperatorDispatch("/", left, right);
                left = dispatched != null ? dispatched : trackArithmeticType(at(SpnDivideNodeGen.create(left, right), opTok), left, right);
            } else if (tokens.match("%")) {
                SpnParseToken opTok = tokens.lastConsumed();
                SpnExpressionNode right = parseUnary();
                SpnExpressionNode dispatched = tryOperatorDispatch("%", left, right);
                left = dispatched != null ? dispatched : trackArithmeticType(at(SpnModuloNodeGen.create(left, right), opTok), left, right);
            } else {
                // Qualified multiplicative operators: *_dot, /_something, %_something
                SpnExpressionNode qualified = tryQualifiedInfix(left, this::parseUnary, "*", "/", "%");
                if (qualified != null) { left = qualified; continue; }
                break;
            }
        }
        return left;
    }

    /**
     * Check if the current token is a qualified operator (e.g., *_dot, +_cross)
     * whose base matches one of the given prefixes. Returns the full operator
     * name if matched, null otherwise. Does NOT consume the token.
     */
    private String peekQualifiedOp(String... bases) {
        SpnParseToken tok = tokens.peek();
        if (tok == null || tok.type() != TokenType.OPERATOR) return null;
        String text = tok.text();
        int underscore = text.indexOf('_');
        if (underscore <= 0) return null; // not qualified
        String base = text.substring(0, underscore);
        for (String b : bases) {
            if (base.equals(b)) return text;
        }
        return null;
    }

    /**
     * Try to parse and dispatch a qualified infix operator. If the current
     * token is a qualified operator matching any of the given bases, consume
     * it and return a dispatch node. Returns null if no match.
     */
    private SpnExpressionNode tryQualifiedInfix(SpnExpressionNode left,
                                                  java.util.function.Supplier<SpnExpressionNode> parseRight,
                                                  String... bases) {
        String op = peekQualifiedOp(bases);
        if (op == null) return null;
        SpnParseToken opTok = tokens.advance(); // consume the qualified operator
        SpnExpressionNode right = parseRight.get();
        SpnExpressionNode dispatched = tryOperatorDispatch(op, left, right);
        if (dispatched != null) return at(dispatched, opTok);
        throw tokens.error("No overload found for qualified operator: " + op);
    }

    /**
     * Try to resolve a binary operator to a registered overload at compile time.
     * Returns a direct SpnInvokeNode if a matching overload is found, null otherwise.
     *
     * Dispatch strategy (Julia-inspired, one-way promotions):
     *   1. Exact match on the immediate types — use it.
     *   2. If both types are primitives with built-in operations, stop — let the
     *      parser fall back to the built-in node.
     *   3. Walk the promotion tree on each argument independently, find the
     *      overload reachable with the fewest total promotions.
     */
    private SpnExpressionNode tryOperatorDispatch(String op,
                                                    SpnExpressionNode left,
                                                    SpnExpressionNode right) {
        List<OperatorOverload> overloads = operatorRegistry.get(op);
        if (overloads == null || overloads.isEmpty()) return null;

        FieldType leftType = inferType(left);
        FieldType rightType = inferType(right);
        if (leftType == null || rightType == null) return null;

        // 1. Exact match (cost 0)
        for (OperatorOverload overload : overloads) {
            if (overload.paramTypes().length == 2
                    && typesMatch(overload.paramTypes()[0], leftType)
                    && typesMatch(overload.paramTypes()[1], rightType)) {
                SpnExpressionNode result = new SpnInvokeNode(overload.callTarget(), left, right);
                trackType(result, overload.returnType());
                return result;
            }
        }

        // 2. Both primitives → built-in handles it, don't promote
        if (isPrimitive(leftType) && isPrimitive(rightType)) return null;

        // 3. Walk promotion trees, find minimum-cost match
        List<PromotionStep> leftSteps = buildPromotionChain(leftType);
        List<PromotionStep> rightSteps = buildPromotionChain(rightType);

        int bestCost = Integer.MAX_VALUE;
        PromotionStep bestLeft = null, bestRight = null;
        OperatorOverload bestOverload = null;

        for (PromotionStep ls : leftSteps) {
            if (ls.depth() >= bestCost) continue;
            for (PromotionStep rs : rightSteps) {
                int cost = ls.depth() + rs.depth();
                if (cost == 0 || cost >= bestCost) continue;
                for (OperatorOverload overload : overloads) {
                    if (overload.paramTypes().length == 2
                            && overload.paramTypes()[0].describe().equals(ls.typeDesc())
                            && overload.paramTypes()[1].describe().equals(rs.typeDesc())) {
                        bestCost = cost;
                        bestLeft = ls;
                        bestRight = rs;
                        bestOverload = overload;
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
        return result;
    }

    /** A point in the promotion graph: the type reached, how many hops, and the converters to apply. */
    private record PromotionStep(String typeDesc, int depth, List<CallTarget> converters) {}

    /** BFS the promotion graph from a starting type, returning all reachable types with cost. */
    private List<PromotionStep> buildPromotionChain(FieldType startType) {
        List<PromotionStep> steps = new ArrayList<>();
        steps.add(new PromotionStep(startType.describe(), 0, List.of()));

        Set<String> visited = new HashSet<>();
        visited.add(startType.describe());
        java.util.Deque<PromotionStep> queue = new java.util.ArrayDeque<>();
        queue.add(steps.get(0));

        while (!queue.isEmpty()) {
            PromotionStep current = queue.poll();
            for (Promotion promo : promotionRegistry) {
                if (promo.sourceDesc().equals(current.typeDesc()) && visited.add(promo.targetDesc())) {
                    List<CallTarget> newConverters = new ArrayList<>(current.converters());
                    newConverters.add(promo.converter());
                    PromotionStep next = new PromotionStep(
                            promo.targetDesc(), current.depth() + 1, List.copyOf(newConverters));
                    steps.add(next);
                    queue.add(next);
                }
            }
        }
        return steps;
    }

    /** Wrap an expression in a chain of promotion calls. */
    private SpnExpressionNode applyPromotionChain(SpnExpressionNode expr, PromotionStep step) {
        SpnExpressionNode current = expr;
        for (CallTarget converter : step.converters()) {
            current = new SpnInvokeNode(converter, current);
        }
        return current;
    }

    private boolean isPrimitive(FieldType type) {
        return type == FieldType.LONG || type == FieldType.DOUBLE
                || type == FieldType.BOOLEAN || type == FieldType.STRING;
    }

    /**
     * Check if an inferred type is assignable to a declared type.
     *
     * <p>Handles union subtyping:
     * <ul>
     *   <li>{@code Circle} is assignable to {@code Circle | Rectangle} (member of union)</li>
     *   <li>{@code Circle | Rectangle} is assignable to {@code Circle | Rectangle | Triangle} (subset)</li>
     *   <li>{@code Circle} is assignable to {@code Circle} (identity)</li>
     * </ul>
     */
    private boolean typesMatch(FieldType declared, FieldType inferred) {
        if (declared == null || inferred == null) return false;
        // Same object (e.g., both FieldType.LONG)
        if (declared == inferred) return true;
        // Both describe the same type name
        if (declared.describe().equals(inferred.describe())) return true;

        // Union assignability: inferred is assignable to declared union
        // if every variant in inferred is a member of declared
        if (declared instanceof FieldType.OfVariant declaredUnion) {
            SpnStructDescriptor[] declaredVariants = declaredUnion.variantSet().getVariants();

            // Concrete struct assignable to union containing it
            if (inferred instanceof FieldType.OfStruct inferredStruct) {
                for (SpnStructDescriptor v : declaredVariants) {
                    if (v == inferredStruct.descriptor()) return true;
                }
            }

            // Smaller union assignable to larger union (subset check)
            if (inferred instanceof FieldType.OfVariant inferredUnion) {
                outer:
                for (SpnStructDescriptor iv : inferredUnion.variantSet().getVariants()) {
                    for (SpnStructDescriptor dv : declaredVariants) {
                        if (iv == dv) continue outer;
                    }
                    return false; // inferred variant not in declared
                }
                return true;
            }
        }

        return false;
    }

    private SpnExpressionNode parseUnary() {
        if (tokens.match("-")) {
            SpnExpressionNode operand = parseUnary();
            requireTyped(operand, "negation");
            return at(SpnNegateNodeGen.create(operand));
        }
        if (tokens.check("inspect")) {
            tokens.advance();
            SpnExpressionNode operand = parseUnary();
            return new spn.node.SpnInspectNode(operand);
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

    /**
     * Resolve any FieldType to its underlying SpnStructDescriptor, if it has one.
     * Works for OfStruct, OfConstrainedType (product), and OfProduct.
     */
    private SpnStructDescriptor resolveStructDescriptor(FieldType type) {
        if (type instanceof FieldType.OfStruct os) return os.descriptor();
        if (type instanceof FieldType.OfConstrainedType oct && oct.descriptor().isProduct())
            return structRegistry.get(oct.descriptor().getName());
        if (type instanceof FieldType.OfProduct op)
            return structRegistry.get(op.descriptor().getName());
        return null;
    }

    /** Get the type name from a FieldType, for registry lookups. */
    private String resolveTypeName(FieldType type) {
        if (type instanceof FieldType.OfStruct os) return os.descriptor().getName();
        if (type instanceof FieldType.OfConstrainedType oct) return oct.descriptor().getName();
        if (type instanceof FieldType.OfProduct op) return op.descriptor().getName();
        return null;
    }

    /**
     * Create a positional access node for expr.0, expr.1, etc.
     * Uses TupleElementAccessNode for tuples, FieldAccessNode for structs/types.
     */
    private SpnExpressionNode createPositionalAccess(SpnExpressionNode expr, int index,
                                                      FieldType receiverType, SpnParseToken tok) {
        // Tuple value
        if (receiverType instanceof FieldType.OfTuple ot) {
            SpnExpressionNode node = spn.node.struct.SpnTupleElementAccessNodeGen.create(expr, index);
            var desc = ot.descriptor();
            if (index < desc.arity()) {
                FieldType elemType = desc.elementType(index);
                if (elemType != null) trackType(node, elemType);
            }
            return node;
        }
        // Struct/type value — positional access maps to field index
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
        throw tokens.error("Cannot use positional access on " +
                (receiverType != null ? receiverType.describe() : "unknown type"), tok);
    }

    /** Resolve a field index from the inferred type. */
    private int resolveFieldIndex(SpnExpressionNode expr, String fieldName) {
        FieldType type = inferType(expr);
        SpnStructDescriptor sd = resolveStructDescriptor(type);
        if (sd != null) {
            int idx = sd.fieldIndex(fieldName);
            if (idx >= 0) return idx;
        }
        throw tokens.error("Cannot resolve field '" + fieldName + "'"
                + (type != null ? " on type " + type.describe() : " (unknown type)"));
    }

    /** Track the type of a field access if the struct descriptor has type info. */
    private void trackFieldType(SpnExpressionNode accessNode, String fieldName, FieldType receiverType) {
        SpnStructDescriptor sd = resolveStructDescriptor(receiverType);
        if (sd != null) {
            int idx = sd.fieldIndex(fieldName);
            if (idx >= 0) {
                FieldType ft = sd.fieldType(idx);
                if (ft != null) trackType(accessNode, ft);
            }
        }
    }

    /** Look up a method by receiver type and name. */
    private MethodEntry resolveMethod(FieldType receiverType, String methodName) {
        if (receiverType == null) return null;

        // Union types: check that the method exists on ALL variants.
        // Return the first variant's method entry (they should be compatible).
        if (receiverType instanceof FieldType.OfVariant ov) {
            MethodEntry first = null;
            for (SpnStructDescriptor variant : ov.variantSet().getVariants()) {
                MethodEntry entry = methodRegistry.get(variant.getName() + "." + methodName);
                if (entry == null) return null; // not on all variants
                if (first == null) first = entry;
            }
            return first;
        }

        // Try exact type description match
        String key = receiverType.describe() + "." + methodName;
        MethodEntry entry = methodRegistry.get(key);
        if (entry != null) return entry;
        // Try the type's simple name
        String typeName = resolveTypeName(receiverType);
        if (typeName != null) {
            entry = methodRegistry.get(typeName + "." + methodName);
            if (entry != null) return entry;
        }
        return null;
    }

    /** Resolve a type name to a FieldType for method receiver typing. */
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

            // Look up the function
            CallTarget target = functionRegistry.get(name);
            if (target != null) {
                checkPurityViolation(name, tok);
                checkUntypedArgs(args, name, tok);
                return new SpnInvokeNode(target, args.toArray(new SpnExpressionNode[0]));
            }

            // Builtin function (stdlib, canvas, etc.)
            spn.node.BuiltinFactory builtin = builtinRegistry.get(name);
            if (builtin != null) {
                checkPurityViolation(name, tok);
                return builtin.create(args.toArray(new SpnExpressionNode[0]));
            }

            // Self-recursive call — the function is being defined right now
            if (name.equals(deferredFunctionName)) {
                SpnDeferredInvokeNode deferred = new SpnDeferredInvokeNode(
                        name, args.toArray(new SpnExpressionNode[0]));
                deferredCalls.add(deferred);
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

        // Check for factory — TypeName(args) always goes through factory if defined.
        List<FactoryEntry> factories = factoryRegistry.get(name);
        if (factories != null) {
            for (FactoryEntry fe : factories) {
                if (fe.arity() == args.size()) {
                    SpnExpressionNode callNode = new spn.node.func.SpnInvokeNode(
                            fe.callTarget(), args.toArray(new SpnExpressionNode[0]));
                    if (fe.descriptor().hasTypedReturn()) {
                        trackType(callNode, fe.descriptor().getReturnType());
                    }
                    return callNode;
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

        return new spn.node.struct.SpnTupleConstructNode(
                SpnTupleDescriptor.untyped(elements.size()),
                elements.toArray(new SpnExpressionNode[0]));
    }

    // ── Match expression ───────────────────────────────────────────────────

    private SpnExpressionNode parseMatchExpression() {
        SpnParseToken matchTok = tokens.expect("match");
        SpnExpressionNode subject = parseExpression();

        List<SpnMatchBranchNode> branches = new ArrayList<>();
        FieldType resultType = null;
        while (tokens.match("|")) {
            SpnMatchBranchNode branch = parseMatchBranch();
            branches.add(branch);
            // Unify branch body types to infer the match result type
            resultType = unifyTypes(resultType, inferType(branch.getBody()));
        }

        if (branches.isEmpty()) {
            throw tokens.error("Match expression must have at least one branch");
        }

        // Exhaustiveness check: if subject type is a union/variant, verify coverage
        FieldType subjectType = inferType(subject);
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

    private SpnMatchBranchNode parseMatchBranch() {
        ParsedPattern pp = parsePattern();
        // Optional guard: | x | x > 0 -> ...
        SpnExpressionNode guard = null;
        if (tokens.check("|")) {
            tokens.advance();
            guard = parseExpression();
        }

        tokens.expect("->");
        SpnExpressionNode body = parseExpression();

        return new SpnMatchBranchNode(pp.pattern, pp.bindingSlots, guard, body);
    }

    private record ParsedPattern(MatchPattern pattern, int[] bindingSlots) {}

    private ParsedPattern parsePattern() {
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

        // Empty collection: []
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

                int headSlot = currentScope.addLocal(headName);
                int tailSlot = currentScope.addLocal(tailName);
                return new ParsedPattern(new MatchPattern.ArrayHeadTail(),
                        new int[]{headSlot, tailSlot});
            }

            // [a, b, c] — exact length with bindings
            // [:key val, ...] — dict keys pattern
            if (first.type() == TokenType.SYMBOL) {
                // Dict keys pattern: [:name n, :age a]
                List<SpnSymbol> keys = new ArrayList<>();
                List<Integer> slots = new ArrayList<>();
                while (!tokens.check("]")) {
                    SpnParseToken symTok = tokens.expectType(TokenType.SYMBOL);
                    keys.add(symbolTable.intern(symTok.text().substring(1)));
                    String bindName = tokens.expectType(TokenType.IDENTIFIER).text();
                    slots.add(currentScope.addLocal(bindName));
                    tokens.match(",");
                }
                tokens.expect("]");
                return new ParsedPattern(
                        new MatchPattern.DictionaryKeys(keys.toArray(new SpnSymbol[0])),
                        slots.stream().mapToInt(Integer::intValue).toArray());
            }

            // [a, b, c] exact-length array pattern
            List<String> names = new ArrayList<>();
            names.add(tokens.advance().text());
            while (tokens.match(",")) {
                names.add(tokens.advance().text());
            }
            tokens.expect("]");

            int[] slots = new int[names.size()];
            for (int i = 0; i < names.size(); i++) {
                slots[i] = currentScope.addLocal(names.get(i));
            }
            return new ParsedPattern(new MatchPattern.ArrayExactLength(names.size()), slots);
        }

        // String prefix pattern: "http://" ++ rest
        if (tok.type() == TokenType.STRING) {
            tokens.advance();
            String prefix = unescapeString(tok.text());
            if (tokens.match("++")) {
                String bindName = tokens.expectType(TokenType.IDENTIFIER).text();
                int slot = currentScope.addLocal(bindName);
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
                        slots.add(currentScope.addLocal(bindName));
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
            // Explicit casts to avoid ternary numeric promotion (long -> double)
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
                List<MatchPattern> fieldPatterns = new ArrayList<>();
                while (!tokens.check(")")) {
                    fieldPatterns.add(parseNestedPattern());
                    tokens.match(",");
                }
                tokens.expect(")");

                if (desc != null) {
                    return new ParsedPattern(
                            new MatchPattern.StructDestructure(
                                    desc, fieldPatterns.toArray(MatchPattern[]::new)),
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
            int slot = currentScope.addLocal(tok.text());
            return new ParsedPattern(new MatchPattern.Wildcard(), new int[]{slot});
        }

        throw tokens.error("Expected pattern, got: " + tok.text(), tok);
    }

    /**
     * Parse a pattern inside a composite context (tuple element, struct field).
     * Returns a MatchPattern directly — variable bindings use Capture with
     * embedded frame slots, so no external bindingSlots array is needed.
     */
    private MatchPattern parseNestedPattern() {
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
                List<MatchPattern> fieldPatterns = new ArrayList<>();
                while (!tokens.check(")")) {
                    fieldPatterns.add(parseNestedPattern());
                    tokens.match(",");
                }
                tokens.expect(")");
                return new MatchPattern.StructDestructure(
                        desc, fieldPatterns.toArray(MatchPattern[]::new));
            }
            return new MatchPattern.Struct(desc);
        }

        // Identifier — variable capture
        if (tok.type() == TokenType.IDENTIFIER) {
            tokens.advance();
            int slot = currentScope.addLocal(tok.text());
            return new MatchPattern.Capture(slot);
        }

        throw tokens.error("Unexpected token in pattern: " + tok.text(), tok);
    }

    // ── Field type parsing ─────────────────────────────────────────────────

    private static final Set<String> PRIMITIVE_TYPE_KEYWORDS =
            Set.of("int", "float", "string", "bool");

    /**
     * Parse a type expression, including anonymous union types: {@code Circle | Rectangle}.
     * Delegates to {@link #parseSingleFieldType()} for each component.
     */
    private FieldType parseFieldType() {
        FieldType first = parseSingleFieldType();

        if (!tokens.check("|")) return first;

        // Union type: Type | Type | ...
        List<SpnStructDescriptor> variants = new ArrayList<>();
        collectUnionMembers(variants, first);
        while (tokens.match("|")) {
            collectUnionMembers(variants, parseSingleFieldType());
        }

        // Sort for order-independence: Circle | Rectangle == Rectangle | Circle
        variants.sort(java.util.Comparator.comparing(SpnStructDescriptor::getName));
        // Deduplicate (after sorting, duplicates are adjacent)
        for (int i = variants.size() - 1; i > 0; i--) {
            if (variants.get(i) == variants.get(i - 1)) variants.remove(i);
        }

        if (variants.size() == 1) {
            return FieldType.ofStruct(variants.get(0));
        }

        String name = variants.stream()
                .map(SpnStructDescriptor::getName)
                .collect(java.util.stream.Collectors.joining(" | "));
        return FieldType.ofVariant(new SpnVariantSet(name,
                variants.toArray(new SpnStructDescriptor[0])));
    }

    /** Extract struct descriptors from a FieldType for union construction. */
    private void collectUnionMembers(List<SpnStructDescriptor> variants, FieldType type) {
        if (type instanceof FieldType.OfStruct os) {
            variants.add(os.descriptor());
        } else if (type instanceof FieldType.OfVariant ov) {
            java.util.Collections.addAll(variants, ov.variantSet().getVariants());
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
    private FieldType parseSingleFieldType() {
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

            // Check for generic parameters: Collection<Long>
            if (tokens.match("<")) {
                FieldType inner = parseFieldType();
                tokens.expect(">");
                return switch (name) {
                    case "Array", "Collection" -> FieldType.ofArray(inner);
                    case "Set" -> FieldType.ofSet(inner);
                    case "Dict" -> FieldType.ofDictionary(inner);
                    default -> FieldType.UNTYPED;
                };
            }

            return switch (name) {
                case "int", "Long" -> FieldType.LONG;
                case "float", "Double" -> FieldType.DOUBLE;
                case "string", "String" -> FieldType.STRING;
                case "bool", "Boolean" -> FieldType.BOOLEAN;
                case "Symbol" -> FieldType.SYMBOL;
                case "Array", "Collection" -> FieldType.ofArray(FieldType.UNTYPED);
                case "Set" -> FieldType.ofSet(FieldType.UNTYPED);
                case "Dict" -> FieldType.ofDictionary(FieldType.UNTYPED);
                case "Tuple" -> FieldType.ofTuple(SpnTupleDescriptor.untyped(0));
                default -> {
                    // Check registries
                    SpnStructDescriptor sd = structRegistry.get(name);
                    if (sd != null) yield FieldType.ofStruct(sd);
                    SpnTypeDescriptor td = typeRegistry.get(name);
                    if (td != null) yield td.isProduct()
                            ? FieldType.ofProduct(td) : FieldType.ofConstrainedType(td);
                    SpnVariantSet vs = variantRegistry.get(name);
                    if (vs != null) yield FieldType.ofVariant(vs);
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

    // ── Helpers ─────────────────────────────────────────────────────────────

    private SpnSymbol parseSymbolValue() {
        SpnParseToken tok = tokens.expectType(TokenType.SYMBOL);
        return symbolTable.intern(tok.text().substring(1));
    }

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
        // A '-' is unary if the previous token was an operator/delimiter/keyword
        // This is a heuristic; the tokenizer already handles some of this
        return false; // default: treat '-' as binary subtract in addSub
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
