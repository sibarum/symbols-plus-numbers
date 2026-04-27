package spn.clifford;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Level-1 tests for {@link CliffordHyperbolicPair} (δ = +1). The defining
 * identity is {@code j² = +1} — the split-complex / hyperbolic algebra in
 * which the new generator squares to +1 (Lorentz boost / scale primitive).
 */
class CliffordHyperbolicPairTest {

    private static CliffordInteger i(long n) { return new CliffordInteger(n); }
    private static CliffordHyperbolicPair p(long t, long b) {
        return new CliffordHyperbolicPair(i(t), i(b));
    }

    // ── Defining identity: j² = +1 ──────────────────────────────────

    @Test
    void unitElementSquaresToOne() {
        // j = (0, 1).  j² = (0·0 + 1·1, 1·0 + 1·0) = (1, 0).
        CliffordHyperbolicPair j = p(0, 1);
        assertEquals(p(1, 0), j.composeBilinear(j));
    }

    @Test
    void deltaIsOne() {
        assertEquals(CliffordInteger.ONE, p(0, 0).delta());
    }

    @Test
    void implementsAllExpectedCapabilities() {
        CliffordHyperbolicPair x = p(1, 2);
        assertTrue(x instanceof Bilinear);
        assertTrue(x instanceof Conjugatable);
        assertTrue(x instanceof Invertible);
        assertTrue(x instanceof Symmetric);
        assertTrue(x instanceof Antisymmetric);
    }

    // ── Split-complex arithmetic: (a + bj)·(c + dj) = (ac+bd) + (ad+bc)j ──

    @Test
    void splitComplexMultiplication() {
        // (3 + 4j)·(5 + 7j) = (3·5 + 4·7) + (3·7 + 4·5)j = 43 + 41j
        assertEquals(p(43, 41), p(3, 4).composeBilinear(p(5, 7)));
    }

    @Test
    void splitComplexSquared() {
        // (3 + 4j)² = (9 + 16) + 24j = 25 + 24j
        assertEquals(p(25, 24), p(3, 4).composeBilinear(p(3, 4)));
    }

    // ── Identities ────────────────────────────────────────────────────

    @Test
    void scalarOneIsBilinearIdentity() {
        CliffordHyperbolicPair x = p(3, 4);
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
