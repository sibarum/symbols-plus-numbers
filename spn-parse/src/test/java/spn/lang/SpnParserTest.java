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

    // ── Private constructor fields ─────────────────────────────────────────

    @Nested
    class PrivateConstructorFields {
        @Test
        void bareTypeWithConstructorField() {
            // type Stack with no declared fields + constructor-defined private field
            assertEquals(5L, run("""
                type Stack
                pure Stack(int) -> Stack = (initial) {
                  let this.top = initial
                  this(initial)
                }
                pure Stack.peek() -> int = () { this.top }
                let s = Stack(5)
                s.peek()
                """));
        }

        @Test
        void privateFieldNotAccessibleExternally() {
            assertThrows(SpnParseException.class, () -> run("""
                type Stack
                pure Stack(int) -> Stack = (initial) {
                  let this.top = initial
                  this(initial)
                }
                let s = Stack(5)
                s.top
                """));
        }

        @Test
        void privateFieldAccessibleFromMethod() {
            assertEquals(10L, run("""
                type Counter
                pure Counter(int) -> Counter = (n) {
                  let this.value = n
                  this(n)
                }
                pure Counter.get() -> int = () { this.value }
                pure Counter.add(int) -> Counter = (n) {
                  Counter(this.value + n)
                }
                let c = Counter(3).add(7)
                c.get()
                """));
        }
    }

    // ── Error recovery ─────────────────────────────────────────────────────

    @Nested
    class ErrorRecovery {
        @Test
        void multipleErrorsCollected() {
            // Two broken declarations — both should be reported.
            SpnParser p = parser("""
                type Good(int)
                let x = @@@
                type AlsoGood(int)
                let y = @@@
                """);
            assertThrows(SpnParseException.class, p::parse);
            // Both errors collected, not just the first
            assertTrue(p.getErrors().size() >= 2,
                    "Expected at least 2 errors, got " + p.getErrors().size());
        }

        @Test
        void validDeclarationsAfterErrorStillRegister() {
            // The type after the broken let should still be registered.
            SpnParser p = parser("""
                type Before(int)
                let x = @@@
                type After(int)
                let good = After(42)
                """);
            assertThrows(SpnParseException.class, p::parse);
            // 'Before' and 'After' should both be in the struct registry
            assertNotNull(p.getStructRegistry().get("Before"),
                    "Before should be registered despite later error");
            assertNotNull(p.getStructRegistry().get("After"),
                    "After should be registered — recovery skipped past the broken let");
        }
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
                type Circle(radius: Double)
                type Rectangle(width: Double, height: Double)
                data Shape = Circle | Rectangle
                """);
            p.parse();
            assertNotNull(p.getStructRegistry().get("Circle"));
            assertNotNull(p.getStructRegistry().get("Rectangle"));
            assertNotNull(p.getVariantRegistry().get("Shape"));
        }

        @Test
        void constructVariant() {
            Object result = run("""
                type Circle(radius: Double)
                type Rectangle(width: Double, height: Double)
                data Shape = Circle | Rectangle
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
                type Circle(radius: Double)
                type Rectangle(width: Double, height: Double)
                data Shape = Circle | Rectangle
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

    void parseErrorForUndefinedOperatorOnNonPrimitives() {
        // Applying + to struct types with no matching overload should fail at
        // parse time (not runtime), with a helpful message naming the types.
        String source = """
type Foo(int)
type Bar(int)
let x = Foo(1)
let y = Bar(2)
x + y
""";
        SpnParser parser = new SpnParser(source, "test.spn", null, symbolTable, null);
        try {
            parser.parse();
            fail("Expected SpnParseException");
        } catch (SpnParseException e) {
            String msg = e.getMessage();
            // Should name the missing overload's operand types
            assertTrue(msg.contains("Foo") && msg.contains("Bar"),
                    "Should include operand types: " + msg);
            assertTrue(msg.toLowerCase().contains("no overload"),
                    "Should say no overload: " + msg);
        }
    }

    // ── Union types and type inference ─────────────────────────────────────

    @Nested
    class UnionTypesAndInference {

        @Test
        void anonymousUnionInParamType() {
            assertEquals(78.53975, (double) run("""
                type Circle(radius: Double)
                type Rectangle(width: Double, height: Double)
                pure area(Circle | Rectangle) -> Double = (shape) {
                  match shape
                    | Circle(r) -> 3.14159 * r * r
                    | Rectangle(w, h) -> w * h
                }
                area(Circle(5.0))
                """), 0.001);
        }

        @Test
        void anonymousUnionOrderIndependent() {
            // Rectangle | Circle should work the same as Circle | Rectangle
            assertEquals(12.0, (double) run("""
                type Circle(radius: Double)
                type Rectangle(width: Double, height: Double)
                pure area(Rectangle | Circle) -> Double = (shape) {
                  match shape
                    | Circle(r) -> 3.14159 * r * r
                    | Rectangle(w, h) -> w * h
                }
                area(Rectangle(3.0, 4.0))
                """), 0.001);
        }

        @Test
        void letDestructureThenMethodCall() {
            // Type inference through tuple destructure → method call
            assertEquals(42L, run("""
                type Wrapper(value: int)
                pure Wrapper.get() -> int = () { this.value }
                let w = Wrapper(42)
                let (v) = w
                v
                """));
        }

        @Test
        void blockExpressionPreservesType() {
            // Block expression's type = last expression's type
            assertEquals(10L, run("""
                type Wrapper(value: int)
                pure Wrapper.get() -> int = () { this.value }
                let w = {
                  let x = 5
                  Wrapper(x + x)
                }
                w.get()
                """));
        }

        @Test
        void matchUnifiesSameType() {
            // All match branches return same type → result type is tracked
            assertEquals(10L, run("""
                type Wrapper(value: int)
                pure Wrapper.get() -> int = () { this.value }
                let w = match true
                  | true -> Wrapper(10)
                  | false -> Wrapper(20)
                w.get()
                """));
        }

        @Test
        void unknownTypeNameThrows() {
            assertThrows(SpnParseException.class, () -> run("""
                type Foo(bar: NonexistentType)
                """));
        }

        @Test
        void variantTypeInParamPosition() {
            // Named data union in param position (existing feature, regression test)
            assertEquals(78.53975, (double) run("""
                type Circle(radius: Double)
                type Rectangle(width: Double, height: Double)
                data Shape = Circle | Rectangle
                pure area(Shape) -> Double = (shape) {
                  match shape
                    | Circle(r) -> 3.14159 * r * r
                    | Rectangle(w, h) -> w * h
                }
                area(Circle(5.0))
                """), 0.001);
        }

        @Test
        void tupleReturnType() {
            Object result = run("""
                pure swap(int, int) -> (int, int) = (a, b) { (b, a) }
                swap(1, 2)
                """);
            assertInstanceOf(SpnTupleValue.class, result);
            SpnTupleValue tuple = (SpnTupleValue) result;
            assertEquals(2L, tuple.get(0));
            assertEquals(1L, tuple.get(1));
        }

        @Test
        void letTupleDestructure() {
            assertEquals(30L, run("""
                let (a, b) = (10, 20)
                a + b
                """));
        }

        @Test
        void letTupleDestructureWithSkip() {
            assertEquals(20L, run("""
                let (_, b) = (10, 20)
                b
                """));
        }

        @Test
        void concreteTypeAssignableToUnionParam() {
            // Passing Circle to a function expecting Circle | Rectangle
            assertEquals(78.53975, (double) run("""
                type Circle(radius: Double)
                type Rectangle(width: Double, height: Double)
                pure area(Circle | Rectangle) -> Double = (shape) {
                  match shape
                    | Circle(r) -> 3.14159 * r * r
                    | Rectangle(w, h) -> w * h
                }
                let c = Circle(5.0)
                area(c)
                """), 0.001);
        }

        @Test
        void nonExhaustiveMatchOnUnionThrows() {
            // Missing Rectangle branch → parse error
            assertThrows(SpnParseException.class, () -> run("""
                type Circle(radius: Double)
                type Rectangle(width: Double, height: Double)
                pure area(Circle | Rectangle) -> Double = (shape) {
                  match shape
                    | Circle(r) -> 3.14159 * r * r
                }
                area(Circle(5.0))
                """));
        }

        @Test
        void exhaustiveMatchOnUnionSucceeds() {
            // All variants covered → no error
            assertEquals(78.53975, (double) run("""
                type Circle(radius: Double)
                type Rectangle(width: Double, height: Double)
                pure area(Circle | Rectangle) -> Double = (shape) {
                  match shape
                    | Circle(r) -> 3.14159 * r * r
                    | Rectangle(w, h) -> w * h
                }
                area(Circle(5.0))
                """), 0.001);
        }

        @Test
        void exhaustiveMatchWithWildcard() {
            // Wildcard covers everything → no error
            assertEquals(0.0, (double) run("""
                type Circle(radius: Double)
                type Rectangle(width: Double, height: Double)
                pure area(Circle | Rectangle) -> Double = (shape) {
                  match shape
                    | Circle(r) -> 3.14159 * r * r
                    | _ -> 0.0
                }
                area(Rectangle(3.0, 4.0))
                """), 0.001);
        }

        @Test
        void macroDeclarationAndInvocation() {
            // Macro that derives a "double" function for any numeric type
            assertEquals(10L, run("""
                macro deriveDouble<T> = {
                  pure double(T) -> T = (x) { x + x }
                }
                deriveDouble<int>
                double(5)
                """));
        }

        @Test
        void macroDerivesMultipleFunctions() {
            // The motivating use case: one macro emits several functions for a type.
            assertEquals(7L, run("""
                type Point(int, int)
                macro derivePointOps<T> = {
                  pure sumX(T, T) -> int = (a, b) { a.0 + b.0 }
                  pure sumY(T, T) -> int = (a, b) { a.1 + b.1 }
                }
                derivePointOps<Point>
                sumX(Point(3, 9), Point(4, 1))
                """));
        }

        @Test
        void macroWithMultipleParams() {
            assertEquals(7L, run("""
                macro deriveCombine<A, B> = {
                  pure combine(A, B) -> int = (a, b) { a + b }
                }
                deriveCombine<int, int>
                combine(3, 4)
                """));
        }

        @Test
        void tuplePatternOnStructSubjectThrows() {
            // Nominal typing: (n, d) is a tuple pattern, can't match a Rational struct
            assertThrows(SpnParseException.class, () -> run("""
                type Rational(int, int)
                let r = Rational(3, 4)
                match r
                  | (n, d) -> n
                """));
        }

        @Test
        void structPatternOnWrongStructThrows() {
            assertThrows(SpnParseException.class, () -> run("""
                type Circle(radius: int)
                type Square(side: int)
                let c = Circle(5)
                match c
                  | Square(s) -> s
                """));
        }

        @Test
        void arrayPatternOnTupleSubjectThrows() {
            assertThrows(SpnParseException.class, () -> run("""
                let t = (1, 2, 3)
                match t
                  | [] -> 0
                """));
        }

        @Test
        void structPatternOnMatchingStructSubjectWorks() {
            assertEquals(3L, run("""
                type Rational(int, int)
                let r = Rational(3, 4)
                match r
                  | Rational(n, d) -> n
                """));
        }

        @Test
        void comparisonOnStructWithoutOverloadThrowsAtParse() {
            // < / > / <= / >= on structs without an overload is a parse error
            assertThrows(SpnParseException.class, () -> run("""
                type Foo(int)
                let a = Foo(1)
                let b = Foo(2)
                a < b
                """));
        }

        @Test
        void methodOnWrongTypeThrowsAtParse() {
            // Calling a method that doesn't exist for the receiver type
            assertThrows(SpnParseException.class, () -> run("""
                type Circle(radius: int)
                pure Circle.area() -> int = () { this.radius * this.radius }
                let n = 42
                n.area()
                """));
        }

        @Test
        void constructorWrongArityThrowsAtParse() {
            // Passing wrong number of args to a constructor
            assertThrows(SpnParseException.class, () -> run("""
                type Point(int, int)
                Point(1, 2, 3)
                """));
        }

        @Test
        void unaryNegateDispatchesForStructs() {
            // -(T) -> T dispatches for non-primitive types
            assertEquals(-7L, run("""
                type Box(int)
                pure -(Box) -> int = (x) { -(x.0) }
                let w = Box(7)
                -w
                """));
        }

        @Test
        void multiplicativeInverseViaOneSlash() {
            // 1/x dispatches to unary /(T) -> T overload
            assertEquals(42L, run("""
                type Box(int)
                pure /(Box) -> int = (b) { b.0 }
                let b = Box(42)
                1/b
                """));
        }

        @Test
        void twoSlashDoesNotTriggerInverse() {
            // 2/x should NOT trigger unary inverse — only literal 1 does
            assertThrows(Exception.class, () -> run("""
                type Box(int)
                pure /(Box) -> int = (b) { b.0 }
                let b = Box(42)
                2/b
                """));
        }

        @Test
        void macroGeneratesTypedFunction() {
            // Use case 1: macro generates a function with a specific type signature.
            // deriveGetter(T) emits a function typed to T, dispatched via multiple dispatch.
            assertEquals(3L, run("""
                type Box(int)
                type Pair(int, int)

                macro deriveGetter<T> = {
                  pure getFirst(T) -> int = (x) { x.0 }
                }

                deriveGetter<Box>
                deriveGetter<Pair>

                getFirst(Box(3))
                """));
        }

        @Test
        void macroMultipleDispatchOverloads() {
            // Use case 2: same macro invoked for different types produces
            // overloaded functions that dispatch correctly.
            assertEquals(10L, run("""
                type Box(int)
                type Pair(int, int)

                macro deriveSum<T> = {
                  pure sumFields(T) -> int = (x) { x.0 + x.1 }
                }

                deriveSum<Pair>
                sumFields(Pair(3, 7))
                """));
        }

        @Test
        void macroEmitType() {
            // The new macro v2 pattern: macro emits a type, caller binds it.
            // Internal declarations (helperFn) are discarded after macro.
            assertEquals(99L, run("""
                macro constructWrapper<T> = {
                  type Wrapper(T)
                  pure Wrapper.unwrap() -> T = () { this.0 }
                  pure helperFn(int) -> int = (x) { x + 1 }
                  emit Wrapper
                }

                type Box(int)
                type SafeBox = constructWrapper<Box>

                let sb = SafeBox(Box(99))
                sb.unwrap().0
                """));
        }

        @Test
        void macroEmitTypeInternalDiscarded() {
            // helperFn defined inside the macro should NOT be visible after expansion
            assertThrows(SpnParseException.class, () -> run("""
                macro constructWrapper<T> = {
                  type Wrapper(T)
                  pure helperFn(int) -> int = (x) { x + 1 }
                  emit Wrapper
                }

                type Box(int)
                type SafeBox = constructWrapper<Box>

                helperFn(5)
                """));
        }

        @Test
        void macroEmitOperatorsRegisterGlobally() {
            // Operator overloads inside a macro persist globally (no emit needed)
            assertEquals(true, run("""
                type Box(int)
                pure ==(Box, Box) -> bool = (a, b) { a.0 == b.0 }

                macro deriveAdd<T> = {
                  pure +(T, T) -> T = (a, b) { T(a.0 + b.0) }
                }

                deriveAdd<Box>
                Box(3) + Box(4) == Box(7)
                """));
        }

        @Test
        void macroGeneratesWrapperType() {
            // Use case 1 advanced: macro generates a WRAPPER TYPE.
            // The second parameter lets the caller name the wrapper.
            assertEquals(99L, run("""
                type Box(int)

                macro deriveWrapper<T, W> = {
                  type W(T)
                  pure W.unwrap() -> T = () { this.0 }
                }

                deriveWrapper<Box, SafeBox>
                let sb = SafeBox(Box(99))
                sb.unwrap().0
                """));
        }

        @Test
        void blockWithLetAndTupleReturn() {
            Object result = run("""
                let t = {
                  let x = 3
                  let y = 4
                  (x, y)
                }
                t
                """);
            assertInstanceOf(SpnTupleValue.class, result);
        }
    }

    // ── Macro conditional blocks ───────────────────────────────────────────
    //
    // `<! if COND !> { A } <! else !> { B }` appears inside a macro body.
    // At expansion time the condition is evaluated against substituted
    // parameters; the entire directive is replaced with the inner tokens of
    // the chosen branch (braces stripped) so declarations inside that branch
    // register at the surrounding scope.

    @Nested
    class MacroConditionalBlocks {

        @Test void symbolLiteralSelectsFirstBranch() {
            assertEquals(10L, run("""
                macro withBoost<flavor> = {
                  type Wrapper(int)
                  <! if flavor == :fast !> {
                    pure Wrapper.go() -> int = () { this.0 * 2 }
                  } <! else !> {
                    pure Wrapper.go() -> int = () { this.0 }
                  }
                  emit Wrapper
                }

                type Fast = withBoost<:fast>
                Fast(5).go()
                """));
        }

        @Test void symbolLiteralSelectsElseBranch() {
            assertEquals(5L, run("""
                macro withBoost<flavor> = {
                  type Wrapper(int)
                  <! if flavor == :fast !> {
                    pure Wrapper.go() -> int = () { this.0 * 2 }
                  } <! else !> {
                    pure Wrapper.go() -> int = () { this.0 }
                  }
                  emit Wrapper
                }

                type Slow = withBoost<:slow>
                Slow(5).go()
                """));
        }

        @Test void emptyElseAsIncludeOnlyIf() {
            // `<! if X !> { decl } <! else !> {}` = "include only when X"
            assertEquals(42L, run("""
                macro maybeExtra<include> = {
                  type Box(int)
                  <! if include == :yes !> {
                    pure Box.boost() -> int = () { this.0 + 40 }
                  } <! else !> { }
                  emit Box
                }

                type B = maybeExtra<:yes>
                B(2).boost()
                """));
        }

        @Test void intComparisonSelectsBranch() {
            assertEquals(99L, run("""
                macro threshold<limit> = {
                  type T(int)
                  <! if limit > 50 !> {
                    pure T.val() -> int = () { 99 }
                  } <! else !> {
                    pure T.val() -> int = () { 1 }
                  }
                  emit T
                }

                type Big = threshold<100>
                Big(0).val()
                """));
        }

        @Test void logicalAndBothBranches() {
            // flavor == :fast && bits > 16 — both must hold for true branch
            assertEquals(true, run("""
                macro choose<flavor, bits> = {
                  type T(int)
                  <! if flavor == :fast && bits > 16 !> {
                    pure T.tag() -> bool = () { true }
                  } <! else !> {
                    pure T.tag() -> bool = () { false }
                  }
                  emit T
                }

                type Both = choose<:fast, 32>
                Both(0).tag()
                """));
        }

        @Test void logicalOrShortCircuits() {
            // :fast || :blue is satisfied by :fast alone
            assertEquals(true, run("""
                macro choose<flavor> = {
                  type T(int)
                  <! if flavor == :fast || flavor == :blue !> {
                    pure T.tag() -> bool = () { true }
                  } <! else !> {
                    pure T.tag() -> bool = () { false }
                  }
                  emit T
                }

                type Fast = choose<:fast>
                Fast(0).tag()
                """));
        }

        @Test void nestedConditionals() {
            // Outer picks :fast branch, inner picks :tagged sub-option
            assertEquals(7L, run("""
                macro build<flavor, variant> = {
                  type T(int)
                  <! if flavor == :fast !> {
                    <! if variant == :tagged !> {
                      pure T.val() -> int = () { 7 }
                    } <! else !> {
                      pure T.val() -> int = () { 4 }
                    }
                  } <! else !> {
                    pure T.val() -> int = () { 0 }
                  }
                  emit T
                }

                type X = build<:fast, :tagged>
                X(0).val()
                """));
        }

        @Test void bodyPositionInsideMatchArm() {
            // Conditional gates the body of a single match arm
            assertEquals(100L, run("""
                macro pick<which> = {
                  type T(int)
                  pure T.eval() -> int = () {
                    match this.0
                    | 0 -> <! if which == :big !> { 100 } <! else !> { 10 }
                    | _ -> -1
                  }
                  emit T
                }

                type X = pick<:big>
                X(0).eval()
                """));
        }

        @Test void missingElseIsParseError() {
            assertThrows(SpnParseException.class, () -> run("""
                macro bad<> = {
                  type T(int)
                  <! if true !> {
                    pure T.a() -> int = () { 1 }
                  }
                  emit T
                }

                type Oops = bad<>
                """));
        }

        @Test void unresolvableIdentifierIsError() {
            // `unknownVar` isn't a macro param, isn't a literal — error
            assertThrows(SpnParseException.class, () -> run("""
                macro bad<> = {
                  type T(int)
                  <! if unknownVar == :x !> {
                    pure T.a() -> int = () { 1 }
                  } <! else !> { }
                  emit T
                }

                type Oops = bad<>
                """));
        }
    }

    // ── Qualified dispatch keys (stage 1) ─────────────────────────────────
    //
    // `@name` is a globally-unique dispatch slot. A type implements it via
    // `pure Type.@name(...) -> Ret = body`. The call site reads
    // `obj.@name(args)`. Stage 1 uses unqualified names only (no dots);
    // namespacing and imports come later.

    @Nested
    class QualifiedDispatchKeys {

        @Test void implementAndInvokeSimpleKey() {
            assertEquals(42L, run("""
                type Box(int)

                pure Box.@describe() -> int = () { this.0 + 40 }

                let b = Box(2)
                b.@describe()
                """));
        }

        @Test void sameKeyOnDifferentTypes() {
            // Both types implement @serialize; dispatch picks the right one per receiver
            assertEquals(true, run("""
                type A(int)
                type B(int)

                pure A.@tag() -> string = () { "a" }
                pure B.@tag() -> string = () { "b" }

                let aTag = A(1).@tag()
                let bTag = B(2).@tag()
                aTag == "a" && bTag == "b"
                """));
        }

        @Test void multiArgQualifiedKey() {
            assertEquals(7L, run("""
                type Box(int)

                pure Box.@add(int) -> int = (n) { this.0 + n }

                Box(3).@add(4)
                """));
        }

        @Test void missingImplErrors() {
            // Calling @describe on a type that doesn't implement it
            assertThrows(SpnParseException.class, () -> run("""
                type Box(int)
                let b = Box(1)
                b.@describe()
                """));
        }

        @Test void dottedFqnKey() {
            // Dotted FQNs tokenize and dispatch identically to unqualified keys
            assertEquals(42L, run("""
                type Box(int)

                pure Box.@com.myapp.describe() -> int = () { this.0 }

                Box(42).@com.myapp.describe()
                """));
        }

        @Test void registerInOwnNamespace() {
            // module declares com.myapp; registering @com.myapp.* is allowed
            assertEquals(42L, run("""
                module com.myapp

                register pure @com.myapp.describe() -> int

                type Box(int)
                pure Box.@com.myapp.describe() -> int = () { this.0 }

                Box(42).@com.myapp.describe()
                """));
        }

        @Test void registerUnderOwnedSubNamespace() {
            // module com.myapp may register deeper namespaces
            assertEquals(7L, run("""
                module com.myapp

                register pure @com.myapp.util.extract() -> int

                type Box(int)
                pure Box.@com.myapp.util.extract() -> int = () { this.0 }

                Box(7).@com.myapp.util.extract()
                """));
        }

        @Test void registerForeignNamespaceRejected() {
            // module com.alice cannot register @com.bob.foo
            assertThrows(SpnParseException.class, () -> run("""
                module com.alice

                register pure @com.bob.foo() -> int
                """));
        }

        @Test void registerBroaderNamespaceRejected() {
            // module com.alice.util cannot register @com.alice.foo (broader)
            assertThrows(SpnParseException.class, () -> run("""
                module com.alice.util

                register pure @com.alice.foo() -> int
                """));
        }

        @Test void registerWithoutModuleDeclRejected() {
            // A file without `module ...` can't register any qualified FQN
            assertThrows(SpnParseException.class, () -> run("""
                register pure @com.anything.foo() -> int
                """));
        }

        @Test void duplicateRegisterRejected() {
            assertThrows(SpnParseException.class, () -> run("""
                module com.myapp

                register pure @com.myapp.foo() -> int
                register pure @com.myapp.foo() -> int
                """));
        }
    }

    // ── Signatures + requires (stages 4-5) ────────────────────────────────
    //
    // `signature Name (keys)` declares a named set of required dispatch keys.
    // `macro foo(T requires Name)` checks at invocation that T has impls
    // for every key the signature lists. Missing keys are named in the error.

    @Nested
    class Signatures {

        @Test void declareAndComposeSignatures() {
            // Parsing test: multi-level composition should not error
            run("""
                signature Additive (@+, @-)
                signature Multiplicative (@*, @/)
                signature Ring (Additive, Multiplicative)
                42
                """);
        }

        @Test void unknownSubSignatureErrors() {
            assertThrows(SpnParseException.class, () -> run("""
                signature Ring (Nonexistent)
                """));
        }

        @Test void macroRequiresSucceedsWhenSatisfied() {
            // Box has @+ defined → satisfies Additive
            assertEquals(6L, run("""
                type Box(int)
                pure +(Box, Box) -> Box = (a, b) { Box(a.0 + b.0) }

                signature Additive (@+)

                macro deriveDouble<T requires Additive> = {
                  pure T.doubled() -> T = () { this + this }
                }
                deriveDouble<Box>

                Box(3).doubled().0
                """));
        }

        @Test void macroRequiresFailsWhenMissing() {
            // Naked has no @+ → Additive not satisfied
            assertThrows(SpnParseException.class, () -> run("""
                type Naked(int)

                signature Additive (@+)

                macro deriveDouble<T requires Additive> = {
                  pure T.doubled() -> T = () { this + this }
                }
                deriveDouble<Naked>
                """));
        }

        @Test void missingKeysListedInError() {
            // When multiple keys are missing, the error should name them
            SpnParseException ex = assertThrows(SpnParseException.class, () -> run("""
                type Empty(int)

                signature FullSet (@+, @-, @*)

                macro needs<T requires FullSet> = {
                  pure T.noop() -> T = () { this }
                }
                needs<Empty>
                """));
            String msg = ex.getMessage();
            assertTrue(msg.contains("@+"), "error should mention @+: " + msg);
            assertTrue(msg.contains("@-"), "error should mention @-: " + msg);
            assertTrue(msg.contains("@*"), "error should mention @*: " + msg);
        }

        @Test void methodKeyInSignature() {
            // Signature requires a qualified method, not an operator
            assertEquals("described", run("""
                module com.myapp
                register pure @com.myapp.describe() -> string

                type Box(int)
                pure Box.@com.myapp.describe() -> string = () { "described" }

                signature Describable (@com.myapp.describe)

                macro withDescribe<T requires Describable> = {
                  pure T.tag() -> string = () { this.@com.myapp.describe() }
                }
                withDescribe<Box>

                Box(7).tag()
                """));
        }

        @Test void composedSignatureCheck() {
            // Ring requires @+, @-, @*, @/ (via Additive + Multiplicative)
            // Box has @+ and @* but not @- or @/ — so Ring isn't satisfied
            assertThrows(SpnParseException.class, () -> run("""
                type Box(int)
                pure +(Box, Box) -> Box = (a, b) { Box(a.0 + b.0) }
                pure *(Box, Box) -> Box = (a, b) { Box(a.0 * b.0) }

                signature Additive (@+, @-)
                signature Multiplicative (@*, @/)
                signature Ring (Additive, Multiplicative)

                macro ringOps<T requires Ring> = {
                  pure T.thrice() -> T = () { this + this + this }
                }
                ringOps<Box>
                """));
        }

        @Test void multiSignatureRequiresSucceedsWhenAllSatisfied() {
            // Box satisfies both Additive and Stringable → passes A & S
            assertEquals(8L, run("""
                type Box(int)
                pure +(Box, Box) -> Box = (a, b) { Box(a.0 + b.0) }
                pure Box.@stringify() -> string = () { "box" }

                signature Additive (@+)
                signature Stringable (@stringify)

                macro twice<T requires Additive & Stringable> = {
                  pure T.twice() -> T = () { this + this }
                }
                twice<Box>

                Box(4).twice().0
                """));
        }

        @Test void multiSignatureFailsNamesOnlyUnsatisfied() {
            // Box satisfies Additive but not Stringable. Error should cite
            // only Stringable, not Additive.
            SpnParseException ex = assertThrows(SpnParseException.class, () -> run("""
                type Box(int)
                pure +(Box, Box) -> Box = (a, b) { Box(a.0 + b.0) }

                signature Additive (@+)
                signature Stringable (@stringify)

                macro needs<T requires Additive & Stringable> = {
                  pure T.noop() -> T = () { this }
                }
                needs<Box>
                """));
            String msg = ex.getMessage();
            assertTrue(msg.contains("Stringable"), "error should mention Stringable: " + msg);
            assertTrue(msg.contains("@stringify"), "error should mention @stringify: " + msg);
            assertTrue(!msg.contains("Additive"),
                    "error should NOT mention the satisfied signature Additive: " + msg);
        }

        @Test void multiSignatureFailsNamesBothWhenBothMissing() {
            // Naked satisfies neither → both signatures named in the error.
            SpnParseException ex = assertThrows(SpnParseException.class, () -> run("""
                type Naked(int)

                signature Additive (@+)
                signature Stringable (@stringify)

                macro needs<T requires Additive & Stringable> = {
                  pure T.noop() -> T = () { this }
                }
                needs<Naked>
                """));
            String msg = ex.getMessage();
            assertTrue(msg.contains("Additive"), "error should mention Additive: " + msg);
            assertTrue(msg.contains("Stringable"), "error should mention Stringable: " + msg);
            assertTrue(msg.contains("@+"), "error should mention @+: " + msg);
            assertTrue(msg.contains("@stringify"), "error should mention @stringify: " + msg);
        }
    }

    // ── Variadic params (T...) grammar ────────────────────────────────────
    //
    // `pure foo(int...) -> int` and `pure foo(string, int...) -> int` parse;
    // the descriptor records variadic=true with element type = last param.
    // Variadic is last-param-only and disallowed on operators and producers.

    @Nested
    class VariadicGrammar {

        @Test void variadicOnlyParamParses() {
            // Body doesn't use the param — Phase 1 is grammar-only.
            run("""
                pure foo(int...) -> int = (xs) { 0 }
                42
                """);
        }

        @Test void variadicAfterFixedParamParses() {
            run("""
                pure foo(string, int...) -> int = (s, xs) { 0 }
                42
                """);
        }

        @Test void variadicInMiddlePositionFails() {
            assertThrows(SpnParseException.class, () -> run("""
                pure foo(int..., string) -> int = (xs, s) { 0 }
                42
                """));
        }

        @Test void multipleVariadicsFail() {
            assertThrows(SpnParseException.class, () -> run("""
                pure foo(int..., string...) -> int = (xs, ys) { 0 }
                42
                """));
        }

        @Test void operatorVariadicFails() {
            // Operators are strictly unary or binary; variadic would break that.
            assertThrows(SpnParseException.class, () -> run("""
                pure +(int...) -> int = (xs) { 0 }
                42
                """));
        }

        @Test void methodVariadicParses() {
            // Receiver + variadic tail. The receiver is last-but-one, the
            // variadic tail is last — order preserved.
            run("""
                type Box(int)
                pure Box.fold(int...) -> int = (xs) { this.0 }
                42
                """);
        }

        @Test void factoryVariadicParses() {
            // Inside a factory body, raw construction is `this(...)`, not
            // the factory name itself. The variadic xs isn't exercised in
            // Phase 1 — only the signature parsing matters here.
            run("""
                type Pile(int)
                pure Pile(int...) -> Pile = (xs) { this(0) }
                42
                """);
        }

        // ── Dispatch (Phase 2) ───────────────────────────────────────────
        //
        // A variadic factory dispatches on `args.size() >= fixedArity`. The
        // tail is packed into an UntypedArray before the call; inside the
        // body the variadic param sees that array.

        @Test void variadicFactoryDispatchesWithMultipleArgs() {
            // Pile(2, 3, 5) hits `pure Pile(int...)`. Body sums the first
            // three tail elements — proves dispatch reached the variadic
            // factory AND the tail was packed as a subscriptable array.
            assertEquals(10L, run("""
                type Pile(int)
                pure Pile(int...) -> Pile = (xs) { this(xs[0] + xs[1] + xs[2]) }

                Pile(2, 3, 5).0
                """));
        }

        @Test void variadicFactoryEmptyCall() {
            // No args → empty packed array → dispatch still reaches the body.
            // Body stores a sentinel so we can distinguish a successful
            // zero-variadic dispatch from a parse failure.
            assertEquals(42L, run("""
                type Pile(int)
                pure Pile(int...) -> Pile = (xs) { this(42) }

                Pile().0
                """));
        }

        @Test void variadicFactoryReadsTailElement() {
            // Subscript the packed tail inside the body.
            assertEquals(7L, run("""
                type Pile(int)
                pure Pile(int...) -> Pile = (xs) { this(xs[1]) }

                Pile(3, 7, 11).0
                """));
        }

        @Test void fixedFactoryWinsOverVariadicAtSameArity() {
            // Both `pure Pile(int)` and `pure Pile(int...)` exist. A 1-arg
            // call must pick the fixed one — confirmed by the int being used
            // doubled rather than packed-and-length'd.
            assertEquals(10L, run("""
                type Pile(int)
                pure Pile(int) -> Pile = (n) { this(n * 2) }
                pure Pile(int...) -> Pile = (xs) { this(999) }

                Pile(5).0
                """));
        }

        @Test void variadicFactoryWrongElementTypeFails() {
            // Pile(int...) with a string in the tail should fail dispatch,
            // then fall through to "unknown type" since no fixed factory
            // matches either.
            assertThrows(SpnParseException.class, () -> run("""
                type Pile(int)
                pure Pile(int...) -> Pile = (xs) { this(0) }

                Pile(1, "oops", 3)
                """));
        }
    }

    // ── Qualified-key import shortening (stage 3) ─────────────────────────
    //
    // `import com.myapp.(serialize)` aliases `serialize` in method-call
    // position to `@com.myapp.serialize`, so callers can write
    // `obj.serialize()` instead of `obj.@com.myapp.serialize()`.

    @Nested
    class QualifiedKeyImports {

        @Test void shortNameResolvesToQualifiedKey() {
            assertEquals(42L, run("""
                module com.myapp
                register pure @com.myapp.unwrap() -> int

                type Box(int)
                pure Box.@com.myapp.unwrap() -> int = () { this.0 }

                import com.myapp.(unwrap)

                Box(42).unwrap()
                """));
        }

        @Test void multipleKeysInOneImport() {
            assertEquals(true, run("""
                module com.myapp
                register pure @com.myapp.alpha() -> int
                register pure @com.myapp.beta() -> int

                type Box(int)
                pure Box.@com.myapp.alpha() -> int = () { this.0 }
                pure Box.@com.myapp.beta() -> int = () { this.0 * 2 }

                import com.myapp.(alpha, beta)

                let b = Box(5)
                b.alpha() == 5 && b.beta() == 10
                """));
        }

        @Test void regularMethodTakesPrecedenceOverAlias() {
            // If a type has a regular .foo method AND @com.myapp.foo is imported,
            // the regular method wins — the alias is only a fallback.
            assertEquals(1L, run("""
                module com.myapp
                register pure @com.myapp.foo() -> int

                type Box(int)
                pure Box.foo() -> int = () { 1 }
                pure Box.@com.myapp.foo() -> int = () { 2 }

                import com.myapp.(foo)

                Box(0).foo()
                """));
        }

        @Test void unimportedShortNameErrors() {
            // Calling a short name with no matching method and no import = error
            assertThrows(SpnParseException.class, () -> run("""
                module com.myapp
                register pure @com.myapp.tag() -> int

                type Box(int)
                pure Box.@com.myapp.tag() -> int = () { this.0 }

                -- No `import com.myapp.(tag)` — so `tag()` is unresolved
                Box(1).tag()
                """));
        }

        @Test void importedAliasWorksAcrossTypes() {
            // Two types implementing the same key, called via short name
            assertEquals(true, run("""
                module com.myapp
                register pure @com.myapp.size() -> int

                type A(int)
                type B(int)
                pure A.@com.myapp.size() -> int = () { 1 }
                pure B.@com.myapp.size() -> int = () { 2 }

                import com.myapp.(size)

                A(0).size() == 1 && B(0).size() == 2
                """));
        }
    }

    // ── Operator arity mutex ──────────────────────────────────────────────

    @Nested
    class OperatorArityMutex {
        // Rule: for a given operator and first-parameter type, you may define
        // EITHER a unary OR a binary overload — not both. The alternative form
        // is expressed as a .neg()/.inv() method, and the parser falls back to
        // that method when the unary operator overload is absent.

        @Test
        void unaryThenBinaryOnSameTypeIsRejected() {
            assertThrows(SpnParseException.class, () -> run("""
                type Box(int)
                pure -(Box) -> int = (b) { -(b.0) }
                pure -(Box, Box) -> int = (a, b) { a.0 - b.0 }
                0
                """));
        }

        @Test
        void binaryThenUnaryOnSameTypeIsRejected() {
            assertThrows(SpnParseException.class, () -> run("""
                type Box(int)
                pure /(Box, Box) -> int = (a, b) { a.0 / b.0 }
                pure /(Box) -> int = (b) { b.0 }
                0
                """));
        }

        @Test
        void binaryOnlyIsAllowed() {
            // Defining just the binary form is fine — no conflict.
            assertEquals(2L, run("""
                type Box(int)
                pure -(Box, Box) -> int = (a, b) { a.0 - b.0 }
                let a = Box(5)
                let b = Box(3)
                a - b
                """));
        }

        @Test
        void unaryOnlyIsAllowed() {
            // Defining just the unary form is fine too.
            assertEquals(-7L, run("""
                type Box(int)
                pure -(Box) -> int = (b) { -(b.0) }
                let w = Box(7)
                -w
                """));
        }

        @Test
        void negMethodFallbackWhenUnaryOpAbsent() {
            // With the binary form defined and a .neg() method instead of the
            // unary operator, `-box` should resolve to box.neg() at parse time.
            assertEquals(-5L, run("""
                type Box(int)
                pure -(Box, Box) -> Box = (a, b) { Box(a.0 - b.0) }
                pure Box.neg() -> Box = () { Box(-(this.0)) }
                let w = Box(5)
                (-w).0
                """));
        }

        @Test
        void differentTypesDoNotConflict() {
            // Unary -(Box) and binary -(Crate, Crate) are on different types,
            // so no conflict. (The existing stdlib/traction code relies on this.)
            assertEquals(-9L, run("""
                type Box(int)
                type Crate(int)
                pure -(Box) -> int = (b) { -(b.0) }
                pure -(Crate, Crate) -> int = (a, b) { a.0 - b.0 }
                let w = Box(9)
                -w
                """));
        }
    }

    // ── Subject-less guard match ───────────────────────────────────────────

    @Nested
    class GuardMatch {
        // Syntax: `match | cond -> expr | cond -> expr | _ -> default`
        // First true guard wins. The `| _ -> ...` arm is required for totality.

        @Test
        void twoArmBoolean() {
            assertEquals("positive", run("""
                let x = 3
                match
                  | x > 0 -> "positive"
                  | _ -> "nonpositive"
                """));
        }

        @Test
        void chainOfGuards() {
            assertEquals("mid", run("""
                let x = 5
                match
                  | x < 0 -> "neg"
                  | x < 3 -> "low"
                  | x < 10 -> "mid"
                  | _ -> "high"
                """));
        }

        @Test
        void firstMatchingGuardWins() {
            // Both x < 10 and x < 100 are true for x=5; the first one should win.
            assertEquals("under10", run("""
                let x = 5
                match
                  | x < 10 -> "under10"
                  | x < 100 -> "under100"
                  | _ -> "huge"
                """));
        }

        @Test
        void falsesAllFallToWildcard() {
            assertEquals("default", run("""
                let x = 5
                match
                  | x < 0 -> "neg"
                  | x > 100 -> "big"
                  | _ -> "default"
                """));
        }

        @Test
        void missingWildcardRejected() {
            assertThrows(SpnParseException.class, () -> run("""
                let x = 5
                match
                  | x < 0 -> "neg"
                  | x > 100 -> "big"
                """));
        }

        @Test
        void guardCanUseBlock() {
            assertEquals(42L, run("""
                let x = 3
                match
                  | x > 0 -> {
                      let y = 14
                      y * x
                    }
                  | _ -> 0
                """));
        }
    }

    // ── Named pattern destructuring ────────────────────────────────────────

    @Nested
    class NamedPatternDestructuring {
        // Grammar: Type(field = pattern, field = pattern, ...)
        // Fields may appear in any order, unspecified fields default to Wildcard,
        // and duplicate field names are an error. Positional form still works.

        @Test
        void namedFieldBinding() {
            // Rational(num=n, denom=d) → binds n, d to the named fields
            assertEquals(3L, run("""
                type Rational(num: int, denom: int)
                let r = Rational(3, 4)
                match r
                  | Rational(num = n, denom = d) -> n
                """));
        }

        @Test
        void namedFieldOrderDoesNotMatter() {
            // Denom first, num second — still binds correctly.
            assertEquals(7L, run("""
                type Rational(num: int, denom: int)
                let r = Rational(3, 4)
                match r
                  | Rational(denom = d, num = n) -> n + d
                """));
        }

        @Test
        void unspecifiedFieldsDefaultToWildcard() {
            // Only bind num; denom is unspecified → wildcard (still matches).
            assertEquals(3L, run("""
                type Rational(num: int, denom: int)
                let r = Rational(3, 4)
                match r
                  | Rational(num = n) -> n
                """));
        }

        @Test
        void namedFieldWithNestedPattern() {
            // Mix named-top with a literal pattern inside.
            assertEquals("zero", run("""
                type Rational(num: int, denom: int)
                let r = Rational(0, 7)
                match r
                  | Rational(num = 0) -> "zero"
                  | _ -> "nonzero"
                """));
        }

        @Test
        void unknownFieldNameRejected() {
            assertThrows(SpnParseException.class, () -> run("""
                type Rational(num: int, denom: int)
                let r = Rational(3, 4)
                match r
                  | Rational(numerator = n) -> n
                """));
        }

        @Test
        void duplicateFieldNameRejected() {
            assertThrows(SpnParseException.class, () -> run("""
                type Rational(num: int, denom: int)
                let r = Rational(3, 4)
                match r
                  | Rational(num = a, num = b) -> a
                """));
        }

        @Test
        void positionalStillWorks() {
            // Regression: after adding named, positional must still parse.
            assertEquals(3L, run("""
                type Rational(num: int, denom: int)
                let r = Rational(3, 4)
                match r
                  | Rational(n, d) -> n
                """));
        }

        @Test
        void namedCapturesGetFieldType() {
            // Binding `s` to `TComplex.scale` should pick up the Rational type,
            // so method dispatch on s resolves at parse time.
            assertEquals(true, run("""
                type Rational(n: int, d: int)
                pure Rational.isZero() -> bool = () { this.0 == 0 }
                type TComplex(scale: Rational, tangent: Rational)
                let z = TComplex(Rational(0, 1), Rational(1, 1))
                match z
                  | TComplex(scale = s) -> s.isZero()
                """));
        }
    }

    // ── Angle-bracket macro syntax ─────────────────────────────────────────
    //
    // New macro declaration + invocation form: `macro Name<P> = {...}` at
    // definition, `Name<Arg>` at call sites. Same-args invocations memoize
    // to the same emitted type (file-scoped singleton identity).

    @Nested
    class AngleBracketMacros {

        @Test void angleBracketDeclAndTypeAliasInvocation() {
            // Declaration uses <T>; invocation uses <T>; emitted type gets
            // aliased under the user-chosen name and its methods are
            // reachable through the alias.
            assertEquals(42L, run("""
                macro Pair<T> = {
                  type P
                  pure P(T, T) -> P = (a, b) {
                    let this.a = a
                    let this.b = b
                    this(a, b)
                  }
                  pure P.sum() -> T = () { this.a + this.b }
                  emit P
                }

                type IntPair = Pair<int>
                IntPair(20, 22).sum()
                """));
        }

        @Test void sameArgsReuseMemoizedSingleton() {
            // Two aliases bound to the same macro+args must refer to the
            // same underlying type. Constructing through one alias and
            // reading through the other must typecheck and return the value.
            assertEquals(7L, run("""
                macro Box<T> = {
                  type B
                  pure B(T) -> B = (v) {
                    let this.v = v
                    this(v)
                  }
                  pure B.get() -> T = () { this.v }
                  emit B
                }

                type IntBoxA = Box<int>
                type IntBoxB = Box<int>
                let b = IntBoxA(7)
                -- B's get() is resolvable even though b was built through A,
                -- because the singleton means IntBoxA ≡ IntBoxB.
                b.get()
                """));
        }

    }
}
