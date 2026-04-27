package spn.clifford;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CliffordInteger}: the scalar leaf of the Clifford /
 * recursive-GA tower. Pins down the field-of-fractions presentation
 * (top = self, bottom = ONE), the four arithmetic ops on integer pairs,
 * the wheel/projective behavior of division by zero (n/0 → (n, 0); 0/0 = 1),
 * and exception paths for cross-leaf arithmetic.
 */
class CliffordIntegerTest {

    @Test
    void constantsExist() {
        assertEquals(0L, ((CliffordInteger) CliffordInteger.ZERO).value());
        assertEquals(1L, ((CliffordInteger) CliffordInteger.ONE).value());
    }

    @Test
    void constructionAndAccessor() {
        CliffordInteger x = new CliffordInteger(42);
        assertEquals(42L, x.value());
    }

    @Test
    void topIsSelf() {
        CliffordInteger x = new CliffordInteger(7);
        assertSame(x, x.top());
    }

    @Test
    void bottomIsOne() {
        CliffordInteger x = new CliffordInteger(7);
        assertEquals(CliffordInteger.ONE, x.bottom());
    }

    @Test
    void equalsByValue() {
        assertEquals(new CliffordInteger(5), new CliffordInteger(5));
        assertNotEquals(new CliffordInteger(5), new CliffordInteger(6));
    }

    @Test
    void hashCodeAgreesWithEquals() {
        assertEquals(new CliffordInteger(5).hashCode(), new CliffordInteger(5).hashCode());
    }

    @Test
    void notEqualToOtherCliffordTypes() {
        assertNotEquals(new CliffordInteger(1),
                new CliffordElement(new CliffordInteger(1), CliffordInteger.ONE));
    }

    @Test
    void multiplyTwoIntegers() {
        assertEquals(new CliffordInteger(12),
                new CliffordInteger(3).mult(new CliffordInteger(4)));
    }

    @Test
    void multiplyByZero() {
        assertEquals(CliffordInteger.ZERO,
                new CliffordInteger(7).mult(CliffordInteger.ZERO));
    }

    @Test
    void addTwoIntegers() {
        assertEquals(new CliffordInteger(10),
                new CliffordInteger(3).add(new CliffordInteger(7)));
    }

    @Test
    void subtractTwoIntegers() {
        assertEquals(new CliffordInteger(-4),
                new CliffordInteger(3).sub(new CliffordInteger(7)));
    }

    @Test
    void divideTwoIntegers() {
        assertEquals(new CliffordInteger(3),
                new CliffordInteger(12).div(new CliffordInteger(4)));
    }

    @Test
    void divideByZeroPromotesToProjectiveRational() {
        // n / 0 with n ≠ 0  →  (n, 0); ω-valued data, no throw, no info loss.
        CliffordNumber result = new CliffordInteger(7).div(CliffordInteger.ZERO);
        var rat = assertInstanceOf(CliffordProjectiveRational.class, result);
        assertEquals(new CliffordInteger(7), rat.top());
        assertEquals(CliffordInteger.ZERO, rat.bottom());
    }

    @Test
    void zeroOverZeroIsOne() {
        // Traction: 0/0 = 1 (no indeterminate forms in
        // information-conservative arithmetic).
        assertEquals(CliffordInteger.ONE, CliffordInteger.ZERO.div(CliffordInteger.ZERO));
    }

    @Test
    void multiplyWithIncompatibleTypeThrows() {
        // Pair leaves carry generator-flavor; multiplying a scalar by a
        // bilinear pair is genuinely ambiguous (which level? which slot?)
        // and throws. Scalar × FractionalElement is allowed (see below).
        CliffordEllipticPair pair = new CliffordEllipticPair(CliffordInteger.ONE, CliffordInteger.ZERO);
        assertThrows(CliffordIncompatibleArithmeticException.class,
                () -> new CliffordInteger(1).mult(pair));
    }

    @Test
    void addWithIncompatibleTypeThrows() {
        CliffordEllipticPair pair = new CliffordEllipticPair(CliffordInteger.ONE, CliffordInteger.ZERO);
        assertThrows(CliffordIncompatibleArithmeticException.class,
                () -> new CliffordInteger(1).add(pair));
    }

    @Test
    void subWithIncompatibleTypeThrows() {
        CliffordEllipticPair pair = new CliffordEllipticPair(CliffordInteger.ONE, CliffordInteger.ZERO);
        assertThrows(CliffordIncompatibleArithmeticException.class,
                () -> new CliffordInteger(1).sub(pair));
    }

    @Test
    void divWithIncompatibleTypeThrows() {
        CliffordEllipticPair pair = new CliffordEllipticPair(CliffordInteger.ONE, CliffordInteger.ZERO);
        assertThrows(CliffordIncompatibleArithmeticException.class,
                () -> new CliffordInteger(1).div(pair));
    }

    // ── Scalar × FractionalElement (newly supported) ──────────────────

    @Test
    void multiplyByRationalLiftsToFraction() {
        // 7 · (3/4) = (21/4). Scalar promoted via the (this, 1) fractional view.
        CliffordProjectiveRational threeFourths = new CliffordProjectiveRational(
                new CliffordInteger(3), new CliffordInteger(4));
        CliffordNumber result = new CliffordInteger(7).mult(threeFourths);
        var r = assertInstanceOf(CliffordElement.class, result);
        assertEquals(new CliffordInteger(21), r.top());
        assertEquals(new CliffordInteger(4), r.bottom());
    }

    @Test
    void addRationalLiftsToFraction() {
        // 7 + (3/4) = (7·4 + 3)/4 = (31/4)
        CliffordProjectiveRational threeFourths = new CliffordProjectiveRational(
                new CliffordInteger(3), new CliffordInteger(4));
        CliffordNumber result = new CliffordInteger(7).add(threeFourths);
        var r = assertInstanceOf(CliffordElement.class, result);
        assertEquals(new CliffordInteger(31), r.top());
        assertEquals(new CliffordInteger(4), r.bottom());
    }

    @Test
    void multiplyWithNullThrows() {
        assertThrows(CliffordIncompatibleArithmeticException.class,
                () -> new CliffordInteger(1).mult(null));
    }

    // ── Universal CliffordNumber ops: negate, isZero ──────────────────

    @Test
    void negateFlipsSign() {
        assertEquals(new CliffordInteger(-7), new CliffordInteger(7).negate());
        assertEquals(new CliffordInteger(7), new CliffordInteger(-7).negate());
    }

    @Test
    void negateZeroIsZero() {
        assertEquals(CliffordInteger.ZERO, CliffordInteger.ZERO.negate());
    }

    @Test
    void isZeroOnlyForZero() {
        assertTrue(CliffordInteger.ZERO.isZero());
        assertFalse(CliffordInteger.ONE.isZero());
        assertFalse(new CliffordInteger(-1).isZero());
        assertFalse(new CliffordInteger(42).isZero());
    }

    // ── Capability: Conjugatable ──────────────────────────────────────

    @Test
    void implementsConjugatable() {
        assertTrue(new CliffordInteger(7) instanceof Conjugatable);
    }

    @Test
    void conjugateOfRealIsItself() {
        CliffordInteger x = new CliffordInteger(7);
        assertEquals(x, x.conjugate());
    }

    // ── Capability: Invertible ────────────────────────────────────────

    @Test
    void implementsInvertible() {
        assertTrue(new CliffordInteger(7) instanceof Invertible);
    }

    @Test
    void inverseOfNonzeroIsExactRational() {
        CliffordNumber inv = new CliffordInteger(7).inverse();
        var r = assertInstanceOf(CliffordProjectiveRational.class, inv);
        assertEquals(CliffordInteger.ONE, r.top());
        assertEquals(new CliffordInteger(7), r.bottom());
    }

    @Test
    void inverseOfZeroIsOmega() {
        // Per wheel-projective + traction: 0⁻¹ = ω = (1, 0).
        CliffordNumber inv = CliffordInteger.ZERO.inverse();
        var r = assertInstanceOf(CliffordProjectiveRational.class, inv);
        assertEquals(CliffordInteger.ONE, r.top());
        assertEquals(CliffordInteger.ZERO, r.bottom());
    }

    // ── Capability: Symmetric ─────────────────────────────────────────

    @Test
    void implementsSymmetric() {
        assertTrue(new CliffordInteger(7) instanceof Symmetric);
    }

    @Test
    void symmetricOfTwoIntegersIsTheirProduct() {
        // ½(ab + ba) for commuting scalars = ab.
        assertEquals(new CliffordInteger(12),
                new CliffordInteger(3).symmetric(new CliffordInteger(4)));
    }

    // ── Capability: Antisymmetric ─────────────────────────────────────

    @Test
    void implementsAntisymmetric() {
        assertTrue(new CliffordInteger(7) instanceof Antisymmetric);
    }

    @Test
    void antisymmetricOfTwoIntegersIsZero() {
        // ½(ab − ba) for commuting scalars = 0.
        assertEquals(CliffordInteger.ZERO,
                new CliffordInteger(3).antisymmetric(new CliffordInteger(4)));
    }

    @Test
    void antisymmetricWithIncompatibleTypeThrows() {
        CliffordElement composite = new CliffordElement(CliffordInteger.ONE, CliffordInteger.ONE);
        assertThrows(CliffordIncompatibleArithmeticException.class,
                () -> new CliffordInteger(1).antisymmetric(composite));
    }
}
