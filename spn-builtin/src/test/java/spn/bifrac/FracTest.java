package spn.bifrac;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Frac} — top/bottom integer fraction with the special
 * arithmetic rules needed for the bifrac encoding:
 * <ul>
 *   <li><b>Add</b> uses a same-bottom shortcut so {@code ω + ω = (2, 0)}
 *       and {@code (0,-2) + (0,-2) = (0,-2)} (denominator preserved).
 *       Different-bottom add is plain cross-multiplication.</li>
 *   <li><b>Mult</b> uses cross-cancellation so {@code (1,1)} stays an
 *       identity on non-reduced inputs while integer multipliers cancel
 *       with denominators that share factors:
 *       {@code (2,1) · (0,-2) = (0,-1)}, {@code (2,1) · (1,2) = (1,1)}.</li>
 * </ul>
 * Construction never reduces — {@code (0,-2)} stays {@code (0,-2)} until
 * an arithmetic operation chooses to cancel.
 */
class FracTest {

    private static Frac f(long t, long b) { return new Frac(t, b); }

    // ── Constants ───────────────────────────────────────────────────

    @Test
    void constantsHaveExpectedShape() {
        assertEquals(f(0, 1), Frac.ZERO);
        assertEquals(f(1, 1), Frac.ONE);
        assertEquals(f(-1, 1), Frac.NEGATIVE_ONE);
        assertEquals(f(1, 0), Frac.OMEGA);
    }

    // ── Add: same-bottom shortcut, no GCD reduction ─────────────────

    @Test
    void addSameBottomKeepsBottom() {
        // Same-bottom shortcut: tops add, bottom passes through. No
        // cross-multiply, no reduction.
        assertEquals(f(5, 3), f(2, 3).add(f(3, 3)));
    }

    @Test
    void omegaPlusOmegaIsTwoOmega() {
        // (1, 0) + (1, 0) = (2, 0). The shortcut is what saves this from
        // collapsing to (0, 0) under plain cross-multiplication.
        assertEquals(f(2, 0), Frac.OMEGA.add(Frac.OMEGA));
    }

    @Test
    void negativeZeroPlusNegativeZeroPreservesDenominator() {
        // (0, -2) + (0, -2) = (0, -2). Both numerator AND denominator
        // are preserved; no GCD reduction in add.
        assertEquals(f(0, -2), f(0, -2).add(f(0, -2)));
    }

    @Test
    void addDifferentBottomsCrossMultiplies() {
        // (1, 2) + (1, 3) = (3 + 2, 6) = (5, 6). No reduction.
        assertEquals(f(5, 6), f(1, 2).add(f(1, 3)));
    }

    @Test
    void addingZeroPreservesStructureAcrossBottoms() {
        // (0, 1) + (5, 1) — same bottom, shortcut: (5, 1).
        assertEquals(f(5, 1), Frac.ZERO.add(f(5, 1)));
    }

    @Test
    void addingNegativeZeroAcrossDifferentBottomsPerturbs() {
        // (0, -2) + (5, 1) — different bottoms, cross-multiply:
        // (0·1 + 5·-2, -2·1) = (-10, -2). Algebraically still 5,
        // structurally not (5, 1).
        assertEquals(f(-10, -2), f(0, -2).add(f(5, 1)));
    }

    // ── Mult: cross-cancellation ────────────────────────────────────

    @Test
    void multCoprimeOperandsLeaveStandardProduct() {
        // (3, 4) · (5, 7) — gcds across operands are 1; standard product.
        assertEquals(f(15, 28), f(3, 4).mult(f(5, 7)));
    }

    @Test
    void multByOneIsIdentityEvenOnNonReducedInputs() {
        // (1, 1) · (0, -2) — cross-cancel sees gcd(1,-2)=1 and gcd(0,1)=1;
        // nothing cancels, result preserves the (0, -2) shape. This is
        // why we can't use plain "GCD-reduce after standard mult" — that
        // would collapse (0, -2) to (0, -1).
        assertEquals(f(0, -2), Frac.ONE.mult(f(0, -2)));
        assertEquals(f(7, 3), Frac.ONE.mult(f(7, 3)));
    }

    @Test
    void multByIntegerCancelsViaDenominatorGcd() {
        // (2, 1) · (0, -2) — gcd(2, -2) = 2 cancels, giving
        // (1, 1) · (0, -1) = (0, -1). The user's hand-derivation
        // 2·(0/-2) = (0/-1) — implemented via cross-cancellation.
        assertEquals(f(0, -1), f(2, 1).mult(f(0, -2)));
    }

    @Test
    void multByIntegerCollapsesHalfFraction() {
        // (2, 1) · (1, 2) — gcd(2, 2) = 2 cancels: (1, 1) · (1, 1) = (1, 1).
        assertEquals(Frac.ONE, f(2, 1).mult(f(1, 2)));
    }

    @Test
    void multByIntegerOnNegativeHalfFraction() {
        // (2, 1) · (-1, 2) — gcd(2, 2) = 2 cancels: (1, 1) · (-1, 1) = (-1, 1).
        assertEquals(Frac.NEGATIVE_ONE, f(2, 1).mult(f(-1, 2)));
    }

    @Test
    void omegaTimesOmegaStaysOmegaShape() {
        // (1, 0) · (1, 0) — both gcds skipped (zeros present). Standard
        // product: (1·1, 0·0) = (1, 0). ω·ω = ω here because 0·0 = 0
        // at the bottom — the structural collapse comes from plain
        // multiplication, not from cancellation.
        assertEquals(Frac.OMEGA, Frac.OMEGA.mult(Frac.OMEGA));
    }

    @Test
    void twoTimesOmegaIsTwoOmega() {
        // (2, 1) · (1, 0) — gcd(2, 0) is skipped (zero present), so the
        // 2 doesn't get absorbed into ω's zero denominator. Result
        // (2·1, 1·0) = (2, 0). So 2·ω = 2ω via mult, matching ω + ω = 2ω
        // via add. Add and mult agree on this case.
        assertEquals(f(2, 0), f(2, 1).mult(Frac.OMEGA));
    }

    @Test
    void negativeZeroTimesNegativeZeroPreservesDenominator() {
        // (0, -2) · (0, -2) — both gcds skipped (zeros present).
        // Standard product: (0·0, -2·-2) = (0, 4). The denominator's
        // multiplicity is preserved instead of collapsing.
        assertEquals(f(0, 4), f(0, -2).mult(f(0, -2)));
    }

    @Test
    void multCommutes() {
        assertEquals(f(2, 1).mult(f(0, -2)), f(0, -2).mult(f(2, 1)));
        assertEquals(f(3, 5).mult(f(7, 11)), f(7, 11).mult(f(3, 5)));
    }

    // ── Negate / sub ────────────────────────────────────────────────

    @Test
    void negateFlipsTop() {
        assertEquals(f(-3, 4), f(3, 4).negate());
        assertEquals(f(0, -2), f(0, -2).negate()); // -0 stays "-0"
    }

    @Test
    void subOfSameBottomShortCircuits() {
        // Sub is add of negate: (5, 3) + (-3, 3) — same bottom, shortcut.
        assertEquals(f(2, 3), f(5, 3).sub(f(3, 3)));
    }

    // ── Predicates ──────────────────────────────────────────────────

    @Test
    void isZeroForNumeratorZeroAndNonzeroDenom() {
        assertTrue(Frac.ZERO.isZero());
        assertTrue(f(0, 5).isZero());
        assertTrue(f(0, -2).isZero());
    }

    @Test
    void isZeroFalseForOmegaAndIndeterminate() {
        assertFalse(Frac.OMEGA.isZero());
        assertFalse(f(0, 0).isZero()); // 0/0 is indeterminate, not zero
    }

    @Test
    void isOmegaForNonzeroNumeratorAndZeroDenom() {
        assertTrue(Frac.OMEGA.isOmega());
        assertTrue(f(5, 0).isOmega());
        assertTrue(f(-1, 0).isOmega());
    }

    @Test
    void isIndeterminateOnlyForZeroOverZero() {
        assertTrue(f(0, 0).isIndeterminate());
        assertFalse(Frac.ZERO.isIndeterminate());
        assertFalse(Frac.OMEGA.isIndeterminate());
        assertFalse(Frac.ONE.isIndeterminate());
    }

    // ── Structural inequality of algebraic equals ───────────────────

    @Test
    void differentStructuralZerosAreUnequal() {
        // No canonicalization on construction: (0, -2), (0, 5), (0, 1)
        // are all algebraically zero but each is its own value.
        assertFalse(Frac.ZERO.equals(f(0, -2)));
        assertFalse(Frac.ZERO.equals(f(0, 5)));
        assertFalse(f(0, -2).equals(f(0, -1)));
    }
}
