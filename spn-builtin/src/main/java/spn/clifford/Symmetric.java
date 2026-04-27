package spn.clifford;

/**
 * Capability: symmetric (inner / contraction) part of a bilinear product —
 * {@code symmetric(a, b) = ½(a·b + b·a)}. For commuting pairs this collapses
 * to the ordinary product; for general multivectors it is grade-lowering.
 */
public interface Symmetric extends CliffordNumber {

    CliffordNumber symmetric(CliffordNumber other);
}
