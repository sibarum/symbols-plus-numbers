package spn.clifford;

/**
 * Named refinement of {@link FractionalElement} with both components
 * constrained to {@link CliffordInteger}. Models classical projective
 * rationals — including {@code ω = (1, 0)} as a first-class element —
 * inside the wheel/projective semantics of the substrate.
 *
 * <p>The accessors {@link #top} and {@link #bottom} return
 * {@link CliffordInteger} (covariantly tightening
 * {@link CliffordNumber#top}/{@link CliffordNumber#bottom}). Field-of-
 * fractions arithmetic is inherited from {@link FractionalElement} default
 * methods.
 */
public record CliffordProjectiveRational(CliffordInteger top, CliffordInteger bottom)
        implements FractionalElement, Conjugatable {

    /** Conjugation of a real-valued rational is the identity. */
    @Override
    public CliffordNumber conjugate() {
        return this;
    }

    @Override
    public String toString() {
        return "(" + top + " / " + bottom + ")";
    }
}
