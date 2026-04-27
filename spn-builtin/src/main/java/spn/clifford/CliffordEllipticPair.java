package spn.clifford;

/**
 * Cayley-Dickson pair with parameter δ = −1 — the elliptic case (η-rotor in
 * Traction Theory). The unit element {@code (0, 1)} satisfies
 * {@code (0, 1)² = (−1, 0)}; doubling over the integer leaf yields
 * complex-number arithmetic, doubling again yields quaternion arithmetic,
 * and so on up the tower.
 *
 * <p><b>Two products live on this class.</b> They are deliberately
 * distinct, per the substrate's design philosophy:
 * <ul>
 *   <li>{@link #mult} — structural / component-wise:
 *       {@code (a, b)·(c, d) = (a·c, b·d)}. Same shape as
 *       {@link CliffordElement#mult}; useful for differentiation paths
 *       and for structural experimentation. NOT the algebraic product.</li>
 *   <li>{@link #composeBilinear} — the Cayley-Dickson rule:
 *       {@code (a, b)·(c, d) = (a·c − d̄·b,  d·a + b·c̄)}. This is the
 *       *algebraic* product that produces complex multiplication at
 *       level 1, quaternion multiplication at level 2, etc.</li>
 * </ul>
 *
 * <p><b>Required component capabilities:</b> {@link #composeBilinear},
 * {@link #conjugate}, {@link #inverse}, {@link #symmetric}, and
 * {@link #antisymmetric} all need the components to be
 * {@link Conjugatable}. {@link CliffordInteger} and
 * {@link CliffordProjectiveRational} already are; another
 * {@code CliffordEllipticPair} (level n+1 over level n) is also
 * Conjugatable and so the recursion goes through.
 */
public record CliffordEllipticPair(CliffordNumber top, CliffordNumber bottom)
        implements CliffordNumber, Bilinear, Conjugatable, Invertible, Symmetric, Antisymmetric {

    /** δ = −1 — the value the unit element of this level squares to. */
    public static final CliffordNumber DELTA = new CliffordInteger(-1);

    private static final CliffordInteger TWO = new CliffordInteger(2);

    @Override
    public CliffordNumber delta() { return DELTA; }

    // ── Universal CliffordNumber arithmetic ─────────────────────────

    /** Component-wise: {@code (a, b) + (c, d) = (a+c, b+d)}. */
    @Override
    public CliffordNumber add(CliffordNumber other) {
        return new CliffordEllipticPair(
                top.add(other.top()),
                bottom.add(other.bottom())
        );
    }

    /** Component-wise: {@code (a, b) − (c, d) = (a−c, b−d)}. */
    @Override
    public CliffordNumber sub(CliffordNumber other) {
        return new CliffordEllipticPair(
                top.sub(other.top()),
                bottom.sub(other.bottom())
        );
    }

    /** Structural / component-wise. NOT the bilinear product —
     *  see {@link #composeBilinear}. */
    @Override
    public CliffordNumber mult(CliffordNumber other) {
        return new CliffordEllipticPair(
                top.mult(other.top()),
                bottom.mult(other.bottom())
        );
    }

    /** Structural fraction-style cross-divide, mirroring
     *  {@link CliffordElement#div}. */
    @Override
    public CliffordNumber div(CliffordNumber other) {
        return new CliffordEllipticPair(
                top.mult(other.bottom()),
                bottom.mult(other.top())
        );
    }

    /** Component-wise: {@code −(a, b) = (−a, −b)} — additive inverse. */
    @Override
    public CliffordNumber negate() {
        return new CliffordEllipticPair(top.negate(), bottom.negate());
    }

    /** True iff both components are zero. */
    @Override
    public boolean isZero() {
        return top.isZero() && bottom.isZero();
    }

    // ── Capability: Bilinear ─────────────────────────────────────────

    /**
     * Cayley-Dickson bilinear product with δ = −1:
     * {@code (a, b) · (c, d) = (a·c − d̄·b,  d·a + b·c̄)}.
     */
    @Override
    public CliffordNumber composeBilinear(CliffordNumber other) {
        if (!(other instanceof CliffordEllipticPair pair)) {
            throw new CliffordIncompatibleArithmeticException(this, other);
        }
        CliffordNumber a = top, b = bottom;
        CliffordNumber c = pair.top, d = pair.bottom;

        if (!(c instanceof Conjugatable cConj) || !(d instanceof Conjugatable dConj)) {
            throw new IllegalStateException(
                    "composeBilinear requires Conjugatable components on the other pair");
        }
        CliffordNumber cBar = cConj.conjugate();
        CliffordNumber dBar = dConj.conjugate();

        CliffordNumber newTop    = a.mult(c).sub(dBar.mult(b));
        CliffordNumber newBottom = d.mult(a).add(b.mult(cBar));
        return new CliffordEllipticPair(newTop, newBottom);
    }

    // ── Capability: Conjugatable ────────────────────────────────────

    /** Cayley-Dickson conjugation: {@code (a, b) → (ā, −b)}. */
    @Override
    public CliffordNumber conjugate() {
        if (!(top instanceof Conjugatable conjTop)) {
            throw new IllegalStateException(
                    "conjugate requires the top component to be Conjugatable");
        }
        return new CliffordEllipticPair(conjTop.conjugate(), bottom.negate());
    }

    // ── Capability: Invertible ──────────────────────────────────────

    /**
     * Multiplicative inverse for {@link #composeBilinear}:
     * {@code x⁻¹ = x̄ / |x|²} where {@code |x|²} is the top component of
     * {@code x · x̄}. The division is element-wise (each component over the
     * scalar |x|²), not fraction-style. Throws if {@code |x|² = 0} via the
     * underlying {@code div}.
     */
    @Override
    public CliffordNumber inverse() {
        CliffordNumber conj = this.conjugate();
        CliffordNumber product = this.composeBilinear(conj);
        CliffordNumber normSq = product.top();
        return new CliffordEllipticPair(
                conj.top().div(normSq),
                conj.bottom().div(normSq)
        );
    }

    // ── Capability: Symmetric / Antisymmetric ───────────────────────

    /**
     * {@code ½(a·b + b·a)} under {@link #composeBilinear}, scaled
     * component-wise. For level-1 pairs over real scalars this collapses to
     * {@code composeBilinear(other)} (the algebra is commutative); at higher
     * levels it produces the inner / contraction part.
     */
    @Override
    public CliffordNumber symmetric(CliffordNumber other) {
        if (!(other instanceof CliffordEllipticPair otherPair)) {
            throw new CliffordIncompatibleArithmeticException(this, other);
        }
        CliffordNumber ab  = this.composeBilinear(otherPair);
        CliffordNumber ba  = otherPair.composeBilinear(this);
        CliffordNumber sum = ab.add(ba);
        return new CliffordEllipticPair(
                sum.top().div(TWO),
                sum.bottom().div(TWO)
        );
    }

    /**
     * {@code ½(a·b − b·a)} under {@link #composeBilinear}, scaled
     * component-wise. For level-1 pairs over real scalars this is always
     * zero (commutative); at higher levels it produces the wedge /
     * exterior part.
     */
    @Override
    public CliffordNumber antisymmetric(CliffordNumber other) {
        if (!(other instanceof CliffordEllipticPair otherPair)) {
            throw new CliffordIncompatibleArithmeticException(this, other);
        }
        CliffordNumber ab   = this.composeBilinear(otherPair);
        CliffordNumber ba   = otherPair.composeBilinear(this);
        CliffordNumber diff = ab.sub(ba);
        return new CliffordEllipticPair(
                diff.top().div(TWO),
                diff.bottom().div(TWO)
        );
    }

    @Override
    public String toString() {
        return "η(" + top + ", " + bottom + ")";
    }
}
