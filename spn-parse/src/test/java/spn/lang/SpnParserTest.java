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
            assertEquals(7L, run("""
                pure add(_,_) = (a, b) { a + b }
                add(3, 4)
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
                type Color = Symbol where oneOf([:red, :green, :blue])
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
}
