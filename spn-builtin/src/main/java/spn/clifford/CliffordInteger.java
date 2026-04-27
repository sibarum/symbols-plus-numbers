package spn.clifford;

public record CliffordInteger(long value)
        implements CliffordNumber, Conjugatable, Invertible, Symmetric, Antisymmetric {

    public static final CliffordInteger ZERO = new CliffordInteger(0);
    public static final CliffordInteger ONE = new CliffordInteger(1);

    @Override
    public CliffordNumber top() {
        return this;
    }

    @Override
    public CliffordNumber bottom() {
        return ONE;
    }

    @Override
    public CliffordNumber negate() {
        return new CliffordInteger(-value);
    }

    @Override
    public boolean isZero() {
        return value == 0;
    }

    /**
     * Conjugation of a real scalar is the identity.
     */
    @Override
    public CliffordNumber conjugate() {
        return this;
    }

    /**
     * Multiplicative inverse. Per traction rule, {@code 0.inverse() = ω}
     * (the wheel-projective {@code (1, 0)}); for nonzero values, returns
     * {@code (1, value)} as a {@link CliffordProjectiveRational} so the
     * inverse is exact and information-preserving (no integer-truncation
     * loss).
     */
    @Override
    public CliffordNumber inverse() {
        if (value == 0) {
            // 0⁻¹ = ω = (1, 0)
            return new CliffordProjectiveRational(ONE, ZERO);
        }
        return new CliffordProjectiveRational(ONE, this);
    }

    /**
     * {@code ½(a·b + b·a)} — for two scalars, this is just {@code a·b}.
     */
    @Override
    public CliffordNumber symmetric(CliffordNumber other) {
        return mult(other);
    }

    /**
     * {@code ½(a·b − b·a)} — for two scalars, always zero.
     */
    @Override
    public CliffordNumber antisymmetric(CliffordNumber other) {
        if (other instanceof CliffordInteger) {
            return ZERO;
        }
        throw new CliffordIncompatibleArithmeticException(this, other);
    }

    @Override
    public CliffordNumber mult(CliffordNumber other) {
        if (other instanceof CliffordInteger(long value1)) {
            return new CliffordInteger(value * value1);
        }
        // Scalar × fractional: lift this to (this, 1) and delegate to the
        // field-of-fractions rule. Mathematically unambiguous; avoids the
        // cross-flavor ambiguity that mixing distinct generator leaves
        // would introduce.
        if (other instanceof FractionalElement) {
            return new CliffordProjectiveRational(this, ONE).mult(other);
        }
        throw new CliffordIncompatibleArithmeticException(this, other);
    }

    @Override
    public CliffordNumber div(CliffordNumber other) {
        if (other instanceof CliffordInteger otherInt) {
            if (otherInt.value == 0) {
                // Traction: 0/0 = 1 (no indeterminate forms in
                // information-conservative arithmetic).
                if (value == 0) return ONE;
                // Wheel: n/0 (n ≠ 0) promotes to (n, 0) so ω-valued data
                // flows through the substrate without loss.
                return new CliffordProjectiveRational(this, otherInt);
            }
            // Information-conservative: only collapse to integer when the
            // division is exact; otherwise keep the value as a rational
            // so no precision is lost to truncation.
            if (value % otherInt.value == 0) {
                return new CliffordInteger(value / otherInt.value);
            }
            return new CliffordProjectiveRational(this, otherInt);
        }
        throw new CliffordIncompatibleArithmeticException(this, other);
    }

    @Override
    public CliffordNumber add(CliffordNumber other) {
        if (other instanceof CliffordInteger(long value1)) {
            return new CliffordInteger(value + value1);
        }
        if (other instanceof FractionalElement) {
            return new CliffordProjectiveRational(this, ONE).add(other);
        }
        throw new CliffordIncompatibleArithmeticException(this, other);
    }

    @Override
    public CliffordNumber sub(CliffordNumber other) {
        if (other instanceof CliffordInteger(long value1)) {
            return new CliffordInteger(value - value1);
        }
        if (other instanceof FractionalElement) {
            return new CliffordProjectiveRational(this, ONE).sub(other);
        }
        throw new CliffordIncompatibleArithmeticException(this, other);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CliffordInteger(long value1))) return false;
        return value == value1;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
