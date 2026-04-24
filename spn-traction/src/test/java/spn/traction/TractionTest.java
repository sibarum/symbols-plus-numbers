package spn.traction;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge-case contract for {@code TractionComplex} — rotation composition,
 * negation round-trips, and the quarter-turn {@code .i} constant.
 *
 * <p>Each case pins a specific algebraic invariant that would silently
 * fail with the naive tan(a+b) / sign-migrating rational formulation:
 * quadrant correctness on angle doubling, the permissive Traction(0,0)
 * path, omega-tangent handling in {@code .i}, and sign stability across
 * four quarter turns.
 */
class TractionTest extends TractionTestBase {

    /**
     * Quaternion basis setup for the {@link Quaternion} tests.
     *
     * <p>Encoding: a TractionQuaternion {@code (real, imag)} represents the
     * Cayley-Dickson pair {@code real + imag·j}. Each TractionComplex in
     * turn represents a complex number via {@code (scale·tangent.denom,
     * scale·tangent.num)}.
     *
     * <p>Basis quaternions:
     * <pre>
     *   1 = (1, 0)       -- TC representing 1+0i, TC representing 0
     *   i = (i, 0)       -- TC representing 0+1i, TC representing 0
     *   j = (0, 1)       -- TC representing 0,    TC representing 1+0i
     *   k = (0, i)       -- TC representing 0,    TC representing 0+1i
     * </pre>
     *
     * <p>{@code qEq} compares two quaternions by cartesian components of
     * both halves — needed because neither TractionComplex nor
     * TractionQuaternion has an {@code ==} operator defined.
     */
    private static final String
            BASIS_SETUP = """
            import numerics.traction

            let tc0 = TractionComplex(Traction(0,1), Traction(0,1))
            let tc1 = TractionComplex(Traction(1,1), Traction(0,1))
            let tci = TractionComplex.i

            let q_one = TractionQuaternion(tc1, tc0)
            let q_i   = TractionQuaternion(tci, tc0)
            let q_j   = TractionQuaternion(tc0, tc1)
            let q_k   = TractionQuaternion(tc0, tci)

            pure qEq(TractionQuaternion, TractionQuaternion) -> bool = (q1, q2) {
              let r1 = q1.real.cart()
              let i1 = q1.imag.cart()
              let r2 = q2.real.cart()
              let i2 = q2.imag.cart()
              r1.0 == r2.0 && r1.1 == r2.1 && i1.0 == i2.0 && i1.1 == i2.1
            }
            """;

    @Nested
    class RotationComposition {

        @Test void squareOfThreeFourFive() {
            // tc = TractionComplex(3,4,5) sits at (3/5, 4/5), angle θ=atan(4/3).
            // Squaring rotates to 2θ ≈ 106°, which is in Q2 — so cos(2θ) is
            // NEGATIVE. The (7, 24, 25) triple appears normalized to
            // (-7/25, 24/25), not (7/25, 24/25):
            //   cos(2θ) = 1 - 2·sin²θ = 1 - 32/25 = -7/25
            //   sin(2θ) = 2·sinθ·cosθ = 24/25
            // The test pins the quadrant-correct sign — a regression to the
            // naive tangent-only composition would drop that sign.
            assertEquals(true, run("""
                import numerics.traction
                let tc = TractionComplex(3, 4, 5)
                let v = (tc * tc).cart()
                v.0 == Rational(-7, 25) && v.1 == Rational(24, 25)
                """));
        }
    }

    @Nested
    class NegationRoundTrip {

        @Test void addingNegationCartesianIsZero() {
            // tc + tc.neg() produces TractionComplex(Traction(1,25), Traction(0,0)).
            // Traction(0,0) is the permissive path — cart() multiplies it out
            // as (a·0/b, a·0/b), and Rational canonicalizes both (0, 25)
            // legs to (0, 1) = Rational.zero.
            assertEquals(true, run("""
                import numerics.traction
                let tc = TractionComplex(3, 4, 5)
                let v = (tc + tc.neg()).cart()
                v.0 == Rational.zero && v.1 == Rational.zero
                """));
        }
    }

    @Nested
    class QuarterTurn {

        @Test void iRotatesByNinety() {
            // TractionComplex.i = (scale=(1,1), tangent=(1,0)) — the omega-
            // slope representation of +i. Multiplying tc=(3/5, 4/5) by .i
            // should land at (-4/5, 3/5). Exercises the interaction between
            // .i's scale=(1,1) and tc's scale=(1,5), plus the n1·d2/d1·n2
            // terms where d2=0 zeroes out one summand on each side.
            assertEquals(true, run("""
                import numerics.traction
                let tc = TractionComplex(3, 4, 5)
                let v = (tc * TractionComplex.i).cart()
                v.0 == Rational(-4, 5) && v.1 == Rational(3, 5)
                """));
        }

        @Test void fourQuarterTurnsReturnToIdentity() {
            // .i^4 = 1. The tangent should cycle
            //   (1,0) → (0,-1) → (-1,0) → (0,1)
            // ending at (0, 1), which is exactly the default identity
            // TractionComplex(). Scale stays (1,1) throughout. Any sign
            // bug in the *_nwedge / *_ndot formulas would show up as a
            // flipped tangent somewhere in the cycle.
            assertEquals(true, run("""
                import numerics.traction
                let r = TractionComplex.i * TractionComplex.i * TractionComplex.i * TractionComplex.i
                r.scale.num == 1 && r.scale.denom == 1
                    && r.tangent.num == 0 && r.tangent.denom == 1
                """));
        }
    }

    /**
     * Quaternion basis identities, pinning the Cayley-Dickson convention.
     *
     * <p>The multiplication formula in traction.spn is
     * <pre>(a,b)(c,d) = (ac − d̄·b,  d·a + b·c̄)</pre>
     * which is the standard <em>right-handed</em> Cayley-Dickson doubling.
     * The <em>left-handed</em> variant {@code (ac − b·d̄, a·d + c·b̄)} would
     * pass {@code i² = j² = k² = -1} but flip signs on the cyclic products
     * — that's exactly what these tests distinguish.
     */
    @Nested
    class Quaternion {

        @Test void squaresAreMinusOne() {
            // i² = j² = k² = -1. Minimum contract for any quaternion algebra.
            // Passes under both left- and right-handed Cayley-Dickson, so
            // this alone doesn't discriminate — see cyclicProducts below.
            assertEquals(true, run(BASIS_SETUP + """
                    let mone = q_one.neg()
                    qEq(q_i * q_i, mone)
                        && qEq(q_j * q_j, mone)
                        && qEq(q_k * q_k, mone)
                    """));
        }

        @Test void ijkProductIsMinusOne() {
            // Hamilton's defining identity: i·j·k = -1. Together with the
            // squares this nails down the multiplication table up to
            // orientation, catching any sign bug that escapes the squares.
            assertEquals(true, run(BASIS_SETUP + """
                    qEq(q_i * q_j * q_k, q_one.neg())
                    """));
        }

        @Test void cyclicProducts() {
            // i·j = k, j·k = i, k·i = j. Right-handed orientation.
            // A left-handed Cayley-Dickson would give the negatives here —
            // this is the test that actually picks the convention.
            assertEquals(true, run(BASIS_SETUP + """
                    qEq(q_i * q_j, q_k)
                        && qEq(q_j * q_k, q_i)
                        && qEq(q_k * q_i, q_j)
                    """));
        }

        @Test void antiCyclicProducts() {
            // Non-commutativity: reversing cyclic products flips sign.
            // j·i = -k, k·j = -i, i·k = -j.
            assertEquals(true, run(BASIS_SETUP + """
                    qEq(q_j * q_i, q_k.neg())
                        && qEq(q_k * q_j, q_i.neg())
                        && qEq(q_i * q_k, q_j.neg())
                    """));
        }
    }
}
