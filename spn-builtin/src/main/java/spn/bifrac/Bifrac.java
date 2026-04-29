package spn.bifrac;

/**
 * Combined fractional/exponential encoding: a value is
 * {@code coef · 0^exp} where both {@link #coef} and {@link #exp} are
 * {@link Frac}s. This stacks the {@code spn.clifford} (top/bottom)
 * substrate inside the {@code spn.coefexp} (coef·0^exp) substrate, so
 * the exponent itself can be a fraction — including ω, -0, and
 * 0/0 — letting the algebra reach values like
 * {@code 0^(1/2)} (a "square root of zero").
 *
 * <p>The four δ corners are now indexed by the <i>exponent</i>:
 * <pre>
 *   exp = 0  → 1   (hyperbolic δ)
 *   exp = 1  → 0   (parabolic δ)
 *   exp = -1 → ω   (traction δ)
 *   exp = ω  → -1  (elliptic δ)
 * </pre>
 *
 * <p>Multiplication adds exponents, so squaring is structurally clean:
 * {@code (1·0^a) · (1·0^a) = 1·0^(a+a)}. Squaring {@code 0^(1/2)}
 * gives {@code 0^((1,2)+(1,2)) = 0^(4,4)} — algebraically
 * {@code 0^1 = 0}, structurally {@code (4,4)} (no GCD reduction).
 *
 * <p><b>Add only handles same-exp.</b> {@code 1·0^a + 1·0^a = 2·0^a}
 * (coefficients add via {@link Frac#add}'s same-bottom shortcut, exp
 * stays put). Different-exp addition is deferred — picking the
 * comparison rule (algebraic vs structural, tie-breaks) is load-bearing
 * for the eventual bilinear formula and not yet decided.
 *
 * <p><b>Squaring uses {@link #pow} (power-of-power), not {@link #mult}.</b>
 * {@code (0^a)² = 0^(2·a)} via integer-times-frac on the exp; that
 * goes through {@link Frac#mult}'s cross-cancellation, which keeps the
 * exponent in a clean form. {@code mult(self)} would instead route
 * through {@link Frac#add}'s same-bottom shortcut, giving an
 * algebraically-equal but structurally-different exp.
 */
public record Bifrac(Frac coef, Frac exp) {

    /** 1 = 1·0^0 — hyperbolic δ. */
    public static final Bifrac ONE = new Bifrac(Frac.ONE, Frac.ZERO);
    /** 0 = 1·0^1 — parabolic δ. */
    public static final Bifrac ZERO = new Bifrac(Frac.ONE, Frac.ONE);
    /** -1 = 1·0^ω — elliptic δ. */
    public static final Bifrac NEGATIVE_ONE = new Bifrac(Frac.ONE, Frac.OMEGA);
    /** ω = 1·0^(-1) — traction δ. */
    public static final Bifrac OMEGA = new Bifrac(Frac.ONE, Frac.NEGATIVE_ONE);

    /** δ = 1 — hyperbolic. */
    public static final Bifrac DELTA_HYPERBOLIC = ONE;
    /** δ = 0 — parabolic. */
    public static final Bifrac DELTA_PARABOLIC = ZERO;
    /** δ = -1 — elliptic. */
    public static final Bifrac DELTA_ELLIPTIC = NEGATIVE_ONE;
    /** δ = ω — traction. */
    public static final Bifrac DELTA_TRACTION = OMEGA;

    /** {@code (c₁·0^e₁) · (c₂·0^e₂) = (c₁·c₂)·0^(e₁+e₂)}. */
    public Bifrac mult(Bifrac other) {
        return new Bifrac(coef.mult(other.coef), exp.add(other.exp));
    }

    /**
     * Power-of-power: {@code (c·0^e)^n = c^n · 0^(n·e)}. The exp goes
     * through {@link Frac#mult} (cross-cancellation), so squaring
     * {@code 0^(0/-2)} lands at {@code 0^(0/-1)} rather than the
     * {@code 0^(0/-2)} that {@code mult(self)} would give via
     * {@link Frac#add}'s same-bottom shortcut.
     *
     * @throws IllegalArgumentException if {@code n < 0}
     */
    public Bifrac pow(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("pow(n) requires n >= 0, got " + n);
        }
        if (n == 0) return ONE;
        Frac newCoef = coef;
        for (int i = 1; i < n; i++) {
            newCoef = newCoef.mult(coef);
        }
        Frac newExp = exp.mult(new Frac(n, 1));
        return new Bifrac(newCoef, newExp);
    }

    /**
     * Same-exp addition: {@code c₁·0^a + c₂·0^a = (c₁+c₂)·0^a}.
     * Coefficients add via {@link Frac#add}; exp passes through unchanged.
     *
     * @throws UnsupportedOperationException if exps differ — different-exp
     *     addition isn't defined yet (need to pick a comparison rule).
     */
    public Bifrac add(Bifrac other) {
        if (!this.exp.equals(other.exp)) {
            throw new UnsupportedOperationException(
                    "Bifrac.add for different exps is not yet defined: "
                    + this.exp + " vs " + other.exp);
        }
        return new Bifrac(coef.add(other.coef), exp);
    }

    /** Negate the coefficient; exponent unchanged. */
    public Bifrac negate() {
        return new Bifrac(coef.negate(), exp);
    }

    @Override
    public String toString() {
        return coef + "·0^" + exp;
    }
}
