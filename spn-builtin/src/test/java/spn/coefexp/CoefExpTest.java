package spn.coefexp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CoefExp} — the {@code coef · 0^exp} scalar substrate.
 * Covers basic arithmetic, the addition rule (lower-exp dominates,
 * algebraic-zero short-circuits), {@code isZero} on the multiple
 * structural representations of zero, and the four δ constants.
 */
class CoefExpTest {

    private static CoefExp ce(long c, long e) { return new CoefExp(c, e); }

    // ── Constants ───────────────────────────────────────────────────

    @Test
    void zeroOneOmegaConstants() {
        assertEquals(ce(0, 0), CoefExp.ZERO);
        assertEquals(ce(1, 0), CoefExp.ONE);
        assertEquals(ce(-1, 0), CoefExp.NEGATIVE_ONE);
        assertEquals(ce(1, -1), CoefExp.OMEGA);
    }

    // ── Multiplication: exponents add ───────────────────────────────

    @Test
    void scalarTimesScalarStaysAtExpZero() {
        assertEquals(ce(6, 0), ce(2, 0).mult(ce(3, 0)));
    }

    @Test
    void multiplicationAddsExponents() {
        // (3 · 0^1) · (2 · 0^2) = 6 · 0^3
        assertEquals(ce(6, 3), ce(3, 1).mult(ce(2, 2)));
    }

    @Test
    void omegaTimesOmegaIsOmegaSquared() {
        // ω · ω = (1, -1) · (1, -1) = (1, -2)
        assertEquals(ce(1, -2), CoefExp.OMEGA.mult(CoefExp.OMEGA));
    }

    @Test
    void omegaTimesZeroOrderOneIsExpMinus() {
        // ω · 0  = (1, -1) · (1, 1) = (1, 0) — algebraically 1.
        // Encodes the "ω·0 = 1" intuition (zero of order 1 cancels ω
        // of order 1) automatically via exp arithmetic.
        assertEquals(CoefExp.ONE, CoefExp.OMEGA.mult(ce(1, 1)));
    }

    @Test
    void multIdentity() {
        assertEquals(ce(7, 3), ce(7, 3).mult(CoefExp.ONE));
        assertEquals(ce(7, 3), CoefExp.ONE.mult(ce(7, 3)));
    }

    @Test
    void multByZeroCoefIsZeroCoef() {
        // Coef goes through normal multiplication; exp adds.
        assertEquals(ce(0, 5), ce(0, 2).mult(ce(7, 3)));
    }

    // ── Addition: lower exp wins; same exp adds; zero short-circuits ─

    @Test
    void sameExponentAdds() {
        assertEquals(ce(8, 0), ce(5, 0).add(ce(3, 0)));
        assertEquals(ce(0, 0), ce(5, 0).add(ce(-5, 0)));
        assertEquals(ce(8, 1), ce(5, 1).add(ce(3, 1)));
    }

    @Test
    void lowerExpDominatesWhenBothNonzero() {
        // (5, 0) + (3, 1) — the order-1 zero is swallowed by the
        // regular scalar; result is the lower-exp term.
        assertEquals(ce(5, 0), ce(5, 0).add(ce(3, 1)));
        assertEquals(ce(5, 0), ce(3, 1).add(ce(5, 0)));
    }

    @Test
    void omegaAbsorbsRegularScalar() {
        // (5, 0) + ω = ω. Mirrors the wheel-projective rule that any
        // finite addition to ω stays at ω.
        assertEquals(CoefExp.OMEGA, ce(5, 0).add(CoefExp.OMEGA));
    }

    @Test
    void zeroCoefShortCircuits() {
        // (0, n) is algebraic zero regardless of n; it must drop out
        // of addition rather than win on lower exp.
        assertEquals(ce(5, 0), ce(0, 5).add(ce(5, 0)));
        assertEquals(ce(5, 0), ce(5, 0).add(ce(0, 5)));
        // Without the short-circuit, (0, -3) + (5, 0) would return
        // (0, -3) under "lower exp wins" — wrong, since 0 + 5 = 5.
        assertEquals(ce(5, 0), ce(0, -3).add(ce(5, 0)));
    }

    // ── Negation / subtraction ──────────────────────────────────────

    @Test
    void negateFlipsCoef() {
        assertEquals(ce(-3, 2), ce(3, 2).negate());
        assertEquals(ce(0, 0), ce(0, 0).negate());
    }

    @Test
    void subtractionIsAddOfNegated() {
        assertEquals(ce(2, 0), ce(5, 0).sub(ce(3, 0)));
        assertEquals(CoefExp.OMEGA.negate(), ce(5, 0).sub(CoefExp.OMEGA).negate().negate());
    }

    @Test
    void omegaMinusOmegaIsZeroCoefAtMinusOne() {
        // (1, -1) - (1, -1) = (1, -1) + (-1, -1) = (0, -1).
        // Algebraically zero (coef == 0), structurally tagged at exp -1
        // — pinning down the encoding's behavior at the ω - ω boundary.
        CoefExp result = CoefExp.OMEGA.sub(CoefExp.OMEGA);
        assertEquals(ce(0, -1), result);
        assertTrue(result.isZero());
    }

    // ── isZero: covers all structural reps of zero ──────────────────

    @Test
    void isZeroForCoefZero() {
        assertTrue(ce(0, 0).isZero());
        assertTrue(ce(0, 5).isZero());
        assertTrue(ce(0, -3).isZero());
    }

    @Test
    void isZeroForPositiveExp() {
        // 5 · 0^1 = 0. The parabolic δ (1, 1) is one such case.
        assertTrue(ce(5, 1).isZero());
        assertTrue(ce(-7, 4).isZero());
        assertTrue(CoefExpPair.DELTA_PARABOLIC.isZero());
    }

    @Test
    void isZeroFalseForFiniteScalar() {
        assertFalse(ce(1, 0).isZero());
        assertFalse(ce(-3, 0).isZero());
    }

    @Test
    void isZeroFalseForOmega() {
        assertFalse(CoefExp.OMEGA.isZero());
        assertFalse(ce(-2, -1).isZero());
        assertFalse(ce(7, -5).isZero());
    }
}
