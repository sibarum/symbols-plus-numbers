package spn.clifford;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Level-1 tests for {@link CliffordTractionPair} (δ = ω). The defining
 * identity is {@code k² = ω} — the user's novel traction algebra in which
 * the new generator squares to the wheel-projective {@code 1/0}
 * (inversion-dilation primitive).
 *
 * <p>Note: the bilinear product mixes integer and rational types via the
 * {@code multLeaves(ω)} step, so result components are CliffordElements
 * carrying ω-flavored data — distinct from the integer-only level-1
 * results in elliptic / parabolic / hyperbolic cases.
 */
class CliffordTractionPairTest {

    private static CliffordInteger i(long n) { return new CliffordInteger(n); }
    private static CliffordTractionPair p(long t, long b) {
        return new CliffordTractionPair(i(t), i(b));
    }

    /** ω = (1, 0) as a CliffordElement (the form produced by multLeaves(ω) at integer leaves). */
    private static CliffordElement omegaTimes(long n) {
        return new CliffordElement(i(n), i(0));
    }

    // ── Defining identity: k² = ω ───────────────────────────────────

    @Test
    void unitElementSquaresToOmega() {
        // k = (0, 1). k² = (0·0 + ω·1·1, 1·0 + 1·0) = (ω, 0).
        // Top is the ω-flavored CliffordElement(1, 0); bottom is integer 0.
        CliffordTractionPair k = p(0, 1);
        CliffordTractionPair expected = new CliffordTractionPair(omegaTimes(1), i(0));
        assertEquals(expected, k.composeBilinear(k));
    }

    @Test
    void deltaIsOmega() {
        assertEquals(
                new CliffordProjectiveRational(CliffordInteger.ONE, CliffordInteger.ZERO),
                p(0, 0).delta());
    }

    @Test
    void implementsAllExpectedCapabilities() {
        CliffordTractionPair x = p(1, 2);
        assertTrue(x instanceof Bilinear);
        assertTrue(x instanceof Conjugatable);
        assertTrue(x instanceof Invertible);
        assertTrue(x instanceof Symmetric);
        assertTrue(x instanceof Antisymmetric);
    }

    // ── Scalar arithmetic (no ω-mixing) ─────────────────────────────

    @Test
    void scalarMultiplicationStaysInteger() {
        // (a, 0)·(c, 0) = (ac, 0) — the ω term is multiplied by 0.
        // No type promotion needed; pure integer level-1 result.
        assertEquals(p(15, 0), p(3, 0).composeBilinear(p(5, 0)));
    }

    @Test
    void scalarOneIsBilinearIdentity() {
        // (1, 0)·(a, b): the ω·d̄·b term contributes ω·b·0 = 0; no mixing.
        // Result equals the right operand exactly.
        CliffordTractionPair x = p(3, 4);
        assertEquals(x, p(1, 0).composeBilinear(x));
        assertEquals(x, x.composeBilinear(p(1, 0)));
    }

    // ── Traction multiplication: (a + bk)·(c + dk) = (ac + ω·bd) + (ad + bc)k ──

    @Test
    void tractionGeneratorTimesScalar() {
        // (0, 1)·(3, 0) = (0·3 + ω·0·1, 0·0 + 1·3̄) = (0, 3).
        // The ω term vanishes because d = 0 on the right side.
        assertEquals(p(0, 3), p(0, 1).composeBilinear(p(3, 0)));
    }

    @Test
    void scalarTimesTractionGenerator() {
        // (3, 0)·(0, 1) = (3·0 + ω·1·0, 1·3 + 0·0̄) = (0, 3).
        assertEquals(p(0, 3), p(3, 0).composeBilinear(p(0, 1)));
    }

    @Test
    void productMixingScalarAndTractionPart() {
        // (2, 3)·(5, 7) = (2·5 + ω·7·3, 7·2 + 3·5) = (10 + 21ω, 29)
        // newTop is 10 + 21ω as CliffordElement; newBottom is integer 29.
        CliffordNumber result = p(2, 3).composeBilinear(p(5, 7));
        var pair = (CliffordTractionPair) result;
        // The bottom is straightforward integer arithmetic.
        assertEquals(i(29), pair.bottom());
        // The top is "10 + 21·ω", which the substrate represents as a
        // CliffordElement obtained by adding integer 10 to ω-flavored
        // CliffordElement(21, 0). That add lifts via the cross-leaf
        // promotion to fraction (10/1) + (21/0) = (10·0 + 21·1)/(1·0)
        //                                       = (21, 0) under wheel rules.
        // Verify the top is structurally what the substrate produces.
        assertEquals(new CliffordElement(i(21), i(0)), pair.top());
    }

    // ── Conjugate / universal ops ───────────────────────────────────

    @Test
    void conjugateNegatesBottom() {
        assertEquals(p(3, -4), p(3, 4).conjugate());
    }

    @Test
    void addComponentWise() {
        assertEquals(p(4, 6), p(1, 2).add(p(3, 4)));
    }

    @Test
    void negateBothComponents() {
        assertEquals(p(-3, 4), p(3, -4).negate());
    }
}
