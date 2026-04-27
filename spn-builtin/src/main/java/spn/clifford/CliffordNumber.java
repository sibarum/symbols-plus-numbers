package spn.clifford;

public interface CliffordNumber {

    public CliffordNumber getTop();
    public CliffordNumber getBottom();

    public CliffordNumber mult(CliffordNumber other);
    public CliffordNumber div(CliffordNumber other);
    public CliffordNumber add(CliffordNumber other);
    public CliffordNumber sub(CliffordNumber other);
}
