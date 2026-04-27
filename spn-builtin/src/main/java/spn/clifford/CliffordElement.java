package spn.clifford;

/**
 * Generic top/bottom pair carrying field-of-fractions arithmetic via the
 * {@link FractionalElement} default methods. Both components are arbitrary
 * {@link CliffordNumber}s, so this class supports recursive nesting
 * (Element-of-Element) and the wheel/projective {@code ω = (1, 0)} as raw
 * data without canonicalization.
 *
 * <p>Equality and {@code hashCode} are auto-generated and structural —
 * {@code (1, 2)} and {@code (2, 4)} are deliberately NOT equal even though
 * they represent the same rational value, per the substrate's
 * remove-normalization stance.
 */
public record CliffordElement(CliffordNumber top, CliffordNumber bottom)
        implements FractionalElement {

    @Override
    public String toString() {
        return "(" + top + " / " + bottom + ")";
    }
}
