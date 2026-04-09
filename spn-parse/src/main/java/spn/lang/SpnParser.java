package spn.lang;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import spn.language.ImportDirective;
import spn.language.SpnLanguage;
import spn.language.SpnModule;
import spn.language.SpnModuleRegistry;
import spn.node.SpnBlockNode;
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
    private final Map<String, spn.node.BuiltinFactory> builtinRegistry = new LinkedHashMap<>();

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

    // ── Top-level parsing ──────────────────────────────────────────────────

    private SpnStatementNode parseTopLevel() {
        SpnParseToken tok = tokens.peek();
        if (tok == null) return null;

        return switch (tok.text()) {
            case "import" -> { parseImportDecl(); yield null; }
            case "module" -> { skipModuleDecl(); yield null; }
            case "version" -> { skipVersionDecl(); yield null; }
            case "require" -> { skipRequireDecl(); yield null; }
            case "type" -> { parseTypeDecl(); yield null; }
            case "data" -> { parseDataDecl(); yield null; }
            case "struct" -> { parseStructAsType(); yield null; }
            case "pure" -> parsePureDecl();
            case "promote" -> { parsePromoteDecl(); yield null; }
            case "let" -> parseLetBinding();
            case "while" -> parseWhileStatement();
            case "if" -> parseIfStatement();
            case "yield" -> parseYieldStatement();
            case "return" -> parseYieldStatement(); // return is syntactic sugar for yield
            default -> parseExpressionStatement();
        };
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

    private void applyFullImport(SpnModule module) {
        functionRegistry.putAll(module.getFunctions());
        builtinRegistry.putAll(module.getBuiltinFactories());
        typeRegistry.putAll(module.getTypes());
        structRegistry.putAll(module.getStructs());
        variantRegistry.putAll(module.getVariants());
    }

    private void applySelectiveImport(SpnModule module, ImportDirective directive) {
        for (ImportDirective.ImportedName imp : directive.selectiveNames()) {
            String src = imp.name();
            String dst = imp.localName();

            CallTarget fn = module.getFunction(src);
            if (fn != null) { functionRegistry.put(dst, fn); continue; }

            spn.node.BuiltinFactory bf = module.getBuiltinFactory(src);
            if (bf != null) { builtinRegistry.put(dst, bf); continue; }

            SpnTypeDescriptor td = module.getType(src);
            if (td != null) { typeRegistry.put(dst, td); continue; }

            SpnStructDescriptor sd = module.getStruct(src);
            if (sd != null) { structRegistry.put(dst, sd); continue; }

            SpnVariantSet vs = module.getVariant(src);
            if (vs != null) { variantRegistry.put(dst, vs); continue; }

            throw tokens.error("Module '" + module.getNamespace()
                    + "' does not export '" + src + "'");
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
            int anonIndex = 0;
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
                    String compName = "_" + anonIndex++;
                    builder.component(compName, ft);
                    structBuilder.field(compName, ft);
                }
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

        List<SpnStructDescriptor> variants = new ArrayList<>();
        do {
            tokens.match("|"); // optional leading pipe
            String variantName = tokens.expectType(TokenType.TYPE_NAME).text();
            List<String> fields = new ArrayList<>();
            if (tokens.match("(")) {
                while (!tokens.check(")")) {
                    fields.add(tokens.expectType(TokenType.IDENTIFIER).text());
                    tokens.match(",");
                }
                tokens.expect(")");
            }
            SpnStructDescriptor desc = new SpnStructDescriptor(variantName, fields.toArray(new String[0]));
            structRegistry.put(variantName, desc);
            variants.add(desc);
        } while (tokens.match("|"));

        variantRegistry.put(name, new SpnVariantSet(name, variants.toArray(new SpnStructDescriptor[0])));
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

    private SpnStatementNode parsePureDecl() {
        tokens.expect("pure");
        // Accept identifier (named function) or operator (operator overload)
        SpnParseToken nameTok = tokens.peek();
        String name;
        if (nameTok.type() == TokenType.OPERATOR) {
            name = tokens.advance().text();
        } else {
            name = tokens.expectType(TokenType.IDENTIFIER).text();
        }

        // Parse type signature: (Type, Type) -> ReturnType
        tokens.expect("(");
        List<FieldType> paramTypes = new ArrayList<>();
        while (!tokens.check(")")) {
            paramTypes.add(parseFieldType());
            tokens.match(",");
        }
        tokens.expect(")");

        FieldType returnType = FieldType.UNTYPED;
        if (tokens.match("->")) {
            returnType = parseFieldType();
        }

        boolean isOperator = nameTok.type() == TokenType.OPERATOR;

        // Declaration only (no =), just register the signature
        if (!tokens.check("=")) {
            return null;
        }

        // Definition: = (params) { body }
        tokens.expect("=");
        parseFunctionBody(name, paramTypes, returnType);

        // Register operator overload for compile-time dispatch
        if (isOperator && !paramTypes.isEmpty()) {
            CallTarget ct = functionRegistry.get(name);
            if (ct != null) {
                operatorRegistry.computeIfAbsent(name, k -> new ArrayList<>())
                        .add(new OperatorOverload(
                                paramTypes.toArray(new FieldType[0]), returnType, ct));
            }
        }

        return null;
    }

    /** A parameter binding: either a simple name or a positional destructure (a, b, c). */
    private record ParamDecl(String name, List<String> destructureBindings) {
        boolean isDestructured() { return destructureBindings != null; }
        static ParamDecl simple(String name) { return new ParamDecl(name, null); }
        static ParamDecl destructured(List<String> bindings) { return new ParamDecl(null, bindings); }
    }

    private SpnStatementNode parseFunctionBody(String name, List<FieldType> paramTypes,
                                                FieldType returnType) {
        tokens.expect("(");
        List<ParamDecl> params = new ArrayList<>();
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
        tokens.expect("{");
        SpnExpressionNode body = parseBlockBody();
        tokens.expect("}");
        deferredFunctionName = null;
        boolean isProducer = containsYield;

        // Prepend destructure nodes to the body if any params were destructured
        if (!destructureNodes.isEmpty()) {
            List<SpnStatementNode> stmts = new ArrayList<>(destructureNodes);
            stmts.add(body);
            body = new SpnBlockExprNode(stmts.toArray(new SpnStatementNode[0]));
        }

        popScope();

        // Build the function descriptor — include yield ctx param only for producers
        SpnFunctionDescriptor.Builder descBuilder = SpnFunctionDescriptor.pure(name);
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
        CallTarget callTarget = fnRoot.getCallTarget();
        functionRegistry.put(name, callTarget);

        // Patch any deferred self-calls
        patchDeferredCalls(name, callTarget);

        // Return null — function definitions don't produce a runtime statement
        return null;
    }

    // Support for recursive function references and yield detection
    private String deferredFunctionName;
    private boolean containsYield;
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
        if (first.type() == TokenType.TYPE_NAME && tokens.peek(1) != null
                && tokens.peek(1).text().equals("(")) {
            return parseLetDestructure();
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
        return SpnWriteLocalVariableNodeGen.create(value, slot);
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

        return new SpnIfNode(condition, thenBranch, elseBranch);
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
        return new SpnBlockExprNode(stmts.toArray(new SpnStatementNode[0]));
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
        }
        return left;
    }

    private SpnExpressionNode parseAnd() {
        SpnExpressionNode left = parseEquality();
        while (tokens.match("&&")) {
            SpnExpressionNode right = parseEquality();
            left = new SpnAndNode(left, right);
        }
        return left;
    }

    private SpnExpressionNode parseEquality() {
        SpnExpressionNode left = parseComparison();
        while (true) {
            if (tokens.match("==")) {
                SpnExpressionNode right = parseComparison();
                SpnExpressionNode dispatched = tryOperatorDispatch("==", left, right);
                left = dispatched != null ? dispatched : SpnEqualNodeGen.create(left, right);
            } else if (tokens.match("!=")) {
                SpnExpressionNode right = parseComparison();
                SpnExpressionNode dispatched = tryOperatorDispatch("!=", left, right);
                left = dispatched != null ? dispatched : SpnNotEqualNodeGen.create(left, right);
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
                SpnExpressionNode right = parseConcatenation();
                SpnExpressionNode dispatched = tryOperatorDispatch("<", left, right);
                left = dispatched != null ? dispatched : SpnLessThanNodeGen.create(left, right);
            } else if (tokens.match(">")) {
                SpnExpressionNode right = parseConcatenation();
                SpnExpressionNode dispatched = tryOperatorDispatch(">", left, right);
                left = dispatched != null ? dispatched : SpnGreaterThanNodeGen.create(left, right);
            } else if (tokens.match("<=")) {
                SpnExpressionNode right = parseConcatenation();
                SpnExpressionNode dispatched = tryOperatorDispatch("<=", left, right);
                left = dispatched != null ? dispatched : SpnLessEqualNodeGen.create(left, right);
            } else if (tokens.match(">=")) {
                SpnExpressionNode right = parseConcatenation();
                SpnExpressionNode dispatched = tryOperatorDispatch(">=", left, right);
                left = dispatched != null ? dispatched : SpnGreaterEqualNodeGen.create(left, right);
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
            left = SpnStringConcatNodeGen.create(left, parseAddSub());
        }
        return left;
    }

    private SpnExpressionNode parseAddSub() {
        SpnExpressionNode left = parseMulDiv();
        while (true) {
            if (tokens.match("+")) {
                SpnExpressionNode right = parseMulDiv();
                SpnExpressionNode dispatched = tryOperatorDispatch("+", left, right);
                left = dispatched != null ? dispatched : trackArithmeticType(SpnAddNodeGen.create(left, right), left, right);
            } else if (tokens.check("-") && !isUnaryMinus()) {
                tokens.advance();
                SpnExpressionNode right = parseMulDiv();
                SpnExpressionNode dispatched = tryOperatorDispatch("-", left, right);
                left = dispatched != null ? dispatched : trackArithmeticType(SpnSubtractNodeGen.create(left, right), left, right);
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
                SpnExpressionNode right = parseUnary();
                SpnExpressionNode dispatched = tryOperatorDispatch("*", left, right);
                left = dispatched != null ? dispatched : trackArithmeticType(SpnMultiplyNodeGen.create(left, right), left, right);
            } else if (tokens.match("/")) {
                SpnExpressionNode right = parseUnary();
                SpnExpressionNode dispatched = tryOperatorDispatch("/", left, right);
                left = dispatched != null ? dispatched : trackArithmeticType(SpnDivideNodeGen.create(left, right), left, right);
            } else if (tokens.match("%")) {
                SpnExpressionNode right = parseUnary();
                SpnExpressionNode dispatched = tryOperatorDispatch("%", left, right);
                left = dispatched != null ? dispatched : trackArithmeticType(SpnModuloNodeGen.create(left, right), left, right);
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
        tokens.advance(); // consume the qualified operator
        SpnExpressionNode right = parseRight.get();
        SpnExpressionNode dispatched = tryOperatorDispatch(op, left, right);
        if (dispatched != null) return dispatched;
        throw tokens.error("No overload found for qualified operator: " + op);
    }

    /**
     * Try to resolve a binary operator to a registered overload at compile time.
     * Returns a direct SpnInvokeNode if a matching overload is found, null otherwise.
     */
    private SpnExpressionNode tryOperatorDispatch(String op,
                                                    SpnExpressionNode left,
                                                    SpnExpressionNode right) {
        List<OperatorOverload> overloads = operatorRegistry.get(op);
        if (overloads == null || overloads.isEmpty()) return null;

        FieldType leftType = inferType(left);
        FieldType rightType = inferType(right);
        System.err.println("[dispatch] " + op + ": left=" + (leftType != null ? leftType.describe() : "null")
                + " right=" + (rightType != null ? rightType.describe() : "null")
                + " overloads=" + overloads.size());
        if (leftType == null || rightType == null) return null;

        // 1. Try exact match
        for (OperatorOverload overload : overloads) {
            if (overload.paramTypes().length == 2
                    && typesMatch(overload.paramTypes()[0], leftType)
                    && typesMatch(overload.paramTypes()[1], rightType)) {
                SpnExpressionNode result = new SpnInvokeNode(overload.callTarget(), left, right);
                trackType(result, overload.returnType());
                return result;
            }
        }

        // 2. Try promoting left operand
        for (Promotion promo : promotionRegistry) {
            if (promo.sourceDesc().equals(leftType.describe())) {
                String promotedLeftDesc = promo.targetDesc();
                for (OperatorOverload overload : overloads) {
                    if (overload.paramTypes().length == 2
                            && overload.paramTypes()[0].describe().equals(promotedLeftDesc)
                            && typesMatch(overload.paramTypes()[1], rightType)) {
                        SpnExpressionNode promotedLeft =
                                new SpnInvokeNode(promo.converter(), left);
                        SpnExpressionNode result = new SpnInvokeNode(overload.callTarget(), promotedLeft, right);
                        trackType(result, overload.returnType());
                        return result;
                    }
                }
            }
        }

        // 3. Try promoting right operand
        for (Promotion promo : promotionRegistry) {
            if (promo.sourceDesc().equals(rightType.describe())) {
                String promotedRightDesc = promo.targetDesc();
                for (OperatorOverload overload : overloads) {
                    if (overload.paramTypes().length == 2
                            && typesMatch(overload.paramTypes()[0], leftType)
                            && overload.paramTypes()[1].describe().equals(promotedRightDesc)) {
                        SpnExpressionNode promotedRight =
                                new SpnInvokeNode(promo.converter(), right);
                        SpnExpressionNode result = new SpnInvokeNode(overload.callTarget(), left, promotedRight);
                        trackType(result, overload.returnType());
                        return result;
                    }
                }
            }
        }

        // 4. Try promoting both operands to a common type
        for (Promotion leftPromo : promotionRegistry) {
            if (!leftPromo.sourceDesc().equals(leftType.describe())) continue;
            for (Promotion rightPromo : promotionRegistry) {
                if (!rightPromo.sourceDesc().equals(rightType.describe())) continue;
                if (!leftPromo.targetDesc().equals(rightPromo.targetDesc())) continue;
                String commonDesc = leftPromo.targetDesc();
                for (OperatorOverload overload : overloads) {
                    if (overload.paramTypes().length == 2
                            && overload.paramTypes()[0].describe().equals(commonDesc)
                            && overload.paramTypes()[1].describe().equals(commonDesc)) {
                        SpnExpressionNode promotedLeft =
                                new SpnInvokeNode(leftPromo.converter(), left);
                        SpnExpressionNode promotedRight =
                                new SpnInvokeNode(rightPromo.converter(), right);
                        SpnExpressionNode result = new SpnInvokeNode(overload.callTarget(), promotedLeft, promotedRight);
                        trackType(result, overload.returnType());
                        return result;
                    }
                }
            }
        }

        return null;
    }

    /** Check if a declared parameter type matches an inferred argument type. */
    private boolean typesMatch(FieldType declared, FieldType inferred) {
        if (declared == null || inferred == null) return false;
        // Same object (e.g., both FieldType.LONG)
        if (declared == inferred) return true;
        // Both describe the same type name
        return declared.describe().equals(inferred.describe());
    }

    private SpnExpressionNode parseUnary() {
        if (tokens.match("-")) {
            return SpnNegateNodeGen.create(parseUnary());
        }
        return parsePostfix();
    }

    private SpnExpressionNode parsePostfix() {
        SpnExpressionNode expr = parsePrimary();

        // Handle indexing: expr[index] and expr[:key]
        while (tokens.check("[")) {
            tokens.advance();
            SpnExpressionNode index = parseExpression();
            tokens.expect("]");
            // Delegate to array access (the runtime figures out the collection type)
            expr = SpnArrayAccessNodeGen.create(expr, index);
        }

        return expr;
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

        // Lambda: {expr}
        if (tok.text().equals("{")) {
            tokens.advance();
            SpnExpressionNode expr = parseExpression();
            tokens.expect("}");
            return expr;
        }

        // Type name — struct/type constructor: TypeName(args)
        if (tok.type() == TokenType.TYPE_NAME) {
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

        // Qualified module access: M.name or M.name(args)
        if (tokens.check(".") && qualifiedModules.containsKey(name)) {
            return parseQualifiedAccess(name);
        }

        // Function call: name(args)
        if (tokens.check("(")) {
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
                return new SpnInvokeNode(target, args.toArray(new SpnExpressionNode[0]));
            }

            // Builtin function (stdlib, canvas, etc.)
            spn.node.BuiltinFactory builtin = builtinRegistry.get(name);
            if (builtin != null) {
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
                return new SpnIndirectInvokeNode(
                        new SpnReadLocalVariableNodeWrapper(callSlot),
                        args.toArray(new SpnExpressionNode[0]));
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
            return new SpnFunctionRefNode(refTarget);
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

        // Look up struct descriptor
        SpnStructDescriptor desc = structRegistry.get(name);
        if (desc != null) {
            return new SpnStructConstructNode(desc, args.toArray(new SpnExpressionNode[0]));
        }

        // Look up type descriptor (product construction)
        SpnTypeDescriptor typeDesc = typeRegistry.get(name);
        if (typeDesc != null && typeDesc.isProduct()) {
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
        tokens.expect("match");
        SpnExpressionNode subject = parseExpression();

        List<SpnMatchBranchNode> branches = new ArrayList<>();
        while (tokens.match("|")) {
            branches.add(parseMatchBranch());
        }

        if (branches.isEmpty()) {
            throw tokens.error("Match expression must have at least one branch");
        }

        return new SpnMatchNode(subject, branches.toArray(new SpnMatchBranchNode[0]));
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

        // Wildcard: _
        if (tok.text().equals("_")) {
            tokens.advance();
            return new ParsedPattern(new MatchPattern.Wildcard(), new int[0]);
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

        // Struct pattern: TypeName(field1, field2)
        if (tok.type() == TokenType.TYPE_NAME) {
            tokens.advance();
            String typeName = tok.text();
            SpnStructDescriptor desc = structRegistry.get(typeName);

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

                if (desc != null) {
                    return new ParsedPattern(new MatchPattern.Struct(desc),
                            slots.stream().mapToInt(Integer::intValue).toArray());
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

    // ── Field type parsing ─────────────────────────────────────────────────

    private static final Set<String> PRIMITIVE_TYPE_KEYWORDS =
            Set.of("int", "float", "string", "bool");

    private FieldType parseFieldType() {
        SpnParseToken tok = tokens.peek();

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
                    yield FieldType.UNTYPED;
                }
            };
        }

        if (tok.text().equals("_")) {
            tokens.advance();
            return FieldType.UNTYPED;
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
    static final class SpnReadLocalVariableNodeWrapper extends SpnExpressionNode {
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

    static final class SpnDeferredInvokeNode extends SpnExpressionNode {
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

    static final class SpnBlockExprNode extends SpnExpressionNode {
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
