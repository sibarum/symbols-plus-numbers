package spn.clifford;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Level-2 verification: recurse the Cayley-Dickson construction once more.
 * A {@code CliffordEllipticPair} whose components are themselves
 * {@code CliffordEllipticPair}s should behave like the quaternions ℍ.
 *
 * <p>This is the load-bearing claim of the whole substrate: that the
 * recursion holds, that the same δ = −1 rule applied twice gives Hamilton
 * quaternion arithmetic with the right non-commutativity. If level 2 works,
 * level 3 (octonions) and beyond come for free by the same recursion.
 *
 * <p>Quaternion ↔ pair correspondence used here:
 * <pre>
 *   q = w + x·i + y·j + z·k    ↔    ((w, x), (y, z))
 * </pre>
 * with {@code (1, 0, 0, 0) = scalar 1}, {@code (0, 1, 0, 0) = i},
 * {@code (0, 0, 1, 0) = j}, {@code (0, 0, 0, 1) = k}.
 *
 * <p>Note: {@code symmetric}/{@code antisymmetric} are NOT exercised at
 * level 2 because their {@code /TWO} step uses the structural fraction-style
 * {@code div}, which is not element-wise scaling at the pair level. This is
 * a known design gap — see {@code CliffordEllipticPair} for the comment.
 */
class CliffordEllipticPairLevel2Test {

    private static CliffordInteger i(long n) { return new CliffordInteger(n); }
    private static CliffordEllipticPair p(long t, long b) {
        return new CliffordEllipticPair(i(t), i(b));
    }
    /** Level-2 quaternion: ((w, x), (y, z)) = w + xi + yj + zk. */
    private static CliffordEllipticPair q(long w, long x, long y, long z) {
        return new CliffordEllipticPair(p(w, x), p(y, z));
    }

    private static final CliffordEllipticPair ONE_Q  = q(1, 0, 0, 0);
    private static final CliffordEllipticPair I_Q    = q(0, 1, 0, 0);
    private static final CliffordEllipticPair J_Q    = q(0, 0, 1, 0);
    private static final CliffordEllipticPair K_Q    = q(0, 0, 0, 1);
    private static final CliffordEllipticPair NEG_ONE_Q = q(-1, 0, 0, 0);
    private static final CliffordEllipticPair NEG_I_Q   = q(0, -1, 0, 0);
    private static final CliffordEllipticPair NEG_J_Q   = q(0, 0, -1, 0);
    private static final CliffordEllipticPair NEG_K_Q   = q(0, 0, 0, -1);

    // ── Construction & shape ─────────────────────────────────────────

    @Test
    void quaternionStructureNestsTwoLevels() {
        CliffordEllipticPair quat = q(1, 2, 3, 4);
        // Top is the level-1 (1, 2); bottom is the level-1 (3, 4).
        assertEquals(p(1, 2), quat.top());
        assertEquals(p(3, 4), quat.bottom());
    }

    // ── Each unit squares to −1 ──────────────────────────────────────

    @Test
    void iSquaredIsMinusOne() {
        assertEquals(NEG_ONE_Q, I_Q.composeBilinear(I_Q));
    }

    @Test
    void jSquaredIsMinusOne() {
        assertEquals(NEG_ONE_Q, J_Q.composeBilinear(J_Q));
    }

    @Test
    void kSquaredIsMinusOne() {
        assertEquals(NEG_ONE_Q, K_Q.composeBilinear(K_Q));
    }

    // ── Cyclic identities: ij = k, jk = i, ki = j ────────────────────

    @Test
    void iTimesJEqualsK() {
        assertEquals(K_Q, I_Q.composeBilinear(J_Q));
    }

    @Test
    void jTimesKEqualsI() {
        assertEquals(I_Q, J_Q.composeBilinear(K_Q));
    }

    @Test
    void kTimesIEqualsJ() {
        assertEquals(J_Q, K_Q.composeBilinear(I_Q));
    }

    // ── Anti-cyclic: ji = −k, kj = −i, ik = −j ───────────────────────

    @Test
    void jTimesIEqualsMinusK() {
        assertEquals(NEG_K_Q, J_Q.composeBilinear(I_Q));
    }

    @Test
    void kTimesJEqualsMinusI() {
        assertEquals(NEG_I_Q, K_Q.composeBilinear(J_Q));
    }

    @Test
    void iTimesKEqualsMinusJ() {
        assertEquals(NEG_J_Q, I_Q.composeBilinear(K_Q));
    }

    // ── Non-commutativity is real at level 2 ────────────────────────

    @Test
    void quaternionMultiplicationIsNotCommutative() {
        // ij vs ji: the load-bearing distinction between ℂ and ℍ.
        assertNotEquals(
                I_Q.composeBilinear(J_Q),
                J_Q.composeBilinear(I_Q));
    }

    // ── Hamilton's defining identity: i·j·k = −1 ─────────────────────

    @Test
    void ijkEqualsMinusOne() {
        // Compute (i·j)·k under composeBilinear.
        CliffordNumber ij = I_Q.composeBilinear(J_Q);
        Bilinear ijB = (Bilinear) ij;
        CliffordNumber ijk = ijB.composeBilinear(K_Q);
        assertEquals(NEG_ONE_Q, ijk);
    }

    // ── Scalar 1 is multiplicative identity at level 2 ──────────────

    @Test
    void oneIsLeftAndRightIdentity() {
        CliffordEllipticPair x = q(3, 5, 7, 11);
        assertEquals(x, ONE_Q.composeBilinear(x));
        assertEquals(x, x.composeBilinear(ONE_Q));
    }

    // ── A specific Hamilton product ─────────────────────────────────

    @Test
    void hamiltonProductSpecificCase() {
        // (1 + 2i + 3j + 4k) · (2 + 3i + 4j + 5k)
        // Hamilton formula gives:
        //   scalar: 1·2 - 2·3 - 3·4 - 4·5 = -36
        //   i:      1·3 + 2·2 + 3·5 - 4·4 = 6
        //   j:      1·4 - 2·5 + 3·2 + 4·3 = 12
        //   k:      1·5 + 2·4 - 3·3 + 4·2 = 12
        CliffordEllipticPair a = q(1, 2, 3, 4);
        CliffordEllipticPair b = q(2, 3, 4, 5);
        assertEquals(q(-36, 6, 12, 12), a.composeBilinear(b));
    }

    // ── Conjugation flips i, j, k components ────────────────────────

    @Test
    void conjugateFlipsImaginaryParts() {
        // conj(w + xi + yj + zk) = w − xi − yj − zk
        CliffordEllipticPair x = q(1, 2, 3, 4);
        assertEquals(q(1, -2, -3, -4), x.conjugate());
    }

    @Test
    void conjugateOfRealQuaternionIsItself() {
        assertEquals(q(7, 0, 0, 0), q(7, 0, 0, 0).conjugate());
    }

    // ── Norm² and the x · x̄ = (|x|², 0) identity at level 2 ─────────

    @Test
    void xTimesConjugateIsNormSquared() {
        // |1 + 2i + 3j + 4k|² = 1 + 4 + 9 + 16 = 30
        CliffordEllipticPair x = q(1, 2, 3, 4);
        CliffordEllipticPair xConj = (CliffordEllipticPair) x.conjugate();
        // The product top is the level-1 (|x|², 0) — which itself is the
        // scalar |x|² at top, zero in its bottom slot.
        assertEquals(q(30, 0, 0, 0), x.composeBilinear(xConj));
    }

    // ── Inverse round-trip ──────────────────────────────────────────

    @Test
    void inverseStructureForNonUnitQuaternion() {
        // (1 + 2i + 3j + 4k)⁻¹ = conj/|x|² = (1, -2, -3, -4) / 30.
        // Each component is exactly representable as a rational with
        // denominator 30. The full round-trip x · x⁻¹ would equal 1
        // mathematically, but the substrate keeps non-canonical
        // representations (e.g. 30⁴/30⁴), so this test pins down the
        // inverse's exact structure instead — which round-trip identity
        // would imply if canonicalization were applied.
        CliffordInteger normSq = i(30);
        CliffordProjectiveRational q1 = new CliffordProjectiveRational(i(1),  normSq);
        CliffordProjectiveRational q2 = new CliffordProjectiveRational(i(-2), normSq);
        CliffordProjectiveRational q3 = new CliffordProjectiveRational(i(-3), normSq);
        CliffordProjectiveRational q4 = new CliffordProjectiveRational(i(-4), normSq);

        CliffordEllipticPair inv = (CliffordEllipticPair) q(1, 2, 3, 4).inverse();
        CliffordEllipticPair invTop    = (CliffordEllipticPair) inv.top();
        CliffordEllipticPair invBottom = (CliffordEllipticPair) inv.bottom();

        assertEquals(q1, invTop.top());
        assertEquals(q2, invTop.bottom());
        assertEquals(q3, invBottom.top());
        assertEquals(q4, invBottom.bottom());
    }

    @Test
    void inverseOfUnitQuaternionElement() {
        // i⁻¹ = -i (since |i|² = 1 and conj(i) = -i)
        assertEquals(NEG_I_Q, I_Q.inverse());
        assertEquals(NEG_J_Q, J_Q.inverse());
        assertEquals(NEG_K_Q, K_Q.inverse());
    }
}
