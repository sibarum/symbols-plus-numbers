package spn.lang;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import spn.node.SpnRootNode;
import spn.type.SpnStructValue;
import spn.type.SpnSymbol;
import spn.type.SpnSymbolTable;
import spn.type.SpnTupleValue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that SPN source code parses into executable AST nodes.
 * Each test parses a snippet, executes it, and checks the result.
 *
 * These tests serve double duty: they validate the parser and
 * act as the executable spec for the SPN standard library.
 */
class SpnParserTest {

    private SpnSymbolTable symbolTable;

    @BeforeEach
    void setUp() {
        symbolTable = new SpnSymbolTable();
    }

    private Object run(String source) {
        SpnParser parser = new SpnParser(source, null, symbolTable);
        SpnRootNode root = parser.parse();
        return root.getCallTarget().call();
    }

    private SpnParser parser(String source) {
        return new SpnParser(source, null, symbolTable);
    }

    // ── Literals ───────────────────────────────────────────────────────────

    @Nested
    class Literals {
        @Test void longLiteral() { assertEquals(42L, run("42")); }
        @Test void doubleLiteral() { assertEquals(3.14, run("3.14")); }
        @Test void stringLiteral() { assertEquals("hello", run("\"hello\"")); }
        @Test void trueLiteral() { assertEquals(true, run("true")); }
        @Test void falseLiteral() { assertEquals(false, run("false")); }

        @Test
        void symbolLiteral() {
            Object result = run(":red");
            assertInstanceOf(SpnSymbol.class, result);
            assertEquals("red", ((SpnSymbol) result).name());
        }
    }

    // ── Arithmetic ─────────────────────────────────────────────────────────

    @Nested
    class Arithmetic {
        @Test void addition() { assertEquals(7L, run("3 + 4")); }
        @Test void subtraction() { assertEquals(1L, run("5 - 4")); }
        @Test void multiplication() { assertEquals(12L, run("3 * 4")); }
        @Test void division() { assertEquals(3L, run("12 / 4")); }
        @Test void modulo() { assertEquals(1L, run("7 % 3")); }
        @Test void negation() { assertEquals(-5L, run("-5")); }

        @Test
        void precedence() {
            assertEquals(14L, run("2 + 3 * 4"));
            assertEquals(20L, run("(2 + 3) * 4"));
        }

        @Test
        void doubleArithmetic() {
            assertEquals(5.5, run("2.5 + 3.0"));
        }

        @Test
        void stringConcat() {
            assertEquals("hello world", run("\"hello\" ++ \" world\""));
        }
    }

    // ── Comparisons and booleans ───────────────────────────────────────────

    @Nested
    class Comparisons {
        @Test void lessThan() { assertEquals(true, run("3 < 5")); }
        @Test void greaterThan() { assertEquals(true, run("5 > 3")); }
        @Test void lessEqual() { assertEquals(true, run("3 <= 3")); }
        @Test void greaterEqual() { assertEquals(true, run("5 >= 4")); }
        @Test void equal() { assertEquals(true, run("42 == 42")); }
        @Test void notEqual() { assertEquals(true, run("1 != 2")); }

        @Test void and() { assertEquals(false, run("true && false")); }
        @Test void or() { assertEquals(true, run("false || true")); }

        @Test
        void compoundBoolean() {
            assertEquals(true, run("3 < 5 && 5 > 2"));
        }
    }

    // ── Let bindings ───────────────────────────────────────────────────────

    @Nested
    class LetBindings {
        @Test
        void simpleBinding() {
            assertEquals(42L, run("let x = 42\nx"));
        }

        @Test
        void bindingWithExpression() {
            assertEquals(7L, run("let x = 3 + 4\nx"));
        }

        @Test
        void multipleBindings() {
            assertEquals(7L, run("let x = 3\nlet y = 4\nx + y"));
        }

        @Test
        void reassignment() {
            assertEquals(10L, run("let x = 5\nx = 10\nx"));
        }
    }

    // ── Pure function definitions ──────────────────────────────────────────

    @Nested
    class Functions {
        @Test
        void simpleFunction() {
            assertEquals(7L, run("""
                pure add(Long, Long) -> Long = (a, b) { a + b }
                add(3, 4)
                """));
        }

        @Test
        void functionWithExpressionBody() {
            assertEquals(25L, run("""
                pure square(Long) -> Long = (x) { x * x }
                square(5)
                """));
        }

        @Test
        void functionCallingFunction() {
            assertEquals(50L, run("""
                pure square(Long) -> Long = (x) { x * x }
                pure double_(Long) -> Long = (x) { x + x }
                double_(square(5))
                """));
        }

        @Test
        void untypedFunction() {
            // Untyped params can be passed through but not operated on directly
            assertEquals(7L, run("""
                pure add(int, int) = (a, b) { a + b }
                add(3, 4)
                """));
        }

        @Test
        void untypedPassthrough() {
            // Untyped values can be passed to other _ parameters
            assertEquals(42L, run("""
                pure identity(_) = (x) { x }
                identity(42)
                """));
        }

        @Test
        void untypedArithmeticRejected() {
            assertThrows(SpnParseException.class, () -> run("""
                pure add(_, _) = (a, b) { a + b }
                add(3, 4)
                """));
        }

        @Test
        void untypedComparisonRejected() {
            assertThrows(SpnParseException.class, () -> run("""
                pure bigger(_, _) = (a, b) { a > b }
                bigger(3, 4)
                """));
        }

        @Test
        void functionTypeParam() {
            // Function name as type: structural matching on signature
            assertEquals(10L, run("""
                pure doubler(int) -> int = (x) { x + x }
                pure apply(doubler, int) -> int = (f, x) { f(x) }
                apply(doubler, 5)
                """));
        }

        @Test
        void functionTypeStructuralMatch() {
            // A different function with the same signature should be accepted
            assertEquals(25L, run("""
                pure doubler(int) -> int = (x) { x + x }
                pure squarer(int) -> int = (x) { x * x }
                pure apply(doubler, int) -> int = (f, x) { f(x) }
                apply(squarer, 5)
                """));
        }

        @Test
        void untypedCallRejected() {
            // Calling an untyped parameter as a function is rejected
            assertThrows(SpnParseException.class, () -> run("""
                pure apply(_, int) = (f, x) { f(x) }
                apply(42, 5)
                """));
        }

        @Test
        void actionFunction() {
            // Action functions can be declared and called
            assertEquals(42L, run("""
                action doSomething(int) -> int = (x) { x }
                doSomething(42)
                """));
        }

        @Test
        void actionCanCallPure() {
            // Action functions can freely call pure functions
            assertEquals(10L, run("""
                pure double_(int) -> int = (x) { x + x }
                action doWork(int) -> int = (x) { double_(x) }
                doWork(5)
                """));
        }

        @Test
        void pureCannotCallAction() {
            // Pure functions cannot call action functions
            assertThrows(SpnParseException.class, () -> run("""
                action impureWork(int) -> int = (x) { x }
                pure tryPure(int) -> int = (x) { impureWork(x) }
                tryPure(5)
                """));
        }
    }

    // ── Structs and data types ─────────────────────────────────────────────

    @Nested
    class Structs {
        @Test
        void structConstruction() {
            Object result = run("""
                struct Point(x: Double, y: Double)
                Point(1.0, 2.0)
                """);
            assertInstanceOf(SpnStructValue.class, result);
            SpnStructValue sv = (SpnStructValue) result;
            assertEquals("Point", sv.getDescriptor().getName());
            assertEquals(1.0, sv.getFields()[0]);
            assertEquals(2.0, sv.getFields()[1]);
        }

        @Test
        void dataVariants() {
            SpnParser p = parser("""
                data Shape
                  = Circle(radius)
                  | Rectangle(width, height)
                """);
            p.parse();
            assertNotNull(p.getStructRegistry().get("Circle"));
            assertNotNull(p.getStructRegistry().get("Rectangle"));
            assertNotNull(p.getVariantRegistry().get("Shape"));
        }

        @Test
        void constructVariant() {
            Object result = run("""
                data Shape
                  = Circle(radius)
                  | Rectangle(width, height)
                Circle(5.0)
                """);
            assertInstanceOf(SpnStructValue.class, result);
            assertEquals("Circle", ((SpnStructValue) result).getDescriptor().getName());
        }
        @Test
        void factoryIntercepts() {
            // Factory doubles both components
            assertEquals(6L, run("""
                type Pair(int, int)
                pure Pair(int, int) -> Pair = (a, b) {
                    this(a * 2, b * 2)
                }
                let p = Pair(3, 4)
                p.0
                """));
        }

        @Test
        void factoryChaining() {
            // Single-arg factory chains to 2-arg factory which doubles
            // Wrapper(1) → Wrapper(1,1) → this(2,2)
            assertEquals(2L, run("""
                type Wrapper(int, int)
                pure Wrapper(int, int) -> Wrapper = (a, b) {
                    this(a * 2, b * 2)
                }
                pure Wrapper(int) -> Wrapper = (a) {
                    Wrapper(a, a)
                }
                let w = Wrapper(1)
                w.0
                """));
        }

        @Test
        void rawConstructionWithoutFactory() {
            // No factory defined — raw construction works as before
            assertEquals(4L, run("""
                type Pair(int, int)
                let p = Pair(4, 5)
                p.0
                """));
        }
        @Test
        void associatedConstant() {
            assertEquals(42L, run("""
                type Wrapper(int)
                let Wrapper.default = Wrapper(42)
                Wrapper.default.0
                """));
        }

        @Test
        void associatedConstantUsedInExpression() {
            assertEquals(10L, run("""
                type Pair(int, int)
                let Pair.origin = Pair(0, 0)
                let p = Pair.origin
                p.0 + 10
                """));
        }
    }

    // ── Field access and methods ──────────────────────────────────────────

    @Nested
    class FieldsAndMethods {
        @Test
        void fieldAccess() {
            assertEquals(3.0, run("""
                struct Point(x: Double, y: Double)
                let p = Point(3.0, 4.0)
                p.x
                """));
        }

        @Test
        void fieldAccessSecond() {
            assertEquals(4.0, run("""
                struct Point(x: Double, y: Double)
                let p = Point(3.0, 4.0)
                p.y
                """));
        }

        @Test
        void methodDeclaration() {
            assertEquals(25.0, run("""
                struct Point(x: Double, y: Double)
                pure Point.squaredLength() -> float = () {
                    this.x * this.x + this.y * this.y
                }
                let p = Point(3.0, 4.0)
                p.squaredLength()
                """));
        }

        @Test
        void methodWithArgs() {
            assertEquals(2.0, run("""
                struct Point(x: Double, y: Double)
                pure Point.add(Point) -> Point = (other) {
                    Point(this.x + other.x, this.y + other.y)
                }
                let a = Point(1.0, 0.5)
                let b = Point(1.0, 1.5)
                let c = a.add(b)
                c.x
                """));
        }

        @Test
        void chainedFieldAccess() {
            assertEquals(7.0, run("""
                struct Point(x: Double, y: Double)
                struct Line(start: Point, end: Point)
                let l = Line(Point(3.0, 4.0), Point(7.0, 8.0))
                l.end.x
                """));
        }

        @Test
        void positionalAccessOnTuple() {
            assertEquals(10L, run("""
                let t = (10, 20, 30)
                t.0
                """));
        }

        @Test
        void positionalAccessSecond() {
            assertEquals(20L, run("""
                let t = (10, 20, 30)
                t.1
                """));
        }

        @Test
        void positionalAccessOnType() {
            assertEquals(3L, run("""
                type Rational(int, int)
                let r = Rational(3, 7)
                r.0
                """));
        }

        @Test
        void positionalAndNamedCoexist() {
            assertEquals(4.0, run("""
                struct Point(x: Double, y: Double)
                let p = Point(3.0, 4.0)
                p.1
                """));
        }
    }

    // ── Pattern matching ───────────────────────────────────────────────────

    @Nested
    class PatternMatching {
        @Test
        void matchOnStruct() {
            assertEquals(78.53975, (double) run("""
                data Shape
                  = Circle(radius)
                  | Rectangle(width, height)
                pure area(Shape) -> Double = (shape) {
                  match shape
                    | Circle(r) -> 3.14159 * r * r
                    | Rectangle(w, h) -> w * h
                }
                area(Circle(5.0))
                """), 0.001);
        }

        @Test
        void matchOnSymbol() {
            Object result = run("""
                pure opposite(_) = (dir) {
                  match dir
                    | :north -> :south
                    | :south -> :north
                    | :east -> :west
                    | :west -> :east
                }
                opposite(:north)
                """);
            assertInstanceOf(SpnSymbol.class, result);
            assertEquals("south", ((SpnSymbol) result).name());
        }

        @Test
        void matchWildcard() {
            assertEquals("other", run("""
                pure classify(_) -> String = (x) {
                  match x
                    | 0 -> "zero"
                    | _ -> "other"
                }
                classify(42)
                """));
        }

        @Test
        void matchWithGuard() {
            assertEquals("positive", run("""
                pure classify(_) -> String = (n) {
                  match n
                    | x | x < 0 -> "negative"
                    | x | x > 0 -> "positive"
                    | _ -> "zero"
                }
                classify(5)
                """));
        }

        @Test
        void matchStringPrefix() {
            assertEquals("web: example.com", run("""
                pure parseUrl(_) -> String = (url) {
                  match url
                    | "http://" ++ rest -> "web: " ++ rest
                    | _ -> "unknown"
                }
                parseUrl("http://example.com")
                """));
        }
    }

    // ── Collections ────────────────────────────────────────────────────────

    @Nested
    class Collections {
        @Test
        void arrayLiteral() {
            Object result = run("[1, 2, 3]");
            assertNotNull(result);
        }

        @Test
        void emptyArray() {
            Object result = run("[]");
            assertNotNull(result);
        }

        @Test
        void dictionaryLiteral() {
            Object result = run("[:host \"localhost\", :port \"8080\"]");
            assertNotNull(result);
        }

        @Test
        void arrayAccess() {
            assertEquals(20L, run("""
                let arr = [10, 20, 30]
                arr[1]
                """));
        }
    }

    // ── Tuples ─────────────────────────────────────────────────────────────

    @Nested
    class Tuples {
        @Test
        void tupleLiteral() {
            Object result = run("(1, 2, 3)");
            assertInstanceOf(SpnTupleValue.class, result);
            SpnTupleValue tv = (SpnTupleValue) result;
            assertEquals(3, tv.getElements().length);
            assertEquals(1L, tv.getElements()[0]);
            assertEquals(2L, tv.getElements()[1]);
            assertEquals(3L, tv.getElements()[2]);
        }
    }

    // ── While / do (streaming) ─────────────────────────────────────────────

    @Nested
    class WhileAndStreaming {
        @Test
        void whileConditionLoop() {
            assertEquals(10L, run("""
                let i = 0
                while {i < 10} do {
                  i = i + 1
                }
                i
                """));
        }

        @Test
        void streamingWithProducer() {
            assertEquals(45L, run("""
                pure range(Long, Long) = (start, end) {
                  let i = start
                  while {i < end} do {
                    yield i
                    i = i + 1
                  }
                }
                let sum = 0
                while range(1, 10) do (n) {
                  sum = sum + n
                }
                sum
                """));
        }
    }

    // ── Type declarations ──────────────────────────────────────────────────

    @Nested
    class TypeDeclarations {
        @Test
        void symbolType() {
            SpnParser p = parser("""
                type Color = Symbol
                """);
            p.parse();
            assertNotNull(p.getTypeRegistry().get("Color"));
        }

        @Test
        void structDeclaration() {
            SpnParser p = parser("""
                struct Point(x: Double, y: Double)
                """);
            p.parse();
            assertNotNull(p.getStructRegistry().get("Point"));
            assertEquals(2, p.getStructRegistry().get("Point").fieldCount());
        }

        @Test
        void genericStruct() {
            SpnParser p = parser("""
                struct Pair<T, U>(first, second)
                """);
            p.parse();
            assertTrue(p.getStructRegistry().get("Pair").isGeneric());
        }
    }

    // ── Integration: standard library patterns ─────────────────────────────

    @Nested
    class StandardLibraryPatterns {
        @Test
        void recursiveFunction() {
            assertEquals(120L, run("""
                pure factorial(Long) -> Long = (n) {
                  match n
                    | 0 -> 1
                    | x -> x * factorial(x - 1)
                }
                factorial(5)
                """));
        }

        @Test
        void functionComposition() {
            assertEquals(100L, run("""
                pure double_(Long) -> Long = (x) { x + x }
                pure square(Long) -> Long = (x) { x * x }
                square(double_(5))
                """));
        }

        @Test
        void matchOnLiteral() {
            assertEquals("five", run("""
                pure name(Long) -> String = (n) {
                  match n
                    | 5 -> "five"
                    | _ -> "other"
                }
                name(5)
                """));
        }
    }

    @Test
    void rationalArithmetic() {
        // Rational addition: 1/2 + 3/4 = (1*4 + 2*3)/(2*4) = 10/8
        String source = """
type Rational(int, int) where (_, d) { d != 0 }
promote int -> Rational = (i) { Rational(i, 1) }
pure *(Rational, Rational) -> Rational = ((n1, d1), (n2, d2)) { Rational(n1*n2, d1*d2) }
pure +(Rational, Rational) -> Rational = ((n1, d1), (n2, d2)) { Rational(n1*d2 + d1*n2, d1*d2) }
let x = Rational(1,2)
let y = Rational(3,4)
x + y
""";
        Object result = run(source);
        assertNotNull(result);
        assertTrue(result.toString().contains("10"), "Numerator should be 10: " + result);
        assertTrue(result.toString().contains("8"), "Denominator should be 8: " + result);
    }

    @Test
    void numericsIndexFullDispatch() {
        // Full numerics/index.spn: Rational + ComplexPolar via promotion
        Object result = run("""
type Rational(int, int) where (_, denominator) { denominator != 0 }
type ComplexPolar(Rational, Rational)
promote int -> Rational = (i) { Rational(i, 1) }
pure *(Rational, Rational) -> Rational = ((n1, d1), (n2, d2)) { Rational(n1*n2, d1*d2) }
pure +(Rational, Rational) -> Rational = ((n1, d1), (n2, d2)) { Rational(n1*d2 + d1*n2, d1*d2) }
promote Rational -> ComplexPolar = (r) { ComplexPolar(r, Rational(0,1)) }
pure -(Rational, Rational) -> Rational = ((n1, d1), (n2, d2)) { Rational(n1*d2 - d1*n2, d1*d2) }
pure *_dot(ComplexPolar, ComplexPolar) -> Rational = ((r1, i1),(r2, i2)) { r1*i2 + r2*i1 }
pure *_cross(ComplexPolar, ComplexPolar) -> Rational = ((r1, i1), (r2, i2)) { r1*r2 - r2*i1 }
pure *(ComplexPolar, ComplexPolar) -> ComplexPolar = ((r1, i1),(r2, i2)) { ComplexPolar(r1*r2, i1+i2) }
pure +(ComplexPolar, ComplexPolar) -> ComplexPolar = (c1,c2) { ComplexPolar(c1 *_dot c2, c1 *_cross c2) }
let x = Rational(5,11)
let y = ComplexPolar(Rational(2,1),Rational(3,1))
x+y
""");
        assertNotNull(result);
        assertTrue(result.toString().contains("ComplexPolar"), "Should be ComplexPolar: " + result);
    }

    @Test
    void runtimeErrorIncludesLocationAndSignature() {
        // Trigger a type error by applying + to types without a matching overload
        String source = """
type Foo(int)
type Bar(int)
let x = Foo(1)
let y = Bar(2)
x + y
""";
        SpnParser parser = new SpnParser(source, "test.spn", null, symbolTable, null);
        SpnRootNode root = parser.parse();
        try {
            root.getCallTarget().call();
            fail("Expected SpnException");
        } catch (spn.language.SpnException e) {
            String msg = e.formatMessage();
            // Should include source file and line
            assertTrue(msg.contains("test.spn"), "Should include file name: " + msg);
            // Should include the operator signature
            assertTrue(msg.contains("+(Foo, Bar)"), "Should include op signature: " + msg);
        }
    }
}
