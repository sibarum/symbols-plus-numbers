package spn.clifford;

/**
 * Capability: antisymmetric (wedge / exterior) part of a bilinear product —
 * {@code antisymmetric(a, b) = ½(a·b − b·a)}. For commuting pairs this is
 * zero; for general multivectors it is grade-raising. 2D and 3D cross
 * products are antisymmetric specialized to specific dimensions.
 */
public interface Antisymmetric extends CliffordNumber {

    CliffordNumber antisymmetric(CliffordNumber other);
}
