package spn.bifrac;

/**
 * Top/bottom integer fraction: {@code (top, bottom) = top / bottom}.
 * The substrate of the bifrac encoding — both the coefficient and the
 * exponent in {@link Bifrac} are Fracs.
 *
 * <p><b>No canonicalization.</b> {@code (0, -2)}, {@code (0, 1)}, and
 * {@code (0, 0)} are all algebraically zero (or undefined for 0/0) but
 * are kept as distinct structural values. This is deliberate: the
 * encoding's purpose is to track structural information that
 * canonicalization would erase. Use {@link #isZero()} / {@link #isOmega()}
 * / {@link #isIndeterminate()} for algebraic predicates; record
 * {@code equals} is structural.
 *
 * <p><b>Add — same-bottom shortcut.</b> When both fractions share a
 * denominator, just add tops; otherwise cross-multiply.
 * <pre>
 *   (a, b) + (c, b) = (a + c, b)             // same-bottom shortcut
 *   (a, b) + (c, d) = (a·d + c·b, b·d)       // standard, when b ≠ d
 *   -(a, b)         = (-a, b)
 * </pre>
 * The shortcut is load-bearing: without it, {@code (1,0) + (1,0)}
 * would cross-multiply to {@code (0, 0)} (indeterminate) rather than
 * the intended {@code (2, 0) = 2ω}, and {@code (0,-2) + (0,-2)}
 * would land at {@code (0, 4)} instead of preserving the {@code -2}
 * denominator. <b>Add does not GCD-reduce</b> — coefficients on
 * non-reduced shapes (like {@code 2ω}) must survive.
 *
 * <p><b>Mult — cross-cancellation, no GCD across a zero.</b> Standard
 * multiplication of tops and bottoms, with cross-cancellation: gcd(top
 * of A, bottom of B) is canceled, and similarly gcd(top of B, bottom of
 * A). Critically, the cancel is <i>skipped</i> whenever either operand
 * of the gcd is 0. This is what keeps {@code 2 · ω = (2, 0)} (the 2
 * doesn't get absorbed into ω's zero denominator) and consistent with
 * {@code ω + ω = (2, 0)}. Concrete examples:
 * <pre>
 *   (1, 1) · (0, -2) = (0, -2)   // identity preserved
 *   (2, 1) · (0, -2) = (0, -1)   // gcd(2, -2) = 2 cancels
 *   (2, 1) · (1, 2)  = (1, 1)    // gcd(2, 2)  = 2 cancels
 *   (2, 1) · (1, 0)  = (2, 0)    // gcd skipped (0 present)
 *   (0, -2)·(0, -2)  = (0, 4)    // gcd skipped (0 present)
 * </pre>
 */
public record Frac(long top, long bottom) {

    /** 0 = (0, 1). */
    public static final Frac ZERO = new Frac(0, 1);
    /** 1 = (1, 1). */
    public static final Frac ONE = new Frac(1, 1);
    /** -1 = (-1, 1). */
    public static final Frac NEGATIVE_ONE = new Frac(-1, 1);
    /** ω = 1/0 = (1, 0). */
    public static final Frac OMEGA = new Frac(1, 0);

    public Frac negate() {
        return new Frac(-top, bottom);
    }

    public Frac add(Frac other) {
        if (this.bottom == other.bottom) {
            // Same-bottom shortcut — preserves the denominator so
            // ω + ω = (2, 0) and (0,-2) + (0,-2) = (0, -2) without
            // collapsing to indeterminate or to a bloated denominator.
            return new Frac(this.top + other.top, this.bottom);
        }
        return new Frac(top * other.bottom + other.top * bottom,
                        bottom * other.bottom);
    }

    public Frac sub(Frac other) {
        return this.add(other.negate());
    }

    public Frac mult(Frac other) {
        // Cross-cancel: gcd between A.top and B.bottom on one side,
        // gcd between B.top and A.bottom on the other. Cancellation
        // only crosses operands — internal factors of either operand
        // survive, so (1, 1) stays a multiplicative identity even on
        // non-reduced inputs like (0, -2).
        //
        // GCD is NOT used when either input is 0 — that would let
        // (2, 1) · (1, 0) collapse to (1, 0), losing the coefficient
        // of 2ω. Skipping the cancel keeps 2·ω = 2ω = ω + ω consistent
        // across add and mult.
        long g1 = crossGcd(this.top, other.bottom);
        long g2 = crossGcd(other.top, this.bottom);

        long aTop = this.top / g1;
        long bBot = other.bottom / g1;
        long bTop = other.top / g2;
        long aBot = this.bottom / g2;

        return new Frac(aTop * bTop, aBot * bBot);
    }

    /** GCD for cross-cancellation. Returns 1 (no cancel) when either
     *  operand is 0 — preserves coefficients on ω-like and zero-like
     *  shapes. Otherwise, standard absolute-value gcd. */
    private static long crossGcd(long a, long b) {
        if (a == 0 || b == 0) return 1;
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a;
    }

    /** Algebraic zero: numerator zero with nonzero denominator. */
    public boolean isZero() {
        return top == 0 && bottom != 0;
    }

    /** Algebraic ω: nonzero numerator with zero denominator. */
    public boolean isOmega() {
        return top != 0 && bottom == 0;
    }

    /** {@code 0/0} — neither zero nor ω. */
    public boolean isIndeterminate() {
        return top == 0 && bottom == 0;
    }

    @Override
    public String toString() {
        return "(" + top + "/" + bottom + ")";
    }
}
