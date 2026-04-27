package spn.clifford;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CliffordElement}: the formal top/bottom pair carrying
 * the four field-of-fractions rules. Tests cover the rules over integer
 * leaves, recursion (Element-of-Element), structural equality, and the
 * wheel/projective handling of ω = (1, 0) as data without canonicalization.
 *
 * <p>Note: these tests pin down the *current* component-wise multiplication
 * semantics. The Cayley-Dickson bilinear rule and non-commutative div
 * ordering are open design questions (not yet implemented) and therefore
 * not asserted here.
 */
class CliffordElementTest {

    private static CliffordInteger i(long n) { return new CliffordInteger(n); }
    private static CliffordElement e(long top, long bot) {
        return new CliffordElement(i(top), i(bot));
    }

    // ── Construction & accessors ──────────────────────────────────────

    @Test
    void constructionPreservesTopAndBottom() {
        CliffordInteger top = i(3);
        CliffordInteger bot = i(4);
        CliffordElement el = new CliffordElement(top, bot);
        assertSame(top, el.top());
        assertSame(bot, el.bottom());
    }

    // ── The four field-of-fractions rules ─────────────────────────────

    @Test
    void multiplyComponentWise() {
        // (3/4) · (5/7) = (15/28)
        assertEquals(e(15, 28), e(3, 4).mult(e(5, 7)));
    }

    @Test
    void divideCrossesComponents() {
        // (3/4) / (5/7) = (3·7) / (4·5) = (21/20)
        assertEquals(e(21, 20), e(3, 4).div(e(5, 7)));
    }

    @Test
    void addUsesCommonDenominator() {
        // (1/2) + (1/3) = (1·3 + 1·2) / (2·3) = (5/6)
        assertEquals(e(5, 6), e(1, 2).add(e(1, 3)));
    }

    @Test
    void subtractUsesCommonDenominator() {
        // (1/2) - (1/3) = (1·3 - 1·2) / (2·3) = (1/6)
        assertEquals(e(1, 6), e(1, 2).sub(e(1, 3)));
    }

    // ── Recursion: Element-of-Element ─────────────────────────────────

    @Test
    void elementOfElementRecurses() {
        // ((1/2) / (3/4))  ·  ((5/6) / (7/8))
        // top   = (1/2) · (5/6) = (5/12)
        // bottom = (3/4) · (7/8) = (21/32)
        // Whole = ((5/12), (21/32))
        CliffordElement a = new CliffordElement(e(1, 2), e(3, 4));
        CliffordElement b = new CliffordElement(e(5, 6), e(7, 8));
        CliffordElement product = (CliffordElement) a.mult(b);
        assertEquals(e(5, 12),  product.top());
        assertEquals(e(21, 32), product.bottom());
    }

    @Test
    void recursionPreservesStructuralEquality() {
        CliffordElement a = new CliffordElement(e(1, 2), e(3, 4));
        CliffordElement b = new CliffordElement(e(1, 2), e(3, 4));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ── Equality is structural, not value-equivalence ────────────────

    @Test
    void differentRepresentationsOfSameRationalNotEqual() {
        // (1/2) and (2/4) are mathematically equal as rationals but the
        // substrate keeps representations distinct (per remove-normalization
        // stance). Tests pin this down so future canonicalization is a
        // deliberate change.
        assertNotEquals(e(1, 2), e(2, 4));
    }

    @Test
    void omegaIsNotCanonicallyOne() {
        // ω = (1, 0) is its own structural value, not equal to any
        // representation of 1.
        CliffordElement omega = e(1, 0);
        assertNotEquals(omega, e(1, 1));
        assertNotEquals(omega, new CliffordElement(CliffordInteger.ONE, CliffordInteger.ONE));
    }

    // ── ω = (1, 0) flows as data ─────────────────────────────────────

    @Test
    void constructOmegaDoesNotThrow() {
        CliffordElement omega = e(1, 0);
        assertEquals(i(1), omega.top());
        assertEquals(i(0), omega.bottom());
    }

    @Test
    void multiplyWithOmegaPropagates() {
        // ω · (3/4) = (1·3) / (0·4) = (3/0) — ω-flavored data, no throw.
        CliffordNumber result = e(1, 0).mult(e(3, 4));
        assertEquals(e(3, 0), result);
    }

    @Test
    void omegaPlusOmegaIsOmegaSquaredOverZero() {
        // ω + ω = (1·0 + 1·0) / (0·0) = (0/0). This pins down current
        // substrate output; the (0,0) value stays as raw data here, since
        // 0/0 = 1 is enforced only by integer-leaf div, not by add.
        CliffordNumber result = e(1, 0).add(e(1, 0));
        assertEquals(e(0, 0), result);
    }

    // ── Universal CliffordNumber ops: negate, isZero ──────────────────

    @Test
    void negateFlipsTopOnly() {
        assertEquals(e(-3, 4), e(3, 4).negate());
    }

    @Test
    void negateTwiceIsIdentity() {
        CliffordElement x = e(3, 4);
        assertEquals(x, x.negate().negate());
    }

    @Test
    void isZeroOnlyWhenTopZeroAndBottomNonzero() {
        assertTrue(e(0, 5).isZero());        // 0/5 = 0
        assertFalse(e(3, 4).isZero());       // 3/4 ≠ 0
        assertFalse(e(0, 0).isZero());       // (0,0) is the 0/0 = 1 case
        assertFalse(e(1, 0).isZero());       // ω ≠ 0
    }
}
