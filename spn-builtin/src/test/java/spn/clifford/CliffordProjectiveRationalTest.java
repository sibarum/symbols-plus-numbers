package spn.clifford;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests for {@link CliffordProjectiveRational}: the named refinement of
 * {@link CliffordElement} with both top and bottom constrained to
 * {@link CliffordInteger}. Verifies the constructor signature, ω = (1, 0)
 * construction, and that arithmetic inherited from CliffordElement still
 * produces correct values (even if the result loses the "ProjectiveRational"
 * type tag, since CliffordElement.mult builds a plain CliffordElement).
 */
class CliffordProjectiveRationalTest {

    private static CliffordInteger i(long n) { return new CliffordInteger(n); }

    @Test
    void constructFromIntegers() {
        CliffordProjectiveRational r = new CliffordProjectiveRational(i(3), i(4));
        assertEquals(i(3), r.top());
        assertEquals(i(4), r.bottom());
    }

    @Test
    void omegaConstructsFromIntegers() {
        // ω = 1/0 — explicitly representable, no throw.
        CliffordProjectiveRational omega = new CliffordProjectiveRational(i(1), i(0));
        assertEquals(i(1), omega.top());
        assertEquals(i(0), omega.bottom());
    }

    @Test
    void wheelBottomConstructsFromIntegers() {
        // (0, 0) is constructible as raw data; substrate doesn't canonicalize.
        CliffordProjectiveRational bottom = new CliffordProjectiveRational(i(0), i(0));
        assertEquals(i(0), bottom.top());
        assertEquals(i(0), bottom.bottom());
    }

    @Test
    void inheritedMultiplyProducesCorrectRationalValue() {
        // (3/4) · (5/7) = (15/28). The result is a CliffordElement, not
        // necessarily tagged as a ProjectiveRational — its value is what
        // we check.
        CliffordProjectiveRational a = new CliffordProjectiveRational(i(3), i(4));
        CliffordProjectiveRational b = new CliffordProjectiveRational(i(5), i(7));
        CliffordNumber product = a.mult(b);
        var prod = assertInstanceOf(CliffordElement.class, product);
        assertEquals(i(15), prod.top());
        assertEquals(i(28), prod.bottom());
    }

    @Test
    void equalsAndHashCodeStructural() {
        CliffordProjectiveRational a = new CliffordProjectiveRational(i(3), i(4));
        CliffordProjectiveRational b = new CliffordProjectiveRational(i(3), i(4));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
