package spn.clifford;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Level-1 tests for {@link CliffordParabolicPair} (δ = 0). The defining
 * identity is {@code ε² = 0} — the parabolic / dual-number algebra in
 * which the new generator squares to zero (translation / shear primitive).
 */
class CliffordParabolicPairTest {

    private static CliffordInteger i(long n) { return new CliffordInteger(n); }
    private static CliffordParabolicPair p(long t, long b) {
        return new CliffordParabolicPair(i(t), i(b));
    }

    // ── Defining identity: ε² = 0 ────────────────────────────────────

    @Test
    void unitElementSquaresToZero() {
        // ε = (0, 1).  ε² = (0·0, 1·0 + 1·0) = (0, 0).
        CliffordParabolicPair epsilon = p(0, 1);
        assertEquals(p(0, 0), epsilon.composeBilinear(epsilon));
    }

    @Test
    void deltaIsZero() {
        assertEquals(CliffordInteger.ZERO, p(0, 0).delta());
    }

    @Test
    void implementsAllExpectedCapabilities() {
        CliffordParabolicPair x = p(1, 2);
        assertTrue(x instanceof Bilinear);
        assertTrue(x instanceof Conjugatable);
        assertTrue(x instanceof Invertible);
        assertTrue(x instanceof Symmetric);
        assertTrue(x instanceof Antisymmetric);
    }

    // ── Dual-number arithmetic: (a + bε)·(c + dε) = ac + (ad + bc)ε ──

    @Test
    void dualNumberMultiplication() {
        // (3 + 4ε)·(5 + 7ε) = 15 + (3·7 + 4·5)ε = 15 + 41ε
        assertEquals(p(15, 41), p(3, 4).composeBilinear(p(5, 7)));
    }

    @Test
    void dualNumberSquared() {
        // (3 + 4ε)² = 9 + 24ε  (the ε² = 0 term drops)
        assertEquals(p(9, 24), p(3, 4).composeBilinear(p(3, 4)));
    }

    // ── Identities ────────────────────────────────────────────────────

    @Test
    void scalarOneIsBilinearIdentity() {
        CliffordParabolicPair x = p(3, 4);
        assertEquals(x, p(1, 0).composeBilinear(x));
        assertEquals(x, x.composeBilinear(p(1, 0)));
    }

    @Test
    void conjugateNegatesBottom() {
        assertEquals(p(3, -4), p(3, 4).conjugate());
    }

    // ── Universal ops sanity ─────────────────────────────────────────

    @Test
    void addComponentWise() {
        assertEquals(p(4, 6), p(1, 2).add(p(3, 4)));
    }

    @Test
    void negateBothComponents() {
        assertEquals(p(-3, 4), p(3, -4).negate());
    }
}
