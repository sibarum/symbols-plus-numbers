package spn.clifford;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CliffordEllipticPair} — the first concrete
 * {@link Bilinear} implementer with δ = −1. Verifies:
 *
 * <ul>
 *   <li>Universal CliffordNumber ops (component-wise add/sub/negate/mult,
 *       fraction-style div, isZero).</li>
 *   <li>The Cayley-Dickson bilinear product, including the load-bearing
 *       identities: {@code (0,1)² = (−1, 0)}, {@code x · x̄ = (|x|², 0)},
 *       complex multiplication at level 1.</li>
 *   <li>Conjugation, inverse, and the round-trip
 *       {@code x.composeBilinear(x.inverse()) = (1, 0)}.</li>
 *   <li>Symmetric and antisymmetric decomposition, including the
 *       commutativity at level 1 (antisymmetric = 0) and the consistency
 *       identity {@code symmetric + antisymmetric = composeBilinear}.</li>
 * </ul>
 */
class CliffordEllipticPairTest {

    private static CliffordInteger i(long n) { return new CliffordInteger(n); }
    private static CliffordEllipticPair p(long t, long b) {
        return new CliffordEllipticPair(i(t), i(b));
    }

    // ── Construction & accessors ──────────────────────────────────────

    @Test
    void constructionPreservesComponents() {
        CliffordEllipticPair x = p(3, 4);
        assertEquals(i(3), x.top());
        assertEquals(i(4), x.bottom());
    }

    @Test
    void deltaIsMinusOne() {
        assertEquals(i(-1), p(0, 0).delta());
    }

    @Test
    void implementsAllExpectedCapabilities() {
        CliffordEllipticPair x = p(1, 2);
        assertTrue(x instanceof Bilinear);
        assertTrue(x instanceof Conjugatable);
        assertTrue(x instanceof Invertible);
        assertTrue(x instanceof Symmetric);
        assertTrue(x instanceof Antisymmetric);
    }

    // ── Universal CliffordNumber ops ─────────────────────────────────

    @Test
    void addComponentWise() {
        // (1, 2) + (3, 4) = (4, 6)
        assertEquals(p(4, 6), p(1, 2).add(p(3, 4)));
    }

    @Test
    void subComponentWise() {
        assertEquals(p(-2, -2), p(1, 2).sub(p(3, 4)));
    }

    @Test
    void multIsStructuralComponentWise() {
        // (1, 2) · (3, 4) = (3, 8) — NOT bilinear; this is structural.
        assertEquals(p(3, 8), p(1, 2).mult(p(3, 4)));
    }

    @Test
    void divIsStructuralFractionStyle() {
        // (1, 2) / (3, 4) = (1·4, 2·3) = (4, 6) — fraction-style, like CliffordElement.
        assertEquals(p(4, 6), p(1, 2).div(p(3, 4)));
    }

    @Test
    void negateBothComponents() {
        assertEquals(p(-3, 4), p(3, -4).negate());
    }

    @Test
    void isZeroOnlyWhenBothComponentsZero() {
        assertTrue(p(0, 0).isZero());
        assertFalse(p(1, 0).isZero());
        assertFalse(p(0, 1).isZero());
        assertFalse(p(3, 4).isZero());
    }

    // ── Cayley-Dickson bilinear product ──────────────────────────────

    @Test
    void unitElementSquaresToDelta() {
        // (0, 1)·(0, 1) = (-1, 0). The load-bearing identity that names
        // this class as the elliptic case (δ = -1).
        CliffordEllipticPair unit = p(0, 1);
        assertEquals(p(-1, 0), unit.composeBilinear(unit));
    }

    @Test
    void scalarOneSquaresToScalarOne() {
        // (1, 0)·(1, 0) = (1, 0)
        assertEquals(p(1, 0), p(1, 0).composeBilinear(p(1, 0)));
    }

    @Test
    void scalarOneIsBilinearIdentity() {
        // (1, 0)·(a, b) = (a, b) and (a, b)·(1, 0) = (a, b)
        CliffordEllipticPair x = p(3, 4);
        assertEquals(x, p(1, 0).composeBilinear(x));
        assertEquals(x, x.composeBilinear(p(1, 0)));
    }

    @Test
    void complexMultiplicationAtLevel1() {
        // (3 + 4i)(3 + 4i) = 9 + 24i - 16 = -7 + 24i  →  pair (-7, 24)
        assertEquals(p(-7, 24), p(3, 4).composeBilinear(p(3, 4)));
    }

    @Test
    void xTimesConjugateIsNormSquaredOnTop() {
        // (3, 4)·(3, -4) = (9 + 16, 0) = (25, 0)
        CliffordEllipticPair x = p(3, 4);
        CliffordEllipticPair xConj = (CliffordEllipticPair) x.conjugate();
        assertEquals(p(25, 0), x.composeBilinear(xConj));
    }

    @Test
    void bilinearIsCommutativeAtLevel1OverReals() {
        // Level-1 pairs over real scalars form ℂ, which is commutative.
        CliffordEllipticPair a = p(1, 2);
        CliffordEllipticPair b = p(3, 5);
        assertEquals(a.composeBilinear(b), b.composeBilinear(a));
    }

    @Test
    void composeBilinearWithNonPairThrows() {
        // Scalars must be embedded as pairs explicitly.
        assertThrows(CliffordIncompatibleArithmeticException.class,
                () -> p(1, 2).composeBilinear(i(7)));
    }

    // ── Conjugation ──────────────────────────────────────────────────

    @Test
    void conjugateNegatesBottom() {
        assertEquals(p(3, -4), p(3, 4).conjugate());
        assertEquals(p(3, 4), p(3, -4).conjugate());
    }

    @Test
    void conjugateOfRealIsSelf() {
        // (a, 0) is real-valued; conjugate is itself.
        assertEquals(p(7, 0), p(7, 0).conjugate());
    }

    @Test
    void conjugateIsInvolution() {
        CliffordEllipticPair x = p(3, 4);
        assertEquals(x, ((Conjugatable) x.conjugate()).conjugate());
    }

    // ── Inverse ──────────────────────────────────────────────────────

    @Test
    void inverseOfNonzeroPair() {
        // (3, 4)⁻¹ = (3, -4) / 25 = (3/25, -4/25)
        CliffordEllipticPair inv = (CliffordEllipticPair) p(3, 4).inverse();
        assertEquals(new CliffordProjectiveRational(i(3),  i(25)), inv.top());
        assertEquals(new CliffordProjectiveRational(i(-4), i(25)), inv.bottom());
    }

    @Test
    void inverseOfUnitScalarIsItself() {
        // (1, 0)⁻¹ = (1, 0) (norm² = 1)
        assertEquals(p(1, 0), p(1, 0).inverse());
    }

    @Test
    void inverseOfUnitElement() {
        // (0, 1)⁻¹ = (0, -1) (norm² = 1; conjugate = (0, -1))
        assertEquals(p(0, -1), p(0, 1).inverse());
    }

    @Test
    void unitElementInverseTimesUnitEqualsIdentity() {
        // (0, 1) · (0, 1)⁻¹ = (0, 1) · (0, -1) = (1, 0) under composeBilinear.
        CliffordEllipticPair unit = p(0, 1);
        CliffordEllipticPair inv = (CliffordEllipticPair) unit.inverse();
        assertEquals(p(1, 0), unit.composeBilinear(inv));
    }

    // ── Symmetric / Antisymmetric ───────────────────────────────────

    @Test
    void antisymmetricIsZeroAtLevel1OverReals() {
        // Level-1 over reals is commutative, so a∧b = 0 always.
        CliffordEllipticPair a = p(3, 4);
        CliffordEllipticPair b = p(5, 7);
        assertEquals(p(0, 0), a.antisymmetric(b));
    }

    @Test
    void symmetricEqualsBilinearAtLevel1OverReals() {
        // Level-1 over reals: ½(ab + ba) = ab (since ab = ba).
        CliffordEllipticPair a = p(3, 4);
        CliffordEllipticPair b = p(5, 7);
        assertEquals(a.composeBilinear(b), a.symmetric(b));
    }

    @Test
    void symmetricPlusAntisymmetricEqualsBilinear() {
        // The fundamental identity: ab = ½(ab + ba) + ½(ab − ba).
        CliffordEllipticPair a = p(2, 3);
        CliffordEllipticPair b = p(5, 7);
        CliffordNumber bilinear = a.composeBilinear(b);
        CliffordNumber sum = a.symmetric(b).add(a.antisymmetric(b));
        assertEquals(bilinear, sum);
    }

    // ── Equality & display ──────────────────────────────────────────

    @Test
    void equalsAndHashCodeStructural() {
        assertEquals(p(3, 4), p(3, 4));
        assertEquals(p(3, 4).hashCode(), p(3, 4).hashCode());
    }

    @Test
    void notEqualToOtherCliffordTypes() {
        // Same data shape as CliffordElement, different class identity.
        CliffordElement element = new CliffordElement(i(3), i(4));
        CliffordEllipticPair pair = p(3, 4);
        assertFalse(pair.equals(element));
        assertFalse(element.equals(pair));
    }

    @Test
    void toStringShowsPairForm() {
        assertTrue(p(3, 4).toString().contains("3"));
        assertTrue(p(3, 4).toString().contains("4"));
    }

    // ── Cross-leaf div behavior used by inverse ─────────────────────

    @Test
    void integerDivPreservesNonExactAsRational() {
        // Sanity: the new CliffordInteger.div semantics that inverse()
        // depends on. 3 / 4 keeps as (3, 4) instead of truncating to 0.
        CliffordNumber result = i(3).div(i(4));
        var r = assertInstanceOf(CliffordProjectiveRational.class, result);
        assertEquals(i(3), r.top());
        assertEquals(i(4), r.bottom());
    }
}
