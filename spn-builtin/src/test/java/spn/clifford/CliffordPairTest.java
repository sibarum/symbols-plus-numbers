package spn.clifford;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CliffordPair} — the runtime-mode reference implementation
 * intended for NN architectures where a neuron's weight interpretation can
 * shift between the four corners under gate control.
 *
 * <p>Verifies (i) each of the four mode factories reproduces its sealed
 * sibling's defining identity through CliffordPair's defaults, (ii)
 * {@link CliffordPair#withDelta} shifts mode without disturbing the
 * components, (iii) mismatched δ in {@code composeBilinear} throws, and
 * (iv) cross-type composition (CliffordPair vs sealed sibling) throws even
 * when their δs would match.
 */
class CliffordPairTest {

    private static CliffordInteger i(long n) { return new CliffordInteger(n); }

    // ── Mode factories produce the right δ ──────────────────────────

    @Test
    void ellipticFactorySetsDelta() {
        assertEquals(i(-1), CliffordPair.elliptic(i(0), i(1)).delta());
    }

    @Test
    void parabolicFactorySetsDelta() {
        assertEquals(CliffordInteger.ZERO, CliffordPair.parabolic(i(0), i(1)).delta());
    }

    @Test
    void hyperbolicFactorySetsDelta() {
        assertEquals(CliffordInteger.ONE, CliffordPair.hyperbolic(i(0), i(1)).delta());
    }

    @Test
    void tractionFactorySetsDelta() {
        assertEquals(
                new CliffordProjectiveRational(CliffordInteger.ONE, CliffordInteger.ZERO),
                CliffordPair.traction(i(0), i(1)).delta());
    }

    // ── Each mode reproduces its sibling's defining identity ────────

    @Test
    void ellipticUnitSquaresToMinusOne() {
        // (0, 1)² = (-1, 0) in elliptic mode.
        CliffordPair unit = CliffordPair.elliptic(i(0), i(1));
        CliffordPair expected = CliffordPair.elliptic(i(-1), i(0));
        assertEquals(expected, unit.composeBilinear(unit));
    }

    @Test
    void parabolicUnitSquaresToZero() {
        // (0, 1)² = (0, 0) in parabolic mode.
        CliffordPair unit = CliffordPair.parabolic(i(0), i(1));
        CliffordPair expected = CliffordPair.parabolic(i(0), i(0));
        assertEquals(expected, unit.composeBilinear(unit));
    }

    @Test
    void hyperbolicUnitSquaresToOne() {
        // (0, 1)² = (1, 0) in hyperbolic mode.
        CliffordPair unit = CliffordPair.hyperbolic(i(0), i(1));
        CliffordPair expected = CliffordPair.hyperbolic(i(1), i(0));
        assertEquals(expected, unit.composeBilinear(unit));
    }

    @Test
    void tractionUnitSquaresToOmega() {
        // (0, 1)² = (ω, 0) in traction mode.
        CliffordPair unit = CliffordPair.traction(i(0), i(1));
        CliffordElement omega = new CliffordElement(i(1), i(0));
        CliffordPair expected = CliffordPair.traction(omega, i(0));
        assertEquals(expected, unit.composeBilinear(unit));
    }

    // ── Mode shifting via withDelta ─────────────────────────────────

    @Test
    void withDeltaPreservesComponents() {
        CliffordPair before = CliffordPair.elliptic(i(3), i(4));
        CliffordPair after  = before.withDelta(CliffordPair.DELTA_HYPERBOLIC);
        assertEquals(i(3), after.top());
        assertEquals(i(4), after.bottom());
        assertEquals(CliffordPair.DELTA_HYPERBOLIC, after.delta());
    }

    @Test
    void withDeltaShiftsAlgebra() {
        // Same components, different mode → different bilinear product.
        // (0, 1) elliptic squared = (-1, 0); after withDelta to hyperbolic, = (1, 0).
        CliffordPair x = CliffordPair.elliptic(i(0), i(1));
        CliffordPair shifted = x.withDelta(CliffordPair.DELTA_HYPERBOLIC);
        assertEquals(
                CliffordPair.hyperbolic(i(1), i(0)),
                shifted.composeBilinear(shifted));
    }

    // ── δ-mismatch is a hard error ──────────────────────────────────

    @Test
    void mismatchedDeltaThrows() {
        CliffordPair elliptic   = CliffordPair.elliptic(i(0), i(1));
        CliffordPair hyperbolic = CliffordPair.hyperbolic(i(0), i(1));
        assertThrows(CliffordIncompatibleArithmeticException.class,
                () -> elliptic.composeBilinear(hyperbolic));
    }

    @Test
    void mismatchedDeltaInSymmetricThrows() {
        CliffordPair elliptic  = CliffordPair.elliptic(i(0), i(1));
        CliffordPair parabolic = CliffordPair.parabolic(i(0), i(1));
        assertThrows(CliffordIncompatibleArithmeticException.class,
                () -> elliptic.symmetric(parabolic));
    }

    // ── Cross-type composition rejected even when δ matches ────────

    @Test
    void crossTypeWithMatchingDeltaThrows() {
        // CliffordPair (mode=elliptic) and CliffordEllipticPair both have δ=-1
        // but are different concrete classes; getClass() check rejects.
        CliffordPair runtime = CliffordPair.elliptic(i(0), i(1));
        CliffordEllipticPair sealed = new CliffordEllipticPair(i(0), i(1));
        assertThrows(CliffordIncompatibleArithmeticException.class,
                () -> runtime.composeBilinear(sealed));
    }

    // ── NN-style: mode toggled by gate ──────────────────────────────

    @Test
    void gateToggledModeProducesCorrectAlgebra() {
        // Simulate ReLU-like gate: if signal > 0, hyperbolic; else elliptic.
        CliffordNumber sharedTop = i(0);
        CliffordNumber sharedBottom = i(1);

        CliffordPair signalOff = relu(-1.0) > 0
                ? CliffordPair.hyperbolic(sharedTop, sharedBottom)
                : CliffordPair.elliptic(sharedTop, sharedBottom);
        CliffordPair signalOn  = relu( 1.0) > 0
                ? CliffordPair.hyperbolic(sharedTop, sharedBottom)
                : CliffordPair.elliptic(sharedTop, sharedBottom);

        // Off branch is elliptic: unit² = -1.
        assertEquals(CliffordPair.elliptic(i(-1), i(0)),
                signalOff.composeBilinear(signalOff));
        // On branch is hyperbolic: unit² = +1.
        assertEquals(CliffordPair.hyperbolic(i(1), i(0)),
                signalOn.composeBilinear(signalOn));
    }

    private static double relu(double x) { return Math.max(0.0, x); }

    // ── Capability surface ──────────────────────────────────────────

    @Test
    void implementsAllExpectedCapabilities() {
        CliffordPair x = CliffordPair.elliptic(i(0), i(1));
        assertTrue(x instanceof CayleyDicksonPair);
        assertTrue(x instanceof Bilinear);
        assertTrue(x instanceof Conjugatable);
        assertTrue(x instanceof Invertible);
        assertTrue(x instanceof Symmetric);
        assertTrue(x instanceof Antisymmetric);
    }
}
