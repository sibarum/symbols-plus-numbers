package spn.clifford;

/**
 * Capability: Clifford conjugation. For a scalar leaf, conjugation is the
 * identity. For a Cayley-Dickson pair {@code (a, b)} at level n, conjugation
 * is {@code (conjugate(a), −b)}. Required by the bilinear rule
 * {@code (a,b)(c,d) = (ac + δ·d̄·b, da + bc̄)}.
 */
public interface Conjugatable extends CliffordNumber {

    CliffordNumber conjugate();
}
