package spn.clifford;

/**
 * Cayley-Dickson pair with parameter δ = 0 — the parabolic / dual-number
 * case (ε-generator in Traction Theory). The unit element {@code (0, 1)}
 * satisfies {@code (0, 1)² = (0, 0)}; doubling yields dual-number
 * arithmetic where {@code ε² = 0}.
 *
 * <p>All algebraic behavior comes from {@link CayleyDicksonPair} default
 * methods. At δ = 0, the second cross-term in the bilinear formula
 * evaluates to zero (via {@code multLeaves(0)} which propagates through
 * the leaf-level zero-absorption), recovering the dual-number multiplication
 * {@code (a + bε)·(c + dε) = ac + (ad + bc)ε}.
 */
public record CliffordParabolicPair(CliffordNumber top, CliffordNumber bottom)
        implements CayleyDicksonPair {

    /** δ = 0 — the value the unit element of this level squares to. */
    public static final CliffordNumber DELTA = CliffordInteger.ZERO;

    @Override
    public CliffordNumber delta() { return DELTA; }

    @Override
    public CayleyDicksonPair factory(CliffordNumber top, CliffordNumber bottom) {
        return new CliffordParabolicPair(top, bottom);
    }

    @Override
    public String toString() {
        return "ε(" + top + ", " + bottom + ")";
    }
}
