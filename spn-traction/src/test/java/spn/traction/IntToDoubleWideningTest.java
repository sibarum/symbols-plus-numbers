package spn.traction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that long (int) values implicitly widen to double (float) when
 * constructing a struct whose field is declared as float. Mirrors the same
 * widening that function arguments already get in SpnFunctionRootNode.
 */
class IntToDoubleWideningTest extends TractionTestBase {

    @Test
    void longArithmeticFillsFloatField() {
        // Struct with a float field, constructed from long arithmetic —
        // the `6 / 2` returns a Long, which widens to 3.0 for the Double field.
        Object result = run("""
                type Pair(a: float, b: float)
                let p = Pair(6 / 2, 7 - 4)
                p.a + p.b
                """);
        assertEquals(6.0, result);
    }

    @Test
    void userDefinedPromotionAppliesAtStructConstruction() {
        // `promote int -> Rational` is declared in rational.spn. Passing a raw
        // int literal to a Rational-typed field should apply the promotion at
        // parse time, mirroring how function-arg promotion already works.
        Object result = run("""
                import numerics.rational
                type RatPair(a: Rational, b: Rational)
                let p = RatPair(3, 4)
                p.a == Rational(3, 1) && p.b == Rational(4, 1)
                """);
        assertEquals(true, result);
    }
}
