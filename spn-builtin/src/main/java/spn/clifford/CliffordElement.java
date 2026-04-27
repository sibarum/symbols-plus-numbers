package spn.clifford;

public class CliffordElement implements CliffordNumber {

    private CliffordNumber top;

    private CliffordNumber bottom;

    public CliffordElement(CliffordNumber top, CliffordNumber bottom) {
        this.top = top;
        this.bottom = bottom;
    }

    public CliffordNumber getTop() {
        return top;
    }

    public CliffordNumber getBottom() {
        return bottom;
    }

    public CliffordNumber mult(CliffordNumber other) {
        return new CliffordElement(
                top.mult(other.getTop()),
                bottom.mult(other.getBottom())
        );
    }

    public CliffordNumber div(CliffordNumber other) {
        return new CliffordElement(
                top.mult(other.getBottom()),
                bottom.mult(other.getTop())
        );
    }

    public CliffordNumber add(CliffordNumber other) {
        return new CliffordElement(
                top.mult(other.getBottom()).add(
                        other.getTop().mult(bottom)
                ),
                bottom.mult(other.getBottom())
        );
    }

    public CliffordNumber sub(CliffordNumber other) {
        return new CliffordElement(
                top.mult(other.getBottom()).sub(
                        other.getTop().mult(bottom)
                ),
                bottom.mult(other.getBottom())
        );
    }
}
