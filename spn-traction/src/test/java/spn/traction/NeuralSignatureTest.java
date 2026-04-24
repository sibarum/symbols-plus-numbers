package spn.traction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests for the NeuralWeight signature + TractionQuaternion binding.
 *
 * <p>Verifies the dispatch machinery before the full macro arrives:
 * each signature method (compose, invert, aggregate, activate, error,
 * interp) dispatches to the correct TractionQuaternion implementation,
 * and {@code requires NeuralWeight} accepts TractionQuaternion at
 * macro-expansion time.
 */
class NeuralSignatureTest extends TractionTestBase {

    @Test void composeDispatchesToHamiltonProduct() {
        // q.compose(r) should equal q * r. Test on i · j = k.
        assertEquals(true, run("""
            import factor.neural_traction
            import numerics.traction

            let tc0 = TractionComplex(Traction(0,1), Traction(0,1))
            let tc1 = TractionComplex(Traction(1,1), Traction(0,1))
            let tci = TractionComplex.i

            let q_i = TractionQuaternion(tci, tc0)
            let q_j = TractionQuaternion(tc0, tc1)
            let q_k = TractionQuaternion(tc0, tci)

            let result = q_i.compose(q_j)
            let r1 = result.real.cart()
            let i1 = result.imag.cart()
            let k1 = q_k.real.cart()
            let k2 = q_k.imag.cart()
            r1.0 == k1.0 && r1.1 == k1.1 && i1.0 == k2.0 && i1.1 == k2.1
            """));
    }

    @Test void errorProducesCorrectionElement() {
        // a.compose(a.error(b)) == b, algebraically.
        // With renormalization rounding, compare via cart approximations.
        assertEquals(true, run("""
            import factor.neural_traction
            import numerics.traction

            let tc0 = TractionComplex(Traction(0,1), Traction(0,1))
            let tc1 = TractionComplex(Traction(1,1), Traction(0,1))

            let a = TractionQuaternion(tc1, tc0)
            let b = quatFromCart4(0.6, 0.8, 0.0, 0.0)

            let err = a.error(b)
            let reconstructed = a.compose(err)
            let (rw, rx, ry, rz) = cartesianOf(reconstructed)
            let (bw, bx, by, bz) = cartesianOf(b)
            let dw = rw - bw
            let dx = rx - bx
            let absdw = match | dw < 0.0 -> -dw | _ -> dw
            let absdx = match | dx < 0.0 -> -dx | _ -> dx
            absdw < 0.01 && absdx < 0.01
            """));
    }

    @Test void interpMovesFractionallyTowardTarget() {
        // interp(a, b, 0) ≈ a, interp(a, b, 1) ≈ b, interp(a, b, 0.5) at midpoint.
        assertEquals(true, run("""
            import factor.neural_traction

            let a = quatFromCart4(1.0, 0.0, 0.0, 0.0)
            let b = quatFromCart4(0.0, 1.0, 0.0, 0.0)

            let mid = a.interp(b, 0.5)
            let (mw, mx, my, mz) = cartesianOf(mid)
            let dw = mw - 0.5
            let dx = mx - 0.5
            let absdw = match | dw < 0.0 -> -dw | _ -> dw
            let absdx = match | dx < 0.0 -> -dx | _ -> dx
            absdw < 0.01 && absdx < 0.01
            """));
    }

    @Test void activateProjectsToUnitSphere() {
        // The activation normalizes to |q|=1 regardless of input magnitude
        // (phase-valued task). The old bump-with-dead-zone design zeroed
        // sub-0.5 magnitudes and created a training zero-trap.
        assertEquals(true, run("""
            import factor.neural_traction

            let q = quatFromCart4(0.3, 0.0, 0.0, 0.0)
            let a = q.activate()
            let m = magnitudeFloat(a)
            m > 0.999 && m < 1.001
            """));
    }

    @Test void composeErrorChainWithoutMacro() {
        // Same call chain as the macro test but written inline. Isolates
        // whether the failure is in macro expansion or in the call chain.
        assertEquals(true, run("""
            import factor.neural_traction
            import numerics.traction

            let q = quatFromCart4(0.6, 0.8, 0.0, 0.0)
            let back = q.compose(q.error(q))
            let (bw, bx, by, bz) = cartesianOf(back)
            let (qw, qx, qy, qz) = cartesianOf(q)
            let dw = bw - qw
            let absdw = match | dw < 0.0 -> -dw | _ -> dw
            absdw < 0.01
            """));
    }

    @Test void requiresNeuralWeightAcceptsQuaternion() {
        // Declare a trivial macro requiring NeuralWeight, expand with
        // TractionQuaternion, and exercise it. Pins that the signature
        // dispatch resolves all six methods on the quaternion type.
        assertEquals(true, run("""
            import factor.neural
            import factor.neural_traction
            import numerics.traction

            macro identity_check<T requires NeuralWeight> = {
              pure echo(T) -> T = (q) { q.compose(q.error(q)) }
            }
            identity_check<TractionQuaternion>

            let q = quatFromCart4(0.6, 0.8, 0.0, 0.0)
            let back = echo(q)
            let (bw, bx, by, bz) = cartesianOf(back)
            let (qw, qx, qy, qz) = cartesianOf(q)
            let dw = bw - qw
            let absdw = match | dw < 0.0 -> -dw | _ -> dw
            absdw < 0.01
            """));
    }
}
