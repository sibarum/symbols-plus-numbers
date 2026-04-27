package spn.clifford;

/**
 * Runtime-mode Cayley-Dickson pair: a pair {@code (top, bottom)} carrying
 * its δ as a third record component, so the same neuron / value can change
 * which corner of the four-corner family it represents at runtime.
 *
 * <p><b>Reference implementation for a neural-network architecture</b> in
 * which a single neuron's weight interpretation can shift between elliptic
 * (rotation), parabolic (translation), hyperbolic (boost), and traction
 * (inversion) modes — gated by a ReLU or a cascade of ReLUs over a learned
 * mode signal. The four mode factories ({@link #elliptic}, {@link #parabolic},
 * {@link #hyperbolic}, {@link #traction}) produce instances whose δ matches
 * the corresponding sealed sibling; {@link #withDelta} produces a copy with
 * the same components but a different mode, so the gate output can pick a
 * mode without rebuilding the pair from scratch.
 *
 * <p>{@link #composeBilinear} requires <b>matching δ</b> on both operands —
 * mixing a hyperbolic-mode pair with a traction-mode pair throws, since the
 * bilinear product is parameterized by a single δ. For mode-mixing
 * architectures, the gate must align both operands to the same mode before
 * composition.
 *
 * <p>For compile-time-fixed mode (where the type system enforces consistency),
 * use the sealed siblings: {@link CliffordEllipticPair},
 * {@link CliffordParabolicPair}, {@link CliffordHyperbolicPair},
 * {@link CliffordTractionPair}. {@code CliffordPair} trades that static
 * guarantee for runtime flexibility.
 */
public record CliffordPair(CliffordNumber top, CliffordNumber bottom, CliffordNumber delta)
        implements CayleyDicksonPair {

    /** δ = −1: rotation / Euclidean rotor mode. */
    public static final CliffordNumber DELTA_ELLIPTIC   = new CliffordInteger(-1);
    /** δ = 0: translation / shear mode. */
    public static final CliffordNumber DELTA_PARABOLIC  = CliffordInteger.ZERO;
    /** δ = +1: boost / scale mode. */
    public static final CliffordNumber DELTA_HYPERBOLIC = CliffordInteger.ONE;
    /** δ = ω: inversion-dilation mode. */
    public static final CliffordNumber DELTA_TRACTION   =
            new CliffordProjectiveRational(CliffordInteger.ONE, CliffordInteger.ZERO);

    // ── Mode factories ──────────────────────────────────────────────

    /** Construct a pair in elliptic mode (δ = −1). */
    public static CliffordPair elliptic(CliffordNumber top, CliffordNumber bottom) {
        return new CliffordPair(top, bottom, DELTA_ELLIPTIC);
    }

    /** Construct a pair in parabolic mode (δ = 0). */
    public static CliffordPair parabolic(CliffordNumber top, CliffordNumber bottom) {
        return new CliffordPair(top, bottom, DELTA_PARABOLIC);
    }

    /** Construct a pair in hyperbolic mode (δ = +1). */
    public static CliffordPair hyperbolic(CliffordNumber top, CliffordNumber bottom) {
        return new CliffordPair(top, bottom, DELTA_HYPERBOLIC);
    }

    /** Construct a pair in traction mode (δ = ω). */
    public static CliffordPair traction(CliffordNumber top, CliffordNumber bottom) {
        return new CliffordPair(top, bottom, DELTA_TRACTION);
    }

    // ── Mode shifting ───────────────────────────────────────────────

    /**
     * Return a copy of this pair with the same components and a new δ.
     * The intended use is gate-driven mode shifting: a learned signal
     * (passed through ReLU or a cascade of ReLUs) selects one of the four
     * corner δ values, and {@code withDelta} produces the appropriately-
     * interpreted weight without reconstructing top and bottom.
     */
    public CliffordPair withDelta(CliffordNumber newDelta) {
        return new CliffordPair(top, bottom, newDelta);
    }

    // ── CayleyDicksonPair contract ──────────────────────────────────

    /** Factory preserves this pair's δ — so default operations build
     *  results in the same mode as the receiver. */
    @Override
    public CayleyDicksonPair factory(CliffordNumber top, CliffordNumber bottom) {
        return new CliffordPair(top, bottom, delta);
    }

    @Override
    public String toString() {
        return "δ=" + delta + "(" + top + ", " + bottom + ")";
    }
}
