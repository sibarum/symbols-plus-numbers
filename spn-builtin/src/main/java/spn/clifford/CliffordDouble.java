package spn.clifford;

/**
 * Floating-point scalar leaf — parallel to {@link CliffordInteger} but with
 * a {@code double} value. Intended for differentiable / NN code paths where
 * integer arithmetic is too coarse.
 *
 * <p>Cross-leaf arithmetic with {@link CliffordInteger} promotes to
 * {@code CliffordDouble}; with any {@link FractionalElement} (rationals,
 * ω-flavored values), it lifts {@code this} to a fraction view and
 * delegates to the field-of-fractions defaults.
 */
public record CliffordDouble(double value)
        implements CliffordNumber, Conjugatable, Invertible, Symmetric, Antisymmetric {

    public static final CliffordDouble ZERO = new CliffordDouble(0.0);
    public static final CliffordDouble ONE  = new CliffordDouble(1.0);

    @Override
    public CliffordNumber top() { return this; }

    @Override
    public CliffordNumber bottom() { return ONE; }

    @Override
    public CliffordNumber negate() {
        return new CliffordDouble(-value);
    }

    @Override
    public boolean isZero() {
        return value == 0.0;
    }

    @Override
    public CliffordNumber conjugate() {
        return this;
    }

    @Override
    public CliffordNumber inverse() {
        if (value == 0.0) {
            // 0⁻¹ = ω. Use the integer-typed projective rep so the result
            // matches the convention used by CliffordInteger.inverse().
            return new CliffordProjectiveRational(CliffordInteger.ONE, CliffordInteger.ZERO);
        }
        return new CliffordDouble(1.0 / value);
    }

    @Override
    public CliffordNumber symmetric(CliffordNumber other) {
        return mult(other);
    }

    @Override
    public CliffordNumber antisymmetric(CliffordNumber other) {
        // Real scalars commute with each other and with rationals.
        if (other instanceof CliffordDouble || other instanceof CliffordInteger
                || other instanceof FractionalElement) {
            return ZERO;
        }
        throw new CliffordIncompatibleArithmeticException(this, other);
    }

    // ── Arithmetic with cross-leaf promotion ─────────────────────────

    @Override
    public CliffordNumber mult(CliffordNumber other) {
        if (other instanceof CliffordDouble(double v)) {
            return new CliffordDouble(value * v);
        }
        if (other instanceof CliffordInteger(long v)) {
            return new CliffordDouble(value * v);
        }
        if (other instanceof FractionalElement) {
            return new CliffordElement(this, ONE).mult(other);
        }
        throw new CliffordIncompatibleArithmeticException(this, other);
    }

    @Override
    public CliffordNumber div(CliffordNumber other) {
        if (other instanceof CliffordDouble(double v)) {
            if (v == 0.0) {
                if (value == 0.0) return ONE;                       // 0/0 = 1 (traction)
                return new CliffordElement(this, ZERO);              // n/0 = n·ω
            }
            return new CliffordDouble(value / v);
        }
        if (other instanceof CliffordInteger(long v)) {
            if (v == 0L) {
                if (value == 0.0) return ONE;
                return new CliffordElement(this, CliffordInteger.ZERO);
            }
            return new CliffordDouble(value / v);
        }
        if (other instanceof FractionalElement) {
            return new CliffordElement(this, ONE).div(other);
        }
        throw new CliffordIncompatibleArithmeticException(this, other);
    }

    @Override
    public CliffordNumber add(CliffordNumber other) {
        if (other instanceof CliffordDouble(double v)) {
            return new CliffordDouble(value + v);
        }
        if (other instanceof CliffordInteger(long v)) {
            return new CliffordDouble(value + v);
        }
        if (other instanceof FractionalElement) {
            return new CliffordElement(this, ONE).add(other);
        }
        throw new CliffordIncompatibleArithmeticException(this, other);
    }

    @Override
    public CliffordNumber sub(CliffordNumber other) {
        if (other instanceof CliffordDouble(double v)) {
            return new CliffordDouble(value - v);
        }
        if (other instanceof CliffordInteger(long v)) {
            return new CliffordDouble(value - v);
        }
        if (other instanceof FractionalElement) {
            return new CliffordElement(this, ONE).sub(other);
        }
        throw new CliffordIncompatibleArithmeticException(this, other);
    }

    @Override
    public String toString() {
        return Double.toString(value);
    }
}
