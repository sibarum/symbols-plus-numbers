package spn.clifford;

public class CliffordInteger implements CliffordNumber {

    public static CliffordInteger ONE = new CliffordInteger(1);

    private long value;

    public CliffordInteger(long value) {
        this.value = value;
    }

    public long getValue() {
        return value;
    }

    @Override
    public CliffordNumber getTop() {
        return this;
    }

    @Override
    public CliffordNumber getBottom() {
        // TODO: confirm this shouldn't be 0? Maybe even null?
        return ONE;
    }

    @Override
    public CliffordNumber mult(CliffordNumber other) {
        if (other instanceof CliffordInteger otherInt) {
            return new CliffordInteger(value * otherInt.value);
        }
        throw new CliffordIncompatibleArithmeticException(this, other);
    }

    @Override
    public CliffordNumber div(CliffordNumber other) {
        if (other instanceof CliffordInteger otherInt) {
            return new CliffordInteger(value / otherInt.value);
        }
        throw new CliffordIncompatibleArithmeticException(this, other);
    }

    @Override
    public CliffordNumber add(CliffordNumber other) {
        if (other instanceof CliffordInteger otherInt) {
            return new CliffordInteger(value + otherInt.value);
        }
        throw new CliffordIncompatibleArithmeticException(this, other);
    }

    @Override
    public CliffordNumber sub(CliffordNumber other) {
        if (other instanceof CliffordInteger otherInt) {
            return new CliffordInteger(value - otherInt.value);
        }
        throw new CliffordIncompatibleArithmeticException(this, other);
    }
}
