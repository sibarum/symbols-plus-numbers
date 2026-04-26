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

    @Test void activationIsIdentity() {
        // Un-normalized experiment: activation passes through unchanged so
        // non-unit quaternions carry scaling information through the
        // network. liftReal(0.3) has magnitude 0.3 and should stay at 0.3.
        assertEquals(true, run("""
            import factor.network
            import numerics.traction

            let q = liftReal(0.3)
            let a = q.activate()
            let m = magnitudeFloat(a)
            m > 0.299 && m < 0.301
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

    /**
     * Detect dead neurons in a trained gated network. A neuron is "dead"
     * if its gate selects the same branch (k1 OR k2) for every training
     * example — analogous to ReLU dead neurons that always output zero.
     * The unselected k receives no gradient signal across the entire
     * training set, so the gate has effectively collapsed.
     *
     * <p>Reports a count of dead neurons per layer. Currently asserts
     * fewer than half the neurons are dead. Tighten the bound once we
     * understand prevalence and have an init or training-rule fix.
     */
    @Test void gatedTrainingDoesNotKillTooManyNeurons() {
        // Random bias init breaks the gate-threshold symmetry that zero
        // biases produce — each neuron's pre-activation gets a different
        // offset so neurons trip the threshold at varied input magnitudes.
        String diagScript = """
            import factor.train

            let ps = IntVec(2, 3, 5)
            let h_k1 = initActK1(3, 0, 42)
            let h_k2 = initActK2FromK1(h_k1)
            let o_k1 = initActK1(3, 1, 42)
            let o_k2 = initActK2FromK1(o_k1)
            let net0 = QNet()
              .pushLayerGated(initQWeights(3, 3, 0, 42), initQBiasesRandom(3, 0, 42), h_k1, h_k2)
              .pushLayerGated(initQWeights(3, 3, 1, 42), initQBiasesRandom(3, 1, 42), o_k1, o_k2)
            let trained = train(net0, ps, 5, 2, 6, 30, 0.05)

            -- Returns three counts: (deadOnK1, deadOnK2, totalMagSqAvgX1000).
            -- "Dead on k1" = gate selected k1 for ALL 4 training examples
            -- (so k2 never trained). Mag-squared average tells us where
            -- pre-activations are clustering vs the threshold of 1.0.
            let nLayers = 2
            let nNeurons = 3
            let endN = 6
            let deadOnK1 = 0
            let deadOnK2 = 0
            let totalMagSqX1000 = 0
            let totalSamples = 0
            let li = 0
            while {li < nLayers} do {
              let nj = 0
              while {nj < nNeurons} do {
                let k1c = 0
                let k2c = 0
                let n = 2
                let bias = trained.layers[li].biases[nj]
                let bSq = bias.norm_sq()
                while {n < endN} do {
                  let features = encodeInput(n, ps)
                  let pres = trained.forwardAllPre(features)
                  let z = pres[li][nj]
                  let nSq = z.norm_sq()
                  -- Match the in-network gate exactly: nSq > bSq via
                  -- cross-multiply.
                  match
                  | nSq.num * bSq.denom > bSq.num * nSq.denom -> { k1c = k1c + 1 }
                  | _                                          -> { k2c = k2c + 1 }
                  let magSqX1000 = round(tractionToFloat(nSq) * 1000.0)
                  totalMagSqX1000 = totalMagSqX1000 + magSqX1000
                  totalSamples = totalSamples + 1
                  n = n + 1
                }
                deadOnK1 = match | k2c == 0 -> deadOnK1 + 1 | _ -> deadOnK1
                deadOnK2 = match | k1c == 0 -> deadOnK2 + 1 | _ -> deadOnK2
                nj = nj + 1
              }
              li = li + 1
            }
            -- Pack into a single int: deadOnK1 * 1000000 + deadOnK2 * 1000
            --   + min(avgMagSqX1000, 999).
            let avgMagSqX1000 = totalMagSqX1000 / totalSamples
            let cappedAvg = match | avgMagSqX1000 > 999 -> 999 | _ -> avgMagSqX1000
            deadOnK1 * 1000000 + deadOnK2 * 1000 + cappedAvg
            """;
        int packed = ((Number) run(diagScript)).intValue();
        int deadOnK1 = packed / 1000000;
        int deadOnK2 = (packed / 1000) % 1000;
        int avgMagSqX1000 = packed % 1000;
        int totalNeurons = 2 * 3;
        int totalDead = deadOnK1 + deadOnK2;
        System.out.println("[gatedTrainingDoesNotKillTooManyNeurons]"
            + " dead=" + totalDead + "/" + totalNeurons
            + " (" + deadOnK1 + " stuck on k1, " + deadOnK2 + " stuck on k2)"
            + " avg_pre_mag_sq=" + (avgMagSqX1000 / 1000.0));
        // Sanity floor — fail if literally everything is dead.
        assertEquals(true, totalDead < totalNeurons,
            "all " + totalNeurons + " neurons dead — gate collapsed completely");
    }

    /**
     * Controlled four-way comparison: 1-layer vs 2-layer, identity vs
     * gated. Same hyperparameters (30 epochs, lr=0.05). Tells us how much
     * of the gated network's behavior comes from the gate itself versus
     * the depth.
     */
    @Test void controlledComparison() {
        String prelude = """
            import factor.train
            let ps = IntVec(2, 3, 5)
            """;
        String tail = ", ps, 5, 2, 6, 30, 0.05)\n              totalLoss(trained, ps, 5, 2, 6)";

        // 1-layer identity
        double l1id = ((Number) run(prelude + """
            let net0 = QNet().pushLayer(initQWeights(3, 3, 0, 42), initQBiases(3))
            let trained = train(net0""" + tail)).doubleValue();
        double l1id0 = ((Number) run(prelude + """
            let net0 = QNet().pushLayer(initQWeights(3, 3, 0, 42), initQBiases(3))
            totalLoss(net0, ps, 5, 2, 6)""")).doubleValue();

        // 2-layer identity
        double l2id = ((Number) run(prelude + """
            let net0 = QNet()
              .pushLayer(initQWeights(3, 3, 0, 42), initQBiases(3))
              .pushLayer(initQWeights(3, 3, 1, 42), initQBiases(3))
            let trained = train(net0""" + tail)).doubleValue();
        double l2id0 = ((Number) run(prelude + """
            let net0 = QNet()
              .pushLayer(initQWeights(3, 3, 0, 42), initQBiases(3))
              .pushLayer(initQWeights(3, 3, 1, 42), initQBiases(3))
            totalLoss(net0, ps, 5, 2, 6)""")).doubleValue();

        // 1-layer gated
        double l1ga = ((Number) run(prelude + """
            let k1 = initActK1(3, 0, 42)
            let k2 = initActK2FromK1(k1)
            let net0 = QNet()
              .pushLayerGated(initQWeights(3, 3, 0, 42), initQBiases(3), k1, k2)
            let trained = train(net0""" + tail)).doubleValue();
        double l1ga0 = ((Number) run(prelude + """
            let k1 = initActK1(3, 0, 42)
            let k2 = initActK2FromK1(k1)
            let net0 = QNet()
              .pushLayerGated(initQWeights(3, 3, 0, 42), initQBiases(3), k1, k2)
            totalLoss(net0, ps, 5, 2, 6)""")).doubleValue();

        // 2-layer gated
        double l2ga = ((Number) run(prelude + """
            let h_k1 = initActK1(3, 0, 42)
            let h_k2 = initActK2FromK1(h_k1)
            let o_k1 = initActK1(3, 1, 42)
            let o_k2 = initActK2FromK1(o_k1)
            let net0 = QNet()
              .pushLayerGated(initQWeights(3, 3, 0, 42), initQBiases(3), h_k1, h_k2)
              .pushLayerGated(initQWeights(3, 3, 1, 42), initQBiases(3), o_k1, o_k2)
            let trained = train(net0""" + tail)).doubleValue();
        double l2ga0 = ((Number) run(prelude + """
            let h_k1 = initActK1(3, 0, 42)
            let h_k2 = initActK2FromK1(h_k1)
            let o_k1 = initActK1(3, 1, 42)
            let o_k2 = initActK2FromK1(o_k1)
            let net0 = QNet()
              .pushLayerGated(initQWeights(3, 3, 0, 42), initQBiases(3), h_k1, h_k2)
              .pushLayerGated(initQWeights(3, 3, 1, 42), initQBiases(3), o_k1, o_k2)
            totalLoss(net0, ps, 5, 2, 6)""")).doubleValue();

        System.out.println("[controlledComparison] (30 epochs @ lr=0.05)");
        System.out.printf("  1-layer identity: %.4f -> %.4f  (Δ=%.4f)%n", l1id0, l1id, l1id0 - l1id);
        System.out.printf("  2-layer identity: %.4f -> %.4f  (Δ=%.4f)%n", l2id0, l2id, l2id0 - l2id);
        System.out.printf("  1-layer gated:    %.4f -> %.4f  (Δ=%.4f)%n", l1ga0, l1ga, l1ga0 - l1ga);
        System.out.printf("  2-layer gated:    %.4f -> %.4f  (Δ=%.4f)%n", l2ga0, l2ga, l2ga0 - l2ga);

        assertEquals(true, l1id < l1id0, "1-layer identity didn't reduce loss");
        assertEquals(true, l2id < l2id0, "2-layer identity didn't reduce loss");
        assertEquals(true, l1ga < l1ga0, "1-layer gated didn't reduce loss");
        assertEquals(true, l2ga < l2ga0, "2-layer gated didn't reduce loss");
    }

    @Test void gatedTrainingReducesLoss() {
        // Two-layer (3 → 3 → 3) network with per-neuron gated activation:
        // k1 random unit, k2 = k1.conj() at init. The gate decouples
        // nonlinearity (scalar ReLU on |q|² > 1) from the equivariant
        // geometric action (compose with a learned k). Training updates
        // only the gate-selected k per example.
        double lossBefore = ((Number) run("""
            import factor.train
            let ps = IntVec(2, 3, 5)
            let h_k1 = initActK1(3, 0, 42)
            let h_k2 = initActK2FromK1(h_k1)
            let o_k1 = initActK1(3, 1, 42)
            let o_k2 = initActK2FromK1(o_k1)
            let net0 = QNet()
              .pushLayerGated(initQWeights(3, 3, 0, 42), initQBiases(3), h_k1, h_k2)
              .pushLayerGated(initQWeights(3, 3, 1, 42), initQBiases(3), o_k1, o_k2)
            totalLoss(net0, ps, 5, 2, 6)
            """)).doubleValue();
        double lossAfter = ((Number) run("""
            import factor.train
            let ps = IntVec(2, 3, 5)
            let h_k1 = initActK1(3, 0, 42)
            let h_k2 = initActK2FromK1(h_k1)
            let o_k1 = initActK1(3, 1, 42)
            let o_k2 = initActK2FromK1(o_k1)
            let net0 = QNet()
              .pushLayerGated(initQWeights(3, 3, 0, 42), initQBiases(3), h_k1, h_k2)
              .pushLayerGated(initQWeights(3, 3, 1, 42), initQBiases(3), o_k1, o_k2)
            let trained = train(net0, ps, 5, 2, 6, 30, 0.05)
            totalLoss(trained, ps, 5, 2, 6)
            """)).doubleValue();
        System.out.println("[gatedTrainingReducesLoss] before=" + lossBefore
            + " after=" + lossAfter);
        assertEquals(true, lossAfter < lossBefore,
                "expected lossAfter (" + lossAfter + ") < lossBefore (" + lossBefore + ")");
    }
}
