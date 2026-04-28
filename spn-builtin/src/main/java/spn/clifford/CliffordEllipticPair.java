package spn.clifford;

/**
 * Cayley-Dickson pair with parameter δ = −1 — the elliptic case (η-rotor in
 * Traction Theory). The unit element {@code (0, 1)} satisfies
 * {@code (0, 1)² = (−1, 0)}; doubling over the integer leaf yields complex
 * arithmetic at level 1 and quaternion arithmetic at level 2.
 *
 * <p>All algebraic behavior comes from {@link CayleyDicksonPair} default
 * methods — this record provides only its δ value and the factory needed
 * for those defaults to construct results of the right concrete type.
 */
public record CliffordEllipticPair(CliffordNumber top, CliffordNumber bottom)
        implements CayleyDicksonPair {

    /** δ = −1 — the value the unit element of this level squares to. */
    public static final CliffordNumber DELTA = CliffordInteger.NEGATIVE_ONE;

    @Override
    public CliffordNumber delta() { return DELTA; }

    @Override
    public CayleyDicksonPair factory(CliffordNumber top, CliffordNumber bottom) {
        return new CliffordEllipticPair(top, bottom);
    }

    @Override
    public String toString() {
        return "η(" + top + ", " + bottom + ")";
    }
}
