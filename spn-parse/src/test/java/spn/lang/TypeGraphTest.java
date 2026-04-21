package spn.lang;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import spn.type.SpnSymbolTable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TypeGraph population and queries — the substrate that IDE features
 * (go-to-def, hover, trace links) sit on. Every declaration kind should land
 * in the graph with the NAME token's position; local bindings and parameters
 * should be queryable by scope.
 *
 * <p>These tests exist because position tracking has been a recurring source of
 * off-by-one bugs. If a new feature needs a position the graph doesn't track,
 * extend this file first.
 */
class TypeGraphTest {

    private SpnSymbolTable symbolTable;

    @BeforeEach
    void setUp() {
        symbolTable = new SpnSymbolTable();
    }

    private TypeGraph parseToGraph(String source) {
        SpnParser parser = new SpnParser(source, "test.spn", null, symbolTable, null);
        parser.parse();
        return parser.getTypeGraph();
    }

    // ── Name-token capture ─────────────────────────────────────────────────

    @Test
    void functionNodeRecordsNameTokenPosition() {
        // `sigmoid` is at column 5 on line 1 (after `pure `). The name range
        // should point there, not at the opening brace.
        TypeGraph g = parseToGraph("pure sigmoid(float) -> float = (x) { x }");
        TypeGraph.Node n = g.lookupFirst("sigmoid");
        assertNotNull(n);
        assertEquals(TypeGraph.Kind.FUNCTION, n.kind());
        assertEquals(1, n.nameRange().startLine());
        assertEquals(5, n.nameRange().startCol());
    }

    @Test
    void typeNodeRecordsNameTokenPosition() {
        TypeGraph g = parseToGraph("type Point(x: float, y: float)");
        TypeGraph.Node n = g.lookupFirst("Point");
        assertNotNull(n);
        assertEquals(TypeGraph.Kind.TYPE, n.kind());
        assertEquals(1, n.nameRange().startLine());
        assertEquals(5, n.nameRange().startCol());
    }

    @Test
    void operatorNodeRecordsNameTokenPosition() {
        // Operator declaration: `pure +(int, int) -> int = (a, b) { a + b }`
        // The `+` is at column 5.
        TypeGraph g = parseToGraph(
                "type Foo(v: int)\n" +
                "pure +(Foo, Foo) -> Foo = (a, b) { Foo(a.v + b.v) }");
        TypeGraph.Node op = g.operators("+").stream()
                .filter(n -> n.paramTypes()[0].describe().equals("Foo"))
                .findFirst().orElse(null);
        assertNotNull(op, "operator + on Foo should be recorded");
        assertTrue(op.nameRange().isKnown(), "operator node must have a position");
        assertEquals(2, op.nameRange().startLine());
        assertEquals(5, op.nameRange().startCol());
    }

    // ── Local bindings ─────────────────────────────────────────────────────

    @Test
    void letBindingIsInTypeGraph() {
        TypeGraph g = parseToGraph(
                "pure foo() -> int = () {\n" +
                "  let myVar = 42\n" +
                "  myVar\n" +
                "}");
        TypeGraph.Node local = g.lookupFirst("myVar");
        assertNotNull(local);
        assertEquals(TypeGraph.Kind.LOCAL_BINDING, local.kind());
        assertEquals(2, local.nameRange().startLine());
    }

    @Test
    void parameterIsInTypeGraph() {
        TypeGraph g = parseToGraph(
                "pure sigmoid(float) -> float = (x) { x }");
        TypeGraph.Node param = g.lookupFirst("x");
        assertNotNull(param);
        assertEquals(TypeGraph.Kind.PARAMETER, param.kind());
    }

    @Test
    void findLocalInScopeReturnsNearestPrior() {
        TypeGraph g = parseToGraph(
                "pure foo() -> int = () {\n" +
                "  let x = 1\n" +
                "  let y = 2\n" +
                "  x + y\n" +
                "}");
        // Click at line 4 (the `x + y` expression)
        TypeGraph.Node x = g.findLocalInScope("test.spn", 4, 2, "x");
        assertNotNull(x);
        assertEquals(2, x.nameRange().startLine(), "should find x on line 2");
        TypeGraph.Node y = g.findLocalInScope("test.spn", 4, 2, "y");
        assertNotNull(y);
        assertEquals(3, y.nameRange().startLine(), "should find y on line 3");
    }

    @Test
    void findLocalInScopeStopsAtFunctionBoundary() {
        // Two functions each declaring a local named `x`. A click inside
        // `bar` should see only bar's `x`, not foo's.
        TypeGraph g = parseToGraph(
                "pure foo() -> int = () {\n" +  // 1
                "  let x = 1\n" +                // 2: foo's x
                "  x\n" +                         // 3
                "}\n" +                           // 4
                "pure bar() -> int = () {\n" +  // 5
                "  let x = 2\n" +                // 6: bar's x
                "  x\n" +                         // 7
                "}");                             // 8
        // Click at line 7 (inside bar) → must find bar's x (line 6), not foo's.
        TypeGraph.Node found = g.findLocalInScope("test.spn", 7, 2, "x");
        assertNotNull(found);
        assertEquals(6, found.nameRange().startLine(),
                "should pick bar's x on line 6, not foo's x on line 2");
    }

    @Test
    void findLocalInScopeFindsParameter() {
        TypeGraph g = parseToGraph(
                "pure sigmoid(float) -> float = (x) {\n" +
                "  x\n" +
                "}");
        // Click on `x` inside the body (line 2).
        TypeGraph.Node param = g.findLocalInScope("test.spn", 2, 2, "x");
        assertNotNull(param);
        assertEquals(TypeGraph.Kind.PARAMETER, param.kind());
    }

    // ── Struct fields ──────────────────────────────────────────────────────

    @Test
    void structFieldIsInTypeGraph() {
        TypeGraph g = parseToGraph("type AppState(counter: int)");
        // Fields are stored under composite name "Type.field".
        TypeGraph.Node field = g.lookupFirst("AppState.counter");
        assertNotNull(field);
        assertEquals(TypeGraph.Kind.FIELD, field.kind());
        assertTrue(field.nameRange().isKnown());
        assertEquals(1, field.nameRange().startLine());
        assertEquals(14, field.nameRange().startCol(),
                "field name 'counter' starts at col 14 (after 'type AppState(')");
    }

    @Test
    void multipleTypesWithSameFieldNameStaySeparate() {
        TypeGraph g = parseToGraph(
                "type A(x: int)\n" +
                "type B(x: float)");
        assertNotNull(g.lookupFirst("A.x"));
        assertNotNull(g.lookupFirst("B.x"));
        // Plain lookup shouldn't conflate them — the composite name is the
        // only key under which fields are stored.
        assertEquals(0, g.lookup("x").size());
    }

    // ── CallTarget reverse index ───────────────────────────────────────────

    @Test
    void byCallTargetFindsFunctionNode() {
        TypeGraph g = parseToGraph(
                "pure double(int) -> int = (n) { n + n }");
        TypeGraph.Node fn = g.lookupFirst("double");
        assertNotNull(fn);
        assertNotNull(fn.callTarget());
        assertSame(fn, g.byCallTarget(fn.callTarget()));
    }

    // ── SourceRange convention round-trip ──────────────────────────────────

    @Test
    void editorCoordsSubtractsOneFromLineButNotCol() {
        TypeGraph g = parseToGraph(
                "\n" +
                "pure foo() -> int = () { 1 }");
        TypeGraph.Node fn = g.lookupFirst("foo");
        assertEquals(2, fn.nameRange().startLine());
        assertEquals(5, fn.nameRange().startCol());
        var editor = fn.nameRange().toEditorCoords();
        assertEquals(1, editor.startLine(), "editor line is 0-based");
        assertEquals(5, editor.startCol(), "editor col is the same 0-based col");
    }

    // ── Type-reference use sites (for IDE go-to-def on type names) ──────────

    private SpnParser parseForTypeRefs(String source) {
        SpnParser parser = new SpnParser(source, "test.spn", null, symbolTable, null);
        parser.parse();
        return parser;
    }

    @Test
    void typeReferenceInSignatureResolvesToLocalDeclaration() {
        // `Point` is declared on line 1 and referenced as a parameter type
        // and return type on line 2. Both references should resolve to the
        // declaration's name range.
        SpnParser parser = parseForTypeRefs(
                "type Point(x: float, y: float)\n" +
                "pure identity(Point) -> Point = (p) { p }");

        var refs = parser.getTypeReferenceSites();
        // At least two refs: parameter type and return type on line 2.
        var pointRefs = refs.stream()
                .filter(r -> "Point".equals(r.typeName()))
                .toList();
        assertTrue(pointRefs.size() >= 2,
                "expected >=2 Point references, got " + pointRefs.size());

        var declNode = parser.getTypeGraph().lookupFirst("Point");
        assertNotNull(declNode);
        // Every Point reference should point at the declaration and carry a
        // known use-site range.
        for (var r : pointRefs) {
            assertEquals("test.spn", r.targetFile(), "local type: targetFile is current file");
            assertEquals(declNode.nameRange(), r.targetRange(),
                    "local type: targetRange is the declaration's name range");
            assertTrue(r.useSite().isKnown());
            assertNull(r.importRange(), "local type: no import statement");
        }
    }

    @Test
    void typeReferenceIgnoresBuiltinPrimitives() {
        // `int` / `float` are builtins — no source to jump to, don't record.
        SpnParser parser = parseForTypeRefs(
                "pure add(int, int) -> int = (a, b) { a + b }");
        var refs = parser.getTypeReferenceSites();
        assertTrue(refs.stream().noneMatch(r -> "int".equals(r.typeName())),
                "primitive `int` should not be recorded as a type reference");
    }

    @Test
    void typeReferenceUseSiteMatchesTokenRange() {
        // The reference on line 2 to `Point` starts at column 14 (after
        // `pure ident(`). Check the use-site range reflects the token position.
        SpnParser parser = parseForTypeRefs(
                "type Point(x: float, y: float)\n" +
                "pure ident(Point) -> int = (p) { 0 }");
        var ref = parser.getTypeReferenceSites().stream()
                .filter(r -> "Point".equals(r.typeName()))
                .findFirst().orElse(null);
        assertNotNull(ref);
        assertEquals(2, ref.useSite().startLine(), "use site is on line 2");
        assertEquals(11, ref.useSite().startCol(),
                "use site starts at col 11 (after `pure ident(`)");
    }
}
