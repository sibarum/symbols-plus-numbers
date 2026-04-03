package spn.lang;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import spn.language.SpnLanguage;
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
import spn.node.func.SpnFunctionRootNode;
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

    // Current scope for frame slot management
    private Scope currentScope;

    public SpnParser(String source, SpnLanguage language, SpnSymbolTable symbolTable) {
        this.tokens = new SpnTokenizer(source);
        this.language = language;
        this.symbolTable = symbolTable;
    }

    // ── Scope management ───────────────────────────────────────────────────

    private static class Scope {
        final FrameDescriptor.Builder frameBuilder;
        final Map<String, Integer> locals = new LinkedHashMap<>();
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

        int lookupLocal(String name) {
            Integer slot = locals.get(name);
            if (slot != null) return slot;
            if (parent != null) return parent.lookupLocal(name);
            return -1;
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
            case "type" -> { parseTypeDecl(); yield null; }
            case "data" -> { parseDataDecl(); yield null; }
            case "struct" -> { parseStructDecl(); yield null; }
            case "pure" -> parsePureDecl();
            case "let" -> parseLetBinding();
            case "while" -> parseWhileStatement();
            case "if" -> parseIfStatement();
            case "yield" -> parseYieldStatement();
            case "return" -> parseYieldStatement(); // return is syntactic sugar for yield
            default -> parseExpressionStatement();
        };
    }

    // ── Type declarations ──────────────────────────────────────────────────

    private void parseTypeDecl() {
        tokens.expect("type");
        String name = tokens.expectType(TokenType.TYPE_NAME).text();

        // type Name = Symbol where oneOf([...])
        if (tokens.match("=")) {
            tokens.expect("Symbol");
            tokens.expect("where");
            tokens.expect("oneOf");
            tokens.expect("(");
            tokens.expect("[");
            List<SpnSymbol> symbols = new ArrayList<>();
            while (!tokens.check("]")) {
                symbols.add(parseSymbolValue());
                tokens.match(",");
            }
            tokens.expect("]");
            tokens.expect(")");
            // Register as a constrained type
            SpnTypeDescriptor desc = new SpnTypeDescriptor(name,
                    new Constraint.SymbolOneOf(symbols.toArray(new SpnSymbol[0])));
            typeRegistry.put(name, desc);
            return;
        }

        // type Name where constraints [with Element]
        if (tokens.check("where")) {
            tokens.advance(); // consume "where"
            SpnTypeDescriptor.Builder builder = SpnTypeDescriptor.builder(name);
            parseConstraints(builder);

            // optional "with Omega"
            if (tokens.match("with")) {
                String elemName = tokens.expectType(TokenType.TYPE_NAME).text();
                builder.element(new SpnDistinguishedElement(elemName));
            }

            // optional rules
            while (tokens.check("rule")) {
                tokens.advance();
                skipToEndOfRule();
            }

            typeRegistry.put(name, builder.build());
            return;
        }

        // type Name(components...) with optional operations
        if (tokens.check("(")) {
            tokens.advance();
            SpnTypeDescriptor.Builder builder = SpnTypeDescriptor.builder(name);
            while (!tokens.check(")")) {
                String compName = tokens.expectType(TokenType.IDENTIFIER).text();
                tokens.expect(":");
                FieldType ft = parseFieldType();
                builder.component(compName, ft);
                tokens.match(",");
            }
            tokens.expect(")");

            // optional constraint on component
            if (tokens.check("where")) {
                tokens.advance();
                // skip inline component constraints for now
                skipToNextDecl();
            }

            // optional operation definitions
            while (tokens.hasMore() && isOperator(tokens.peek())) {
                skipToEndOfRule();
            }

            typeRegistry.put(name, builder.build());
        }
    }

    private void parseConstraints(SpnTypeDescriptor.Builder builder) {
        do {
            String prop = tokens.peek().text();
            tokens.advance();
            String op = tokens.advance().text();
            SpnParseToken valTok = tokens.advance();
            double val = Double.parseDouble(valTok.text());

            Constraint c = switch (op) {
                case ">=" -> new Constraint.GreaterThanOrEqual(val);
                case ">" -> new Constraint.GreaterThan(val);
                case "<=" -> new Constraint.LessThanOrEqual(val);
                case "<" -> new Constraint.LessThan(val);
                case "==" -> new Constraint.ModuloEquals(1, (long) val); // exact value
                default -> throw tokens.error("Unknown constraint operator: " + op);
            };
            builder.constraint(c);
        } while (tokens.match(","));
    }

    private void parseDataDecl() {
        tokens.expect("data");
        String name = tokens.expectType(TokenType.TYPE_NAME).text();
        tokens.expect("=");

        List<SpnStructDescriptor> variants = new ArrayList<>();
        do {
            tokens.match("|"); // optional leading pipe
            String variantName = tokens.expectType(TokenType.TYPE_NAME).text();
            tokens.expect("(");
            List<String> fields = new ArrayList<>();
            while (!tokens.check(")")) {
                fields.add(tokens.expectType(TokenType.IDENTIFIER).text());
                tokens.match(",");
            }
            tokens.expect(")");
            SpnStructDescriptor desc = new SpnStructDescriptor(variantName, fields.toArray(new String[0]));
            structRegistry.put(variantName, desc);
            variants.add(desc);
        } while (tokens.match("|"));

        variantRegistry.put(name, new SpnVariantSet(name, variants.toArray(new SpnStructDescriptor[0])));
    }

    private void parseStructDecl() {
        tokens.expect("struct");
        String name = tokens.expectType(TokenType.TYPE_NAME).text();

        // optional type params <T, U>
        List<String> typeParams = new ArrayList<>();
        if (tokens.match("<")) {
            do {
                typeParams.add(tokens.expectType(TokenType.TYPE_NAME).text());
            } while (tokens.match(","));
            tokens.expect(">");
        }

        tokens.expect("(");
        SpnStructDescriptor.Builder builder = SpnStructDescriptor.builder(name);
        for (String tp : typeParams) builder.typeParam(tp);

        while (!tokens.check(")")) {
            String fieldName = tokens.expectType(TokenType.IDENTIFIER).text();
            if (tokens.match(":")) {
                builder.field(fieldName, parseFieldType());
            } else {
                builder.field(fieldName);
            }
            tokens.match(",");
        }
        tokens.expect(")");

        structRegistry.put(name, builder.build());
    }

    // ── Function declarations and definitions ──────────────────────────────

    private SpnStatementNode parsePureDecl() {
        tokens.expect("pure");
        String name = tokens.expectType(TokenType.IDENTIFIER).text();

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

        // Declaration only (no =), just register the signature
        if (!tokens.check("=")) {
            // Interface declaration - no body
            return null;
        }

        // Definition: = (params) { body }
        tokens.expect("=");
        return parseFunctionBody(name, paramTypes, returnType);
    }

    private SpnStatementNode parseFunctionBody(String name, List<FieldType> paramTypes,
                                                FieldType returnType) {
        tokens.expect("(");
        List<String> paramNames = new ArrayList<>();
        while (!tokens.check(")")) {
            paramNames.add(tokens.expectType(TokenType.IDENTIFIER).text());
            tokens.match(",");
        }
        tokens.expect(")");

        // Parse body in new scope
        pushScope();
        int[] userParamSlots = new int[paramNames.size()];
        for (int i = 0; i < paramNames.size(); i++) {
            userParamSlots[i] = currentScope.addLocal(paramNames.get(i));
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
        String name = tokens.expectType(TokenType.IDENTIFIER).text();

        // Optional type annotation
        if (tokens.match(":")) {
            parseFieldType(); // consume but ignore for now — types are in the signature
        }

        tokens.expect("=");
        SpnExpressionNode value = parseExpression();

        int slot = currentScope.addLocal(name);
        return SpnWriteLocalVariableNodeGen.create(value, slot);
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
                left = SpnEqualNodeGen.create(left, parseComparison());
            } else if (tokens.match("!=")) {
                left = SpnNotEqualNodeGen.create(left, parseComparison());
            } else break;
        }
        return left;
    }

    private SpnExpressionNode parseComparison() {
        SpnExpressionNode left = parseConcatenation();
        while (true) {
            if (tokens.match("<")) {
                left = SpnLessThanNodeGen.create(left, parseConcatenation());
            } else if (tokens.match(">")) {
                left = SpnGreaterThanNodeGen.create(left, parseConcatenation());
            } else if (tokens.match("<=")) {
                left = SpnLessEqualNodeGen.create(left, parseConcatenation());
            } else if (tokens.match(">=")) {
                left = SpnGreaterEqualNodeGen.create(left, parseConcatenation());
            } else break;
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
                left = SpnAddNodeGen.create(left, parseMulDiv());
            } else if (tokens.check("-") && !isUnaryMinus()) {
                tokens.advance();
                left = SpnSubtractNodeGen.create(left, parseMulDiv());
            } else break;
        }
        return left;
    }

    private SpnExpressionNode parseMulDiv() {
        SpnExpressionNode left = parseUnary();
        while (true) {
            if (tokens.match("*")) {
                left = SpnMultiplyNodeGen.create(left, parseUnary());
            } else if (tokens.match("/")) {
                left = SpnDivideNodeGen.create(left, parseUnary());
            } else if (tokens.match("%")) {
                left = SpnModuloNodeGen.create(left, parseUnary());
            } else break;
        }
        return left;
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
        if (tok.type() == TokenType.IDENTIFIER) {
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

            // Self-recursive call — the function is being defined right now
            if (name.equals(deferredFunctionName)) {
                SpnDeferredInvokeNode deferred = new SpnDeferredInvokeNode(
                        name, args.toArray(new SpnExpressionNode[0]));
                deferredCalls.add(deferred);
                return deferred;
            }

            throw tokens.error("Unknown function: " + name, tok);
        }

        // Variable read
        int slot = currentScope.lookupLocal(name);
        if (slot < 0) {
            throw tokens.error("Undefined variable: " + name, tok);
        }
        return new SpnReadLocalVariableNodeWrapper(slot);
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

    private FieldType parseFieldType() {
        SpnParseToken tok = tokens.peek();

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
                case "Long" -> FieldType.LONG;
                case "Double" -> FieldType.DOUBLE;
                case "String" -> FieldType.STRING;
                case "Boolean" -> FieldType.BOOLEAN;
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

    private boolean isOperator(SpnParseToken tok) {
        return tok.type() == TokenType.OPERATOR;
    }

    private void skipToEndOfRule() {
        // Skip tokens until we hit something that looks like a new declaration
        while (tokens.hasMore()) {
            SpnParseToken tok = tokens.peek();
            if (tok.text().equals("rule") || tok.text().equals("type") ||
                tok.text().equals("data") || tok.text().equals("struct") ||
                tok.text().equals("pure") || tok.text().equals("let")) {
                return;
            }
            tokens.advance();
        }
    }

    private void skipToNextDecl() {
        skipToEndOfRule();
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
