package spn.traction;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase-valued network substrate — smoke tests on the algebraic
 * TractionQuaternion-valued network.
 *
 * <p>Confirms that {@code factor.network} and {@code factor.train} parse,
 * the quaternion forward pass runs end-to-end, input encoding and output
 * decoding behave, and {@code trainStep} mutates weights. The
 * loss-reduction case is disabled until the bump-activation zero-trap
 * is resolved (see comment on {@link #trainingReducesLoss()}).
 */
class PhaseFactorTest extends TractionTestBase {

    @Test void divFeatureDetectsDivisibility() {
        assertEquals(true, run("""
            import factor.network

            let f_p_is_N = divFeature(6, 6)
            let f_2p     = divFeature(12, 6)
            let f_3p     = divFeature(18, 6)
            let f_coprime = divFeature(7, 6)

            f_p_is_N > 0.99 && f_2p < 0.01
              && f_3p > 0.99
              && f_coprime > 0.0 && f_coprime < 1.0
            """));
    }

    @Test void encodeInputProducesOneQuatPerPrime() {
        assertEquals(true, run("""
            import factor.network

            let primes = IntVec().push(2).push(3).push(5)
            let features = encodeInput(12, primes)
            features.length() == 3
            """));
    }

    @Test void forwardPassRunsEndToEnd() {
        assertEquals(true, run("""
            import factor.network

            let primes = IntVec().push(2).push(3).push(5)
            let net = QNet().pushLayer(initQWeights(3, 3, 0, 42), initQBiases(3))

            let features = encodeInput(12, primes)
            let outputs = net.forward(features)
            outputs.length() == 3
            """));
    }

    @Test void decodeExponentRoundTripsOnTargetPhases() {
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
        assertEquals(true, run("""
            import factor.network
            import numerics.traction

            let q = liftReal(0.3)
            let a = q.activate()
            magnitudeFloat(a) < 0.001
            """));
    }

    @Test void trainingChangesWeights() {
        // trainStep must actually mutate the weights — pins that the
        // algebraic update is running even if the loss doesn't converge
        // for this particular task / activation.
        double beforeW = ((Number) run("""
            import factor.train

            let net = QNet().pushLayer(initQWeights(3, 3, 0, 42), initQBiases(3))
            let (w, x, y, z) = cartesianOf(net.layers[0].weights[0][0])
            w
            """)).doubleValue();
        double afterW = ((Number) run("""
            import factor.train

            let ps = IntVec(2, 3, 5)
            let net0 = QNet().pushLayer(initQWeights(3, 3, 0, 42), initQBiases(3))
            let trained = train(net0, ps, 5, 2, 6, 10, 0.2)
            let (w, x, y, z) = cartesianOf(trained.layers[0].weights[0][0])
            w
            """)).doubleValue();
        assertNotEquals(beforeW, afterW, 1e-9, "weights did not change during training");
    }

    @Test void trainingReducesLoss() {
        double lossBefore = ((Number) run("""
            import factor.train
            let ps = IntVec(2, 3, 5)
            let net0 = QNet().pushLayer(initQWeights(3, 3, 0, 42), initQBiases(3))
            totalLoss(net0, ps, 5, 2, 6)
            """)).doubleValue();
        double lossAfter = ((Number) run("""
            import factor.train
            let ps = IntVec(2, 3, 5)
            let net0 = QNet().pushLayer(initQWeights(3, 3, 0, 42), initQBiases(3))
            let trained = train(net0, ps, 5, 2, 6, 10, 0.2)
            totalLoss(trained, ps, 5, 2, 6)
            """)).doubleValue();
        System.out.println("[trainingReducesLoss] before=" + lossBefore + " after=" + lossAfter);
        assertEquals(true, lossAfter < lossBefore,
                "expected lossAfter (" + lossAfter + ") < lossBefore (" + lossBefore + ")");
    }
}
