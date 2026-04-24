package spn.traction;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase-valued network substrate — forward-pass smoke tests.
 *
 * Verifies that {@code factor.network} parses, the TractionQuaternion-valued
 * forward pass runs end-to-end, and input encoding + output decoding produce
 * values in the expected ranges.
 */
class PhaseFactorTest extends TractionTestBase {

    @Test void divFeatureDetectsDivisibility() {
        // max(0, -cos(N·π/p)) hits +1 exactly when N/p is an odd integer
        // (because -cos(k·π) = 1 for odd k). So N = p gives 1; N = 2p
        // gives 0 (clamped from -1); N = 3p gives 1 again.
        assertEquals(true, run("""
            import factor.network

            let f_p_is_N = divFeature(6, 6)          -- 6 = 1·6, odd multiple → 1
            let f_2p     = divFeature(12, 6)         -- 12 = 2·6, even multiple → 0
            let f_3p     = divFeature(18, 6)         -- 18 = 3·6, odd multiple → 1
            let f_coprime = divFeature(7, 6)         -- 7/6 non-integer → in (0, 1)

            f_p_is_N > 0.99 && f_2p < 0.01
              && f_3p > 0.99
              && f_coprime > 0.0 && f_coprime < 1.0
            """));
    }

    @Test void encodeInputProducesOneQuatPerPrime() {
        // encodeInput builds a QuatVec with length = |primes|.
        assertEquals(true, run("""
            import factor.network

            let primes = IntVec().push(2).push(3).push(5)
            let features = encodeInput(12, primes)
            features.length() == 3
            """));
    }

    @Test void forwardPassRunsEndToEnd() {
        // Initialize a 3→3 layer with deterministic seed, forward a sample
        // input, and check the output has the right shape. Any finite
        // activation counts as success — we're smoke-testing the pipeline.
        assertEquals(true, run("""
            import factor.network

            let primes = IntVec().push(2).push(3).push(5)
            let layer = initPLayer(0, 3, 3, 42)
            let net = PNetwork(PLayerArray().push(layer))

            let features = encodeInput(12, primes)
            let outputs = forwardQ(net, features)
            outputs.length() == 3
            """));
    }

    @Test void decodeExponentRoundTripsOnTargetPhases() {
        // Build a target TractionQuaternion at each exponent class, extract
        // its real-half phase via argOf, decode it back. Round-trip should
        // recover the class index.
        assertEquals(true, run("""
            import factor.network

            let maxExp = 5
            let c0 = decodeExponent(argOf(exponentTarget(0, maxExp)), maxExp)
            let c1 = decodeExponent(argOf(exponentTarget(1, maxExp)), maxExp)
            let c2 = decodeExponent(argOf(exponentTarget(2, maxExp)), maxExp)
            let c3 = decodeExponent(argOf(exponentTarget(3, maxExp)), maxExp)
            let c4 = decodeExponent(argOf(exponentTarget(4, maxExp)), maxExp)

            c0 == 0 && c1 == 1 && c2 == 2 && c3 == 3 && c4 == 4
            """));
    }

    @Test void activationSuppressesSubHalfMagnitude() {
        // bump01(x) = 0 for x ∈ [0, 0.5]. So an input quaternion with
        // magnitude 0.3 (all on the real axis) should activate to
        // magnitude 0.
        assertEquals(true, run("""
            import factor.network
            import numerics.traction

            let q = liftReal(0.3)
            let a = activate(q)
            magnitudeFloat(a) < 0.001
            """));
    }

    @Disabled
    @Test void trainingReducesLoss() {
        // Finite-differences SGD should strictly decrease loss on a small
        // training set over a handful of epochs. Range is intentionally
        // small (N ∈ [2, 6), 3 epochs) — each weight contributes 4
        // components instead of 2, so a 3→3 layer runs 8 · 9 = 72 forward
        // passes per example per gradient step.
        assertEquals(true, run("""
            import factor.train

            let ps = IntVec(2, 3, 5)
            let mE = 5
            let net0 = PNetwork(PLayerArray(initPLayer(0, 3, 3, 42)))

            let lossBefore = totalLoss(net0, ps, mE, 2, 6)
            let trained = train(net0, ps, mE, 2, 6, 3, 0.05, 0.2)
            let lossAfter = totalLoss(trained, ps, mE, 2, 6)

            lossAfter < lossBefore
            """));
    }
}
