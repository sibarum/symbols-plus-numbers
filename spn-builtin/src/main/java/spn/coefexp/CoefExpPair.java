package spn.coefexp;

/**
 * Cayley-Dickson pair built on {@link CoefExp} scalars, parameterized by
 * δ at construction (mirroring the runtime-mode {@code CliffordPair} in
 * {@code spn.clifford}). Each of the four corners — hyperbolic (j),
 * parabolic (ε), elliptic (η), traction (k) — picks its own δ; a single
 * pair type carries them all.
 *
 * <p>Bilinear product:
 * <pre>
 *   (a, b) · (c, d) = (a·c + δ·d̄·b,  d·a + b·c̄)
 * </pre>
 * Components are real {@link CoefExp} scalars at this level, so
 * conjugation is the identity ({@code c̄ = c}, {@code d̄ = d}).
 *
 * <p>The defining identity {@code unit² = (δ, 0)} drops out cleanly: with
 * unit element {@code (0, 1)}, the cross term {@code δ·d̄·b = δ}, and the
 * other three terms are zero. The mode is visible in the structural form
 * of the result top — {@code (1,0)} for j, {@code (1,1)} for ε,
 * {@code (-1,0)} for η, {@code (1,-1)} for k.
 */
public record CoefExpPair(CoefExp top, CoefExp bottom, CoefExp delta) {

    /** δ = 1 — hyperbolic / boost mode. */
    public static final CoefExp DELTA_HYPERBOLIC = new CoefExp(1, 0);
    /** δ = 0 — parabolic / shear mode (kept as {@code (1, 1)} to retain mode info). */
    public static final CoefExp DELTA_PARABOLIC = new CoefExp(1, 1);
    /** δ = -1 — elliptic / rotation mode. */
    public static final CoefExp DELTA_ELLIPTIC = new CoefExp(-1, 0);
    /** δ = ω — traction / inversion-dilation mode. */
    public static final CoefExp DELTA_TRACTION = new CoefExp(1, -1);

    public static CoefExpPair hyperbolic(CoefExp top, CoefExp bottom) {
        return new CoefExpPair(top, bottom, DELTA_HYPERBOLIC);
    }

    public static CoefExpPair parabolic(CoefExp top, CoefExp bottom) {
        return new CoefExpPair(top, bottom, DELTA_PARABOLIC);
    }

    public static CoefExpPair elliptic(CoefExp top, CoefExp bottom) {
        return new CoefExpPair(top, bottom, DELTA_ELLIPTIC);
    }

    public static CoefExpPair traction(CoefExp top, CoefExp bottom) {
        return new CoefExpPair(top, bottom, DELTA_TRACTION);
    }

    /** Cayley-Dickson conjugation: {@code (a, b) → (a, -b)} (top is real). */
    public CoefExpPair conjugate() {
        return new CoefExpPair(top, bottom.negate(), delta);
    }

    public CoefExpPair negate() {
        return new CoefExpPair(top.negate(), bottom.negate(), delta);
    }

    /** Component-wise addition; δ must match. */
    public CoefExpPair add(CoefExpPair other) {
        if (!delta.equals(other.delta)) {
            throw new IllegalArgumentException(
                    "Incompatible delta: " + delta + " vs " + other.delta);
        }
        return new CoefExpPair(top.add(other.top), bottom.add(other.bottom), delta);
    }

    /**
     * Cayley-Dickson bilinear product. δ must match between the two
     * pairs — composing across modes is rejected.
     */
    public CoefExpPair composeBilinear(CoefExpPair other) {
        if (!delta.equals(other.delta)) {
            throw new IllegalArgumentException(
                    "Incompatible delta: " + delta + " vs " + other.delta);
        }
        CoefExp a = top, b = bottom, c = other.top, d = other.bottom;
        // c̄ = c, d̄ = d (real scalars), so the formula simplifies to:
        CoefExp newTop = a.mult(c).add(delta.mult(d).mult(b));
        CoefExp newBottom = d.mult(a).add(b.mult(c));
        return new CoefExpPair(newTop, newBottom, delta);
    }

    @Override
    public String toString() {
        return "(" + top + ", " + bottom + " | δ=" + delta + ")";
    }
}
