package spn.clifford;

/**
 * Element of the Chebyshev ring {@code Q[s][g] / (g² − s·g + 1)}, where
 * {@code g} is an algebraic generator with {@code g² = sg − 1} and
 * {@code s = g + g⁻¹} is the trace parameter. Values are stored as
 * {@code (a, b, s)} representing {@code a + b·g} in the ring with the
 * given trace.
 *
 * <p>This is the form used in the user's "traction calculator" (Python
 * file {@code chebyshev_ring.py}). It is a single-parameter deformation
 * of the algebra: at different values of {@code s} the multiplication
 * lands in qualitatively different signature regions:
 * <ul>
 *   <li>{@code |s| < 2}: g is complex with |g| = 1 — elliptic / rotational regime
 *   <li>{@code s = ±2}: g = ±1 (double root) — parabolic edge / shear
 *   <li>{@code |s| > 2}: g real with two distinct roots — hyperbolic / boost regime
 *   <li>{@code s → ±∞}: ω-direction limit, the traction extreme
 * </ul>
 *
 * <p><b>Distinction from {@link CayleyDicksonPair}:</b> the bilinear
 * formula here always carries a {@code −b₁b₂} term in {@code a'}
 * (independent of {@code s}); Cayley-Dickson with variable δ would
 * scale that term by δ. So {@code Q[s][g]} at {@code s = 0} matches
 * elliptic Cayley-Dickson, but other {@code s} values give a different
 * algebra than the four-corner family. Intentionally NOT in the
 * {@code CayleyDicksonPair} sealed hierarchy.
 *
 * <p>Suitable for NN experiments where {@code s} is a learnable parameter
 * — the gradient flow on {@code s} reveals which signature region a task
 * naturally pulls toward.
 *
 * <p>Reference for the formulas: {@code chebyshev_ring.py} lines 414–442
 * (mult, conjugate) and 444–450 (norm).
 */
public record CliffordRingElement(double a, double b, double s) {

    /** Identity: {@code 1 + 0·g}. */
    public static CliffordRingElement identity(double s) {
        return new CliffordRingElement(1.0, 0.0, s);
    }

    /** Pure generator: {@code 0 + 1·g}. */
    public static CliffordRingElement generator(double s) {
        return new CliffordRingElement(0.0, 1.0, s);
    }

    /**
     * Ring multiplication via {@code g² = sg − 1}:
     * <pre>
     *   (a₁ + b₁g)(a₂ + b₂g) = (a₁a₂ − b₁b₂) + (a₁b₂ + b₁a₂ + b₁b₂s)·g
     * </pre>
     * Both operands must share the same {@code s} — different traces
     * mean different rings, with no canonical product between them.
     */
    public CliffordRingElement mult(CliffordRingElement other) {
        if (s != other.s) {
            throw new IllegalArgumentException(
                    "Ring multiplication requires matching s; got " + s + " and " + other.s);
        }
        double newA = a * other.a - b * other.b;
        double newB = a * other.b + b * other.a + b * other.b * s;
        return new CliffordRingElement(newA, newB, s);
    }

    /** Sum (component-wise on a and b; s unchanged). */
    public CliffordRingElement add(CliffordRingElement other) {
        if (s != other.s) {
            throw new IllegalArgumentException(
                    "Ring addition requires matching s; got " + s + " and " + other.s);
        }
        return new CliffordRingElement(a + other.a, b + other.b, s);
    }

    /** Difference. */
    public CliffordRingElement sub(CliffordRingElement other) {
        if (s != other.s) {
            throw new IllegalArgumentException(
                    "Ring subtraction requires matching s; got " + s + " and " + other.s);
        }
        return new CliffordRingElement(a - other.a, b - other.b, s);
    }

    /** Element-wise scalar multiplication (real scalar, no s involvement). */
    public CliffordRingElement scale(double k) {
        return new CliffordRingElement(a * k, b * k, s);
    }

    /**
     * Conjugate via {@code g → g⁻¹ = s − g}:
     * <pre>
     *   conj(a + b·g) = (a + b·s) − b·g
     * </pre>
     */
    public CliffordRingElement conjugate() {
        return new CliffordRingElement(a + b * s, -b, s);
    }

    /**
     * Norm {@code N(a + b·g) = a² + abs + b²}. Equals {@code (a + b·g)·conj(a + b·g)}'s
     * scalar component (the b·g component is identically zero by construction).
     * Sign convention here matches the calculator: the norm is
     * {@code positive-definite when |s| ≤ 2}, indefinite for {@code |s| > 2}.
     */
    public double norm() {
        return a * a + a * b * s + b * b;
    }

    /**
     * Multiplicative inverse: {@code x⁻¹ = conj(x) / N(x)}.
     * Throws if {@code N(x) = 0} (zero divisor on the {@code |s| = 2} edge
     * or on the light cone for {@code |s| > 2}).
     */
    public CliffordRingElement inverse() {
        double n = norm();
        if (n == 0.0) {
            throw new ArithmeticException(
                    "CliffordRingElement has zero norm (s=" + s + ", a=" + a + ", b=" + b + ")");
        }
        CliffordRingElement c = conjugate();
        return c.scale(1.0 / n);
    }

    /**
     * Signature classification of the current {@code s} value:
     * <ul>
     *   <li>{@code "elliptic"} if {@code |s| < 2}</li>
     *   <li>{@code "parabolic"} if {@code |s| = 2}</li>
     *   <li>{@code "hyperbolic"} if {@code |s| > 2}</li>
     * </ul>
     */
    public String signatureRegion() {
        double abs = Math.abs(s);
        if (abs < 2.0 - 1e-9) return "elliptic";
        if (abs > 2.0 + 1e-9) return "hyperbolic";
        return "parabolic";
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "(%.4f + %.4f·g | s=%.4f, %s)", a, b, s, signatureRegion());
    }
}
