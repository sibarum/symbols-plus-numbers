package spn.traction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StringTemplateTest extends TractionTestBase {

    @Test void plainStringUnchanged() {
        assertEquals("hello world", run("\"hello world\""));
    }

    @Test void singleInterpolation() {
        assertEquals("value = 42", run("""
            let x = 42
            "value = ${x}"
            """));
    }

    @Test void multipleInterpolations() {
        assertEquals("a=1 b=2 c=3", run("""
            let a = 1
            let b = 2
            let c = 3
            "a=${a} b=${b} c=${c}"
            """));
    }

    @Test void interpolationWithExpression() {
        assertEquals("2 + 3 = 5", run("\"2 + 3 = ${2 + 3}\""));
    }

    @Test void interpolationWithFloat() {
        assertEquals("pi ≈ 3.14", run("\"pi ≈ ${3.14}\""));
    }

    @Test void interpolationWithBoolean() {
        assertEquals("ok=true", run("\"ok=${true}\""));
    }

    @Test void escapedDollarIsLiteral() {
        assertEquals("price=$5", run("""
            let n = 5
            "price=\\$${n}"
            """));
    }

    @Test void emptyLiteralBetweenInterpolations() {
        assertEquals("abc", run("""
            let x = "a"
            let y = "b"
            let z = "c"
            "${x}${y}${z}"
            """));
    }

    @Test void trailingLiteral() {
        assertEquals("got 7!", run("\"got ${3 + 4}!\""));
    }
}
