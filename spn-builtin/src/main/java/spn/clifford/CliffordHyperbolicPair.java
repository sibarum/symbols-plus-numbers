package spn.clifford;

/**
 * Cayley-Dickson pair with parameter δ = +1 — the hyperbolic / split-complex
 * case (j-generator in Traction Theory). The unit element {@code (0, 1)}
 * satisfies {@code (0, 1)² = (1, 0)}; doubling yields split-complex
 * arithmetic where {@code j² = +1} (Lorentz boost generator).
 *
 * <p>All algebraic behavior comes from {@link CayleyDicksonPair} default
 * methods. Hyperbolic has zero divisors on the light cone
 * ({@code a² = b²}) — inverse fails there.
 */
public record CliffordHyperbolicPair(CliffordNumber top, CliffordNumber bottom)
        implements CayleyDicksonPair {

    /** δ = +1 — the value the unit element of this level squares to. */
    public static final CliffordNumber DELTA = CliffordInteger.ONE;

    @Override
    public CliffordNumber delta() { return DELTA; }

    @Override
    public CayleyDicksonPair factory(CliffordNumber top, CliffordNumber bottom) {
        return new CliffordHyperbolicPair(top, bottom);
    }

    @Override
    public String toString() {
        return "j(" + top + ", " + bottom + ")";
    }
}
