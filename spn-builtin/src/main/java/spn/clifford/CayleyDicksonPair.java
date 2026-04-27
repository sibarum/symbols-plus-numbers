package spn.clifford;

/**
 * Sealed unification of the four Cayley-Dickson pair siblings — elliptic
 * (δ = −1), parabolic (δ = 0), hyperbolic (δ = +1), and traction (δ = ω).
 * Each sibling differs only in its δ parameter; everything else (component-
 * wise arithmetic, the bilinear formula, conjugation, inverse, symmetric /
 * antisymmetric decomposition) is structurally identical and lives here as
 * default methods.
 *
 * <p>Each implementing record provides only:
 * <ul>
 *   <li>The record components {@code (top, bottom)} (which auto-satisfy the
 *       {@link CliffordNumber#top()} / {@link CliffordNumber#bottom()}
 *       accessors).</li>
 *   <li>An override of {@link Bilinear#delta()} returning its δ value.</li>
 *   <li>An override of {@link #factory} that constructs an instance of its
 *       own concrete type — needed so the default methods can build results
 *       of the correct sibling type without knowing what it is.</li>
 *   <li>A {@code toString} prefix for debugging.</li>
 * </ul>
 *
 * <p>Cross-sibling composition (e.g. an elliptic pair times a hyperbolic
 * pair) is rejected at runtime via {@link #getClass()} — the four siblings
 * live in disjoint algebras at any given level.
 */
public sealed interface CayleyDicksonPair
        extends CliffordNumber, Bilinear, Conjugatable, Invertible, Symmetric, Antisymmetric
        permits CliffordEllipticPair, CliffordParabolicPair,
                CliffordHyperbolicPair, CliffordTractionPair, CliffordPair {

    /** Reusable {@code 2} for the {@code ½} factor in symmetric / antisymmetric. */
    CliffordInteger TWO = new CliffordInteger(2);

    /**
     * Construct a new instance of the same concrete sibling type with the
     * given components. Each record provides this so the default methods
     * can build results without knowing which sibling they're operating on.
     */
    CayleyDicksonPair factory(CliffordNumber top, CliffordNumber bottom);

    // ── Universal CliffordNumber arithmetic — component-wise ────────

    @Override
    default CliffordNumber add(CliffordNumber other) {
        return factory(top().add(other.top()), bottom().add(other.bottom()));
    }

    @Override
    default CliffordNumber sub(CliffordNumber other) {
        return factory(top().sub(other.top()), bottom().sub(other.bottom()));
    }

    @Override
    default CliffordNumber mult(CliffordNumber other) {
        return factory(top().mult(other.top()), bottom().mult(other.bottom()));
    }

    @Override
    default CliffordNumber div(CliffordNumber other) {
        return factory(top().mult(other.bottom()), bottom().mult(other.top()));
    }

    @Override
    default CliffordNumber negate() {
        return factory(top().negate(), bottom().negate());
    }

    @Override
    default boolean isZero() {
        return top().isZero() && bottom().isZero();
    }

    @Override
    default CliffordNumber divLeaves(CliffordNumber scalar) {
        return factory(top().divLeaves(scalar), bottom().divLeaves(scalar));
    }

    @Override
    default CliffordNumber multLeaves(CliffordNumber scalar) {
        return factory(top().multLeaves(scalar), bottom().multLeaves(scalar));
    }

    // ── Bilinear: the unified Cayley-Dickson formula ────────────────

    /**
     * Cayley-Dickson bilinear product, parameterized by δ:
     * <pre>
     *   (a, b) · (c, d) = (a·c + δ·(d̄·b),  d·a + b·c̄)
     * </pre>
     * The {@code δ·} factor is applied via {@link #multLeaves} on the
     * {@code d̄·b} term — which collapses to subtraction at δ = −1, vanishes
     * at δ = 0, is the identity at δ = +1, and materializes ω-valued data
     * at δ = ω. The other operand must be the same concrete sibling.
     */
    @Override
    default CliffordNumber composeBilinear(CliffordNumber other) {
        if (!getClass().isInstance(other)) {
            throw new CliffordIncompatibleArithmeticException(this, other);
        }
        CayleyDicksonPair pair = (CayleyDicksonPair) other;
        // For sealed siblings this delta-check is redundant (class implies
        // delta); for the runtime-variable CliffordPair it's load-bearing —
        // two CliffordPairs with different modes must not silently compose
        // under one of their deltas.
        if (!delta().equals(pair.delta())) {
            throw new CliffordIncompatibleArithmeticException(this, other);
        }
        CliffordNumber a = top(), b = bottom();
        CliffordNumber c = pair.top(), d = pair.bottom();

        if (!(c instanceof Conjugatable cConj) || !(d instanceof Conjugatable dConj)) {
            throw new IllegalStateException(
                    "composeBilinear requires Conjugatable components on the other pair");
        }
        CliffordNumber cBar = cConj.conjugate();
        CliffordNumber dBar = dConj.conjugate();

        CliffordNumber dotProduct = product(dBar, b);
        CliffordNumber newTop    = product(a, c).add(dotProduct.multLeaves(delta()));
        CliffordNumber newBottom = product(d, a).add(product(b, cBar));
        return factory(newTop, newBottom);
    }

    /** Recursive level-aware product: bilinear if Bilinear, else structural mult. */
    private static CliffordNumber product(CliffordNumber x, CliffordNumber y) {
        if (x instanceof Bilinear xBil) {
            return xBil.composeBilinear(y);
        }
        return x.mult(y);
    }

    // ── Conjugatable ────────────────────────────────────────────────

    /** Cayley-Dickson conjugation: {@code (a, b) → (ā, −b)}. */
    @Override
    default CliffordNumber conjugate() {
        if (!(top() instanceof Conjugatable conjTop)) {
            throw new IllegalStateException(
                    "conjugate requires the top component to be Conjugatable");
        }
        return factory(conjTop.conjugate(), bottom().negate());
    }

    // ── Invertible ──────────────────────────────────────────────────

    /**
     * {@code x⁻¹ = x̄ / |x|²}. {@code |x|²} is recovered as the deepest
     * scalar leaf of {@code x · x̄}; element-wise scaling of the conjugate
     * by that leaf yields the inverse at any level of the tower.
     */
    @Override
    default CliffordNumber inverse() {
        CliffordNumber conj = this.conjugate();
        CliffordNumber product = this.composeBilinear(conj);
        CliffordNumber normSq = leafScalar(product);
        return conj.divLeaves(normSq);
    }

    /** Walk {@code top} through nested Bilinear pairs to the underlying scalar leaf. */
    private static CliffordNumber leafScalar(CliffordNumber x) {
        while (x instanceof Bilinear b) {
            x = b.top();
        }
        return x;
    }

    // ── Symmetric / Antisymmetric ───────────────────────────────────

    /** {@code ½(a·b + b·a)} under {@link #composeBilinear}, scaled element-wise. */
    @Override
    default CliffordNumber symmetric(CliffordNumber other) {
        // composeBilinear handles the class + delta gates; reuse it.
        CliffordNumber ab = this.composeBilinear(other);
        CliffordNumber ba = ((Bilinear) other).composeBilinear(this);
        return ab.add(ba).divLeaves(TWO);
    }

    /** {@code ½(a·b − b·a)} under {@link #composeBilinear}, scaled element-wise. */
    @Override
    default CliffordNumber antisymmetric(CliffordNumber other) {
        CliffordNumber ab = this.composeBilinear(other);
        CliffordNumber ba = ((Bilinear) other).composeBilinear(this);
        return ab.sub(ba).divLeaves(TWO);
    }
}
