package spn.coefexp;

/**
 * Single-term Laurent expansion in {@code 0}: the value is
 * {@code coef · 0^exp}. This is an alternative encoding to the
 * top/bottom fractional substrate in {@code spn.clifford}; both can
 * represent finite scalars, zero, and ω, but coefexp keeps the algebra
 * mode visible structurally (e.g. ω is unambiguously {@code (1, -1)},
 * parabolic-zero is {@code (1, 1)}).
 *
 * <pre>
 *   exp = 0  ⟹ regular finite scalar (coef · 1 = coef)
 *   exp &gt; 0  ⟹ algebraic zero of order exp  (coef · 0^exp = 0)
 *   exp &lt; 0  ⟹ ω-flavored singularity        (coef · ω^|exp|)
 * </pre>
 *
 * <p>Multiple structural representations of zero exist —
 * {@code (0, anything)} and {@code (anything, exp&gt;0)} are all
 * algebraically zero. Use {@link #isZero()} for algebraic zero;
 * record {@code equals} is structural.
 *
 * <p><b>Addition rule:</b> "lower exp dominates." The more singular
 * (or less zero-like) term swallows the other when exponents differ.
 * {@code (5, 0) + (21, -1) = (21, -1)} — the ω term absorbs the regular
 * scalar, mirroring the wheel-projective absorption in the fractional
 * encoding. Same-exp terms add coefficients normally. Algebraic-zero
 * operands ({@code coef == 0}) short-circuit so they never override a
 * nonzero partner.
 */
public record CoefExp(long coef, long exp) {

    public static final CoefExp ZERO = new CoefExp(0, 0);
    public static final CoefExp ONE = new CoefExp(1, 0);
    public static final CoefExp NEGATIVE_ONE = new CoefExp(-1, 0);
    /** ω = 1/0 = {@code 1 · 0^(-1)}. */
    public static final CoefExp OMEGA = new CoefExp(1, -1);

    public CoefExp negate() {
        return new CoefExp(-coef, exp);
    }

    /** {@code (a, m) · (b, n) = (a·b, m+n)} — exponents add. */
    public CoefExp mult(CoefExp other) {
        return new CoefExp(coef * other.coef, exp + other.exp);
    }

    /**
     * Lower-exp wins. Algebraic-zero operands ({@code coef == 0}) drop
     * out so they never displace a nonzero term.
     */
    public CoefExp add(CoefExp other) {
        if (this.coef == 0) return other;
        if (other.coef == 0) return this;
        if (this.exp == other.exp) {
            return new CoefExp(this.coef + other.coef, this.exp);
        }
        return this.exp < other.exp ? this : other;
    }

    public CoefExp sub(CoefExp other) {
        return this.add(other.negate());
    }

    /** Algebraic zero: either {@code coef == 0} or {@code exp > 0}. */
    public boolean isZero() {
        return coef == 0 || exp > 0;
    }

    @Override
    public String toString() {
        if (exp == 0) return Long.toString(coef);
        if (coef == 0) return "0";
        return coef + "·0^" + exp;
    }
}
