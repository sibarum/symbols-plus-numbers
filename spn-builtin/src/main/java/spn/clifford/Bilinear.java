package spn.clifford;

/**
 * Capability: composes via the Cayley-Dickson bilinear rule, parameterized
 * by a per-level δ. The unit element of a Bilinear at this level satisfies
 * {@code unit · unit = δ}; the four traction-theory generators correspond to
 * δ ∈ {−1 (η, elliptic), 0 (ε, parabolic), +1 (j, hyperbolic), ω (k, traction)}.
 *
 * <p>Distinct from {@link CliffordNumber#mult}, which is the structural /
 * component-wise product defined uniformly across all CliffordNumbers.
 * {@code composeBilinear} is the *algebraic* product that produces
 * scalar+bivector output at each level of the recursive tower.
 */
public interface Bilinear extends CliffordNumber {

    /** Cayley-Dickson bilinear product at this level. */
    CliffordNumber composeBilinear(CliffordNumber other);

    /** δ at this level: the value that the new generator squares to. */
    CliffordNumber delta();
}
