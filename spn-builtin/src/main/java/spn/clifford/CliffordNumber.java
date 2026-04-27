package spn.clifford;

/**
 * Universal interface for any element of the recursive geometric algebra
 * substrate. Every CliffordNumber carries the structural fractional view
 * ({@link #top} / {@link #bottom}) and the four basic arithmetic
 * operations. Specialized operations live in capability interfaces:
 * {@link Bilinear}, {@link Symmetric}, {@link Antisymmetric},
 * {@link Conjugatable}, {@link Invertible}.
 *
 * <p><b>{@link #mult} contract:</b> the *structural* / component-wise
 * product. For scalars it is ordinary multiplication; for top/bottom pairs
 * it is component-wise on the structural data. It is NOT the Cayley-Dickson
 * bilinear product — that lives on {@link Bilinear#composeBilinear}.
 */
public interface CliffordNumber {

    CliffordNumber top();
    CliffordNumber bottom();

    CliffordNumber mult(CliffordNumber other);
    CliffordNumber div(CliffordNumber other);
    CliffordNumber add(CliffordNumber other);
    CliffordNumber sub(CliffordNumber other);

    CliffordNumber negate();

    boolean isZero();
}
