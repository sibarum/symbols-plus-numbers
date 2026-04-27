package spn.clifford;

/**
 * Sealed capability: a {@link CliffordNumber} represented as a formal
 * top/bottom pair carrying field-of-fractions arithmetic. Default methods
 * provide the four rules
 *
 * <pre>
 *   (a/b) · (c/d) = (a·c) / (b·d)
 *   (a/b) / (c/d) = (a·d) / (b·c)
 *   (a/b) ± (c/d) = (a·d ± c·b) / (b·d)
 * </pre>
 *
 * Implementations only need to provide {@link #top} and {@link #bottom}
 * (the record components); the arithmetic comes for free. {@code negate}
 * flips the numerator; {@code isZero} is true only when the numerator is
 * zero AND the denominator is nonzero (so {@code (0, 0)}, the traction
 * {@code 0/0 = 1} case, is NOT zero).
 *
 * <p>Sealed because the substrate's two fractional shapes —
 * {@link CliffordElement} (generic) and {@link CliffordProjectiveRational}
 * (integer-typed) — exhaust the patterns we need; new fractional shapes
 * should be added to the {@code permits} list deliberately.
 */
public sealed interface FractionalElement extends CliffordNumber
        permits CliffordElement, CliffordProjectiveRational {

    @Override
    default CliffordNumber mult(CliffordNumber other) {
        return new CliffordElement(
                top().mult(other.top()),
                bottom().mult(other.bottom())
        );
    }

    @Override
    default CliffordNumber div(CliffordNumber other) {
        return new CliffordElement(
                top().mult(other.bottom()),
                bottom().mult(other.top())
        );
    }

    @Override
    default CliffordNumber add(CliffordNumber other) {
        return new CliffordElement(
                top().mult(other.bottom()).add(other.top().mult(bottom())),
                bottom().mult(other.bottom())
        );
    }

    @Override
    default CliffordNumber sub(CliffordNumber other) {
        return new CliffordElement(
                top().mult(other.bottom()).sub(other.top().mult(bottom())),
                bottom().mult(other.bottom())
        );
    }

    @Override
    default CliffordNumber negate() {
        return new CliffordElement(top().negate(), bottom());
    }

    @Override
    default boolean isZero() {
        return top().isZero() && !bottom().isZero();
    }
}
