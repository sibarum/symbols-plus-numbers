package spn.bifrac;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link Bifrac} — the {@code coef·0^exp} encoding with
 * {@link Frac} on both sides. Covers:
 * <ul>
 *   <li>The four δ-corner constants.</li>
 *   <li>{@link Bifrac#mult} (general product — exp via {@link Frac#add}).</li>
 *   <li>{@link Bifrac#pow} (squaring / power-of-power — exp via
 *       {@link Frac#mult}; the user's preferred path for raising to
 *       integer powers).</li>
 *   <li>{@link Bifrac#add} for the same-exp case (only).</li>
 *   <li>The user's three corrections, made explicit.</li>
 * </ul>
 */
class BifracTest {

    private static Frac f(long t, long b) { return new Frac(t, b); }
    private static Bifrac b(Frac coef, Frac exp) { return new Bifrac(coef, exp); }

    // ── δ-corner constants ──────────────────────────────────────────

    @Test
    void hyperbolicDeltaIsOneAtExpZero() {
        assertEquals(b(Frac.ONE, Frac.ZERO), Bifrac.DELTA_HYPERBOLIC);
    }

    @Test
    void parabolicDeltaIsOneAtExpOne() {
        assertEquals(b(Frac.ONE, Frac.ONE), Bifrac.DELTA_PARABOLIC);
    }

    @Test
    void ellipticDeltaIsOneAtExpOmega() {
        // -1 = 1·0^ω — exp at ω, not coef at -1.
        assertEquals(b(Frac.ONE, Frac.OMEGA), Bifrac.DELTA_ELLIPTIC);
    }

    @Test
    void tractionDeltaIsOneAtExpMinusOne() {
        // ω = 1·0^(-1) — exp at -1, not coef at "ω".
        assertEquals(b(Frac.ONE, Frac.NEGATIVE_ONE), Bifrac.DELTA_TRACTION);
    }

    // ── User's correction 1: ω + ω = 2ω in both layers ──────────────

    @Test
    void omegaPlusOmegaIsTwoOmegaInFractionalLayer() {
        // (1, 0) + (1, 0) = (2, 0) via Frac.add same-bottom shortcut.
        assertEquals(f(2, 0), Frac.OMEGA.add(Frac.OMEGA));
    }

    @Test
    void omegaPlusOmegaIsTwoOmegaInExponentialLayer() {
        // 1·0^(-1) + 1·0^(-1) = 2·0^(-1) — same-exp Bifrac.add: coefs
        // sum (Frac.add same-bottom shortcut), exp passes through.
        Bifrac sum = Bifrac.OMEGA.add(Bifrac.OMEGA);
        assertEquals(b(f(2, 1), Frac.NEGATIVE_ONE), sum);
    }

    @Test
    void differentExpAddIsRejected() {
        // Same-exp only for now; different-exp add needs a comparison
        // rule that hasn't been chosen.
        assertThrows(UnsupportedOperationException.class,
                () -> Bifrac.ONE.add(Bifrac.OMEGA));
    }

    // ── Multiplication: general x·y, exp via Frac.add ───────────────

    @Test
    void multCombinesCoefAndExp() {
        // (3·0^2) · (5·0^4) = 15·0^6.
        // exp add: (2,1) + (4,1) same-bottom shortcut → (6, 1).
        assertEquals(b(f(15, 1), f(6, 1)),
                b(f(3, 1), f(2, 1)).mult(b(f(5, 1), f(4, 1))));
    }

    @Test
    void multByOneIsIdentity() {
        Bifrac x = b(f(7, 3), f(2, 5));
        // ONE = (1·0^0). coef.mult(ONE) cross-cancels to coef on
        // non-reduced inputs; exp.add(ZERO) is different-bottom
        // (5 vs 1), so cross-multiply: (2·1 + 0·5, 5·1) = (2, 5).
        assertEquals(x, x.mult(Bifrac.ONE));
    }

    @Test
    void omegaTimesOmegaViaMultHasExpMinusTwo() {
        // ω · ω via mult: exp = (-1,1) + (-1,1) same-bottom → (-2, 1).
        assertEquals(b(Frac.ONE, f(-2, 1)),
                Bifrac.OMEGA.mult(Bifrac.OMEGA));
    }

    // ── Squaring via pow(2): the user's preferred path ──────────────

    @Test
    void powZeroReturnsOne() {
        // x^0 = 1 for any x. Multiplicative identity.
        assertEquals(Bifrac.ONE, Bifrac.OMEGA.pow(0));
        assertEquals(Bifrac.ONE, Bifrac.NEGATIVE_ONE.pow(0));
    }

    @Test
    void powOneIsIdentity() {
        // exp · (1, 1) cross-cancels to exp itself (no cross-factors).
        Bifrac x = b(f(7, 3), f(0, -2));
        assertEquals(x, x.pow(1));
    }

    @Test
    void oneSquaredIsOne() {
        // 1² = (1·0^0).pow(2) = 1·0^(0·2) = 1·0^0.
        // exp · (2, 1): cross-cancel gcd(0, 1)=1, gcd(2, 1)=1; result (0, 1).
        assertEquals(Bifrac.ONE, Bifrac.ONE.pow(2));
    }

    @Test
    void zeroSquaredHasExpTwoNotOne() {
        // 0² = (1·0^1).pow(2) = 1·0^2. exp · (2, 1): cross-cancel
        // gcd(1, 1)=1, gcd(2, 1)=1; result (2, 1). Algebraically 0²=0;
        // structurally exp is (2, 1), distinct from the canonical (1, 1).
        assertEquals(b(Frac.ONE, f(2, 1)), Bifrac.ZERO.pow(2));
    }

    @Test
    void omegaSquaredHasExpMinusTwo() {
        // ω² = (1·0^-1).pow(2) = 1·0^(-2). Standard "ω squared = ω-of-order-2".
        assertEquals(b(Frac.ONE, f(-2, 1)), Bifrac.OMEGA.pow(2));
    }

    @Test
    void negativeOneSquaredHasExpTwoOmega() {
        // (-1)² = (1·0^ω).pow(2) = 1·0^(2ω). exp · (2, 1): cross-cancel
        // skips gcd(2, 0) because of the zero, so the 2 stays in the
        // numerator and the exp lands at (2, 0) — distinct from ω = (1, 0).
        // Whether 2ω ↔ ω later (and thus whether (-1)² ↔ -1 or +1) is
        // a separate identification not baked into the encoding here.
        assertEquals(b(Frac.ONE, f(2, 0)), Bifrac.NEGATIVE_ONE.pow(2));
    }

    // ── User's correction 3: (0^(1/2))² = 0^1 = 0 ───────────────────

    @Test
    void sqrtOfZeroSquaredIsZero() {
        // 0^(1/2) is "√0". Squared via pow(2): exp · (2, 1) =
        // (1, 2)·(2, 1). Cross-cancel: gcd(1, 1)=1, gcd(2, 2)=2 →
        // (1, 1)·(1, 1) = (1, 1). Result: 1·0^1 = ZERO. Matches the
        // user's intuition exactly.
        Bifrac root = b(Frac.ONE, f(1, 2));
        assertEquals(Bifrac.ZERO, root.pow(2));
    }

    @Test
    void sqrtOfOneSquaredIsOne() {
        // 0^(0/2) candidate "√1". Squared: exp · (2, 1) cross-cancels
        // gcd(2, 2)=2 → (0, 1) · (1, 1) = (0, 1). Result: ONE.
        Bifrac root = b(Frac.ONE, f(0, 2));
        assertEquals(Bifrac.ONE, root.pow(2));
    }

    @Test
    void sqrtOfOmegaSquaredIsOmega() {
        // 0^(-1/2) "√ω". Squared: cross-cancel gcd(2, 2)=2 →
        // (-1, 1)·(1, 1) = (-1, 1). Result: OMEGA.
        Bifrac root = b(Frac.ONE, f(-1, 2));
        assertEquals(Bifrac.OMEGA, root.pow(2));
    }

    // ── User's correction 2: power-of-power, no addition ────────────

    @Test
    void sqrtOfNegativeZeroSquaredLandsAtNegativeZero() {
        // 0^(0/-2) "candidate √(-1)" via the user's hand-derivation:
        //   (0^(0/-2))² = 0^(2 · 0/-2) = 0^(0/-1)
        // Implemented via pow(2): exp · (2, 1) = (0, -2)·(2, 1).
        // Cross-cancel: gcd(0, 1)=1, gcd(2, -2)=2 → (0, 1)·(1, -1) = (0, -1).
        // So we land at 1·0^(0/-1) — "1·0^(-0)". The user's chain then
        // identifies -0 ↔ ω giving 0^ω = -1, but THAT step (the -0/ω
        // identification) is not implemented here. The encoding stops
        // at the structural form (0, -1).
        Bifrac root = b(Frac.ONE, f(0, -2));
        assertEquals(b(Frac.ONE, f(0, -1)), root.pow(2));
    }

    @Test
    void powAndMultSelfDifferOnNonReducedExp() {
        // The whole point of correction 2: pow(2) and mult(self) can
        // give structurally different exps when the exp is non-reduced.
        // For 0^(0/-2): pow(2) routes through Frac.mult (cross-cancel)
        // → exp (0, -1). mult(self) routes through Frac.add (same-bottom
        // shortcut) → exp (0, -2). Both algebraically zero, structurally
        // distinct. The user's "no addition" rule picks pow.
        Bifrac x = b(Frac.ONE, f(0, -2));
        assertEquals(b(Frac.ONE, f(0, -1)), x.pow(2));
        assertEquals(b(Frac.ONE, f(0, -2)), x.mult(x));
    }

    @Test
    void powAndMultSelfAgreeWhenCrossCancelIsTrivial() {
        // Frac.add (same-bottom shortcut) and Frac.mult (cross-cancel)
        // give the same exp whenever no useful gcd is available — either
        // the denominator is 1, or a zero blocks the cancel. Verify
        // across all four canonical δs.
        assertEquals(Bifrac.ONE.pow(2), Bifrac.ONE.mult(Bifrac.ONE));
        assertEquals(Bifrac.ZERO.pow(2), Bifrac.ZERO.mult(Bifrac.ZERO));
        assertEquals(Bifrac.OMEGA.pow(2), Bifrac.OMEGA.mult(Bifrac.OMEGA));
        assertEquals(Bifrac.NEGATIVE_ONE.pow(2),
                Bifrac.NEGATIVE_ONE.mult(Bifrac.NEGATIVE_ONE));
    }

    // ── pow on higher integers ──────────────────────────────────────

    @Test
    void powCubeMultipliesExpByThree() {
        // ω³ = (1·0^-1).pow(3) — exp · (3, 1). Cross-cancel: gcd(-1, 1)=1,
        // gcd(3, 1)=1; result (-3, 1).
        assertEquals(b(Frac.ONE, f(-3, 1)), Bifrac.OMEGA.pow(3));
    }

    @Test
    void powRejectsNegativeExponent() {
        assertThrows(IllegalArgumentException.class, () -> Bifrac.ONE.pow(-1));
    }

    // ── Negate ──────────────────────────────────────────────────────

    @Test
    void negateFlipsCoefOnly() {
        Bifrac x = b(f(3, 5), f(2, 7));
        assertEquals(b(f(-3, 5), f(2, 7)), x.negate());
    }
}
