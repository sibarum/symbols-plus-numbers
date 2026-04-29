package spn.coefexp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CoefExpPair} — the Cayley-Dickson pair built on
 * {@link CoefExp} scalars. Verifies:
 * <ul>
 *   <li>Each of the four mode factories sets its δ.</li>
 *   <li>The unit element {@code (0, 1)} squared reproduces δ in the top
 *       and zero in the bottom — for all four modes — with the mode
 *       visible in the structural form of the result top.</li>
 *   <li>{@code (1, 0)} is a two-sided identity in the bilinear product.</li>
 *   <li>Mixed {@code (a + bk)·(c + dk)} arithmetic produces the expected
 *       cross-term behavior in each mode.</li>
 *   <li>Conjugation negates the bottom.</li>
 *   <li>Mismatched δ in {@code composeBilinear} throws.</li>
 * </ul>
 */
class CoefExpPairTest {

    private static CoefExp ce(long c, long e) { return new CoefExp(c, e); }
    private static CoefExp s(long c) { return new CoefExp(c, 0); } // finite scalar

    // ── Mode factories set δ ────────────────────────────────────────

    @Test
    void hyperbolicFactorySetsDelta() {
        assertEquals(ce(1, 0), CoefExpPair.hyperbolic(s(0), s(1)).delta());
    }

    @Test
    void parabolicFactorySetsDelta() {
        assertEquals(ce(1, 1), CoefExpPair.parabolic(s(0), s(1)).delta());
    }

    @Test
    void ellipticFactorySetsDelta() {
        assertEquals(ce(-1, 0), CoefExpPair.elliptic(s(0), s(1)).delta());
    }

    @Test
    void tractionFactorySetsDelta() {
        assertEquals(ce(1, -1), CoefExpPair.traction(s(0), s(1)).delta());
    }

    // ── Defining identities: unit² = δ at the top ───────────────────

    @Test
    void hyperbolicUnitSquaresToOne() {
        // (0, 1)² in j-mode = (1, 0): newTop = δ = +1.
        CoefExpPair unit = CoefExpPair.hyperbolic(s(0), s(1));
        CoefExpPair expected = CoefExpPair.hyperbolic(ce(1, 0), s(0));
        assertEquals(expected, unit.composeBilinear(unit));
    }

    @Test
    void parabolicUnitSquaresToZero() {
        // (0, 1)² in ε-mode = (δ, 0) where δ = (1, 1) — algebraically 0,
        // but structurally tagged at exp 1 (parabolic mode visible).
        CoefExpPair unit = CoefExpPair.parabolic(s(0), s(1));
        CoefExpPair sq = unit.composeBilinear(unit);
        assertEquals(ce(1, 1), sq.top());
        assertTrue(sq.top().isZero());
        assertEquals(s(0), sq.bottom());
    }

    @Test
    void ellipticUnitSquaresToMinusOne() {
        // (0, 1)² in η-mode = (-1, 0).
        CoefExpPair unit = CoefExpPair.elliptic(s(0), s(1));
        CoefExpPair expected = CoefExpPair.elliptic(ce(-1, 0), s(0));
        assertEquals(expected, unit.composeBilinear(unit));
    }

    @Test
    void tractionUnitSquaresToOmega() {
        // (0, 1)² in k-mode = (ω, 0) — top is (1, -1) = ω explicitly.
        // The ω-flavor is structurally visible at exp -1, no fraction
        // canonicalization needed.
        CoefExpPair unit = CoefExpPair.traction(s(0), s(1));
        CoefExpPair expected = CoefExpPair.traction(CoefExp.OMEGA, s(0));
        assertEquals(expected, unit.composeBilinear(unit));
    }

    // ── (1, 0) is two-sided bilinear identity (in any mode) ─────────

    @Test
    void scalarOneIsLeftAndRightIdentity() {
        for (CoefExp delta : new CoefExp[]{
                CoefExpPair.DELTA_HYPERBOLIC,
                CoefExpPair.DELTA_PARABOLIC,
                CoefExpPair.DELTA_ELLIPTIC,
                CoefExpPair.DELTA_TRACTION,
        }) {
            CoefExpPair x = new CoefExpPair(s(3), s(4), delta);
            CoefExpPair one = new CoefExpPair(s(1), s(0), delta);
            assertEquals(x, one.composeBilinear(x), "left identity at δ=" + delta);
            assertEquals(x, x.composeBilinear(one), "right identity at δ=" + delta);
        }
    }

    // ── Mixed (a + bk)·(c + dk) — cross term materializes per mode ──

    @Test
    void mixedProductHyperbolic() {
        // (2, 3)·(5, 7), j: newTop = 2·5 + 1·7·3 = 10 + 21 = 31; bottom = 7·2 + 3·5 = 29.
        CoefExpPair result = CoefExpPair.hyperbolic(s(2), s(3))
                .composeBilinear(CoefExpPair.hyperbolic(s(5), s(7)));
        assertEquals(s(31), result.top());
        assertEquals(s(29), result.bottom());
    }

    @Test
    void mixedProductElliptic() {
        // η: newTop = 10 + (-1)·21 = -11; bottom = 29.
        CoefExpPair result = CoefExpPair.elliptic(s(2), s(3))
                .composeBilinear(CoefExpPair.elliptic(s(5), s(7)));
        assertEquals(s(-11), result.top());
        assertEquals(s(29), result.bottom());
    }

    @Test
    void mixedProductParabolic() {
        // ε: newTop = (10, 0) + (1,1)·(7,0)·(3,0) = (10, 0) + (21, 1).
        // Lower exp wins (0 < 1), so result top = (10, 0). The order-1
        // zero from δ swallows the cross term, leaving just a·c.
        CoefExpPair result = CoefExpPair.parabolic(s(2), s(3))
                .composeBilinear(CoefExpPair.parabolic(s(5), s(7)));
        assertEquals(s(10), result.top());
        assertEquals(s(29), result.bottom());
    }

    @Test
    void mixedProductTraction() {
        // k: newTop = (10, 0) + (1,-1)·(7,0)·(3,0) = (10, 0) + (21, -1).
        // Lower exp wins (-1 < 0), so the ω-flavored cross term
        // absorbs the regular a·c term — result top = (21, -1) = 21·ω.
        // Same absorption seen in the fractional encoding, but here the
        // ω-flavor is visible structurally rather than as a 0 denominator.
        CoefExpPair result = CoefExpPair.traction(s(2), s(3))
                .composeBilinear(CoefExpPair.traction(s(5), s(7)));
        assertEquals(ce(21, -1), result.top());
        assertEquals(s(29), result.bottom());
    }

    // ── Conjugate / negate ──────────────────────────────────────────

    @Test
    void conjugateNegatesBottomKeepsTop() {
        CoefExpPair x = CoefExpPair.elliptic(s(3), s(4));
        assertEquals(CoefExpPair.elliptic(s(3), s(-4)), x.conjugate());
    }

    @Test
    void negateNegatesBoth() {
        CoefExpPair x = CoefExpPair.traction(s(3), s(-4));
        assertEquals(CoefExpPair.traction(s(-3), s(4)), x.negate());
    }

    // ── Mode mismatch is a hard error ───────────────────────────────

    @Test
    void mismatchedDeltaInComposeThrows() {
        CoefExpPair j = CoefExpPair.hyperbolic(s(0), s(1));
        CoefExpPair eta = CoefExpPair.elliptic(s(0), s(1));
        assertThrows(IllegalArgumentException.class, () -> j.composeBilinear(eta));
    }

    @Test
    void mismatchedDeltaInAddThrows() {
        CoefExpPair j = CoefExpPair.hyperbolic(s(0), s(1));
        CoefExpPair k = CoefExpPair.traction(s(0), s(1));
        assertThrows(IllegalArgumentException.class, () -> j.add(k));
    }
}
