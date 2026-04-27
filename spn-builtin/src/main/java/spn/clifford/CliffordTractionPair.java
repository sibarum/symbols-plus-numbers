package spn.clifford;

/**
 * Cayley-Dickson pair with parameter δ = ω = (1, 0) — the **traction** case
 * (k-generator in Traction Theory). The unit element {@code (0, 1)}
 * satisfies {@code (0, 1)² = (ω, 0)}, where ω is the wheel-projective
 * {@code 1/0}; doubling yields the user's novel algebra in which
 * {@code k² = ω}.
 *
 * <p>This is the leg of the four-corner family that does NOT have a
 * standard name in mainstream Clifford / geometric algebra literature.
 * PGA uses degenerate basis vectors (square to 0, the parabolic case);
 * CGA gets inversion via sandwich products in a higher-dim embedding,
 * not as a primitive generator. AFAIK no published framework promotes
 * {@code √ω} to a primitive Cayley-Dickson generator.
 *
 * <p>All algebraic behavior comes from {@link CayleyDicksonPair} default
 * methods. At δ = ω, the second cross-term materializes as ω-flavored
 * data via {@code multLeaves(ω)}, producing wheel/projective fractions
 * that flow through the substrate without canonicalization.
 */
public record CliffordTractionPair(CliffordNumber top, CliffordNumber bottom)
        implements CayleyDicksonPair {

    /** δ = ω = (1, 0) — the wheel-projective inverse of zero. */
    public static final CliffordNumber DELTA =
            new CliffordProjectiveRational(CliffordInteger.ONE, CliffordInteger.ZERO);

    @Override
    public CliffordNumber delta() { return DELTA; }

    @Override
    public CayleyDicksonPair factory(CliffordNumber top, CliffordNumber bottom) {
        return new CliffordTractionPair(top, bottom);
    }

    @Override
    public String toString() {
        return "k(" + top + ", " + bottom + ")";
    }
}
