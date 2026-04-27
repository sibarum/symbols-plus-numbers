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

    /**
     * Element-wise scaling: divide every scalar leaf in the tree by
     * {@code scalar}. Pairs override to recurse into top and bottom; the
     * default falls back to {@link #div}, which is correct for scalar
     * leaves where there's nothing to recurse into.
     *
     * <p>Used by element-wise operations like inverse normalization
     * ({@code conj / |x|²}) and the {@code ½} factor in
     * {@link Symmetric}/{@link Antisymmetric} — places where the structural
     * fraction-style {@link #div} would silently corrupt at non-leaf levels.
     */
    default CliffordNumber divLeaves(CliffordNumber scalar) {
        return this.div(scalar);
    }

    /**
     * Element-wise scaling: multiply every scalar leaf in the tree by
     * {@code scalar}. Symmetric to {@link #divLeaves}. Used by the
     * Cayley-Dickson bilinear formula's {@code δ·d̄·b} term — particularly
     * for the traction case where δ = ω requires actual leaf-level
     * multiplication rather than a simple sign flip.
     *
     * <p><b>Zero absorption:</b> if {@code this} is zero, returns {@code this}
     * unchanged rather than lifting through {@link #mult}. This keeps
     * {@code 0·ω = 0} at the leaf level, preventing the cross-leaf scalar
     * lift from producing {@code (0, 0)} (the wheel bottom) for what should
     * be ordinary scalar absorption. Without this, even pure scalar
     * arithmetic in the traction algebra leaks ω-flavored data.
     */
    default CliffordNumber multLeaves(CliffordNumber scalar) {
        if (this.isZero()) return this;
        return this.mult(scalar);
    }
}
