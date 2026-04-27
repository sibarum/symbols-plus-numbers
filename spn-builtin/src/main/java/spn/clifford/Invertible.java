package spn.clifford;

/**
 * Capability: multiplicative inverse such that {@code x.mult(x.inverse())}
 * is the identity of the relevant algebra (or {@code composeBilinear}'s
 * identity, depending on which product is meant — concrete types document
 * which). For a wheel/projective leaf, {@code 0.inverse() = ω = (1, 0)}
 * rather than throwing.
 */
public interface Invertible extends CliffordNumber {

    CliffordNumber inverse();
}
