package spn.traction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NeuralNetworkTest extends TractionTestBase {

    @Test void networkLoadsAndSingleLayerRuns() {
        // Minimal 1-in → 1-out network. Identity weight, zero bias,
        // sigmoid applied. For x=0, σ(0) = 0.5 exactly.
        assertEquals(true, run("""
            import nn.network

            let w0 = Vector().push(1.0)
            let ws = Matrix().push(w0)
            let b  = Vector().push(0.0)
            let layer = Layer(ws, b)
            let net = Network(LayerArray().push(layer))

            let out = forward(net, Vector().push(0.0))
            -- σ(0) = 0.5
            out[0] > 0.499 && out[0] < 0.501
            """));
    }

    @Test void trainingReducesLossOnSinglePair() {
        // Train a tiny 1→2→1 network on one input/target pair for many
        // steps. Loss after training should be strictly less than loss
        // before training — sanity check on forward+backward+update.
        assertEquals(true, run("""
            import nn.network

            -- Layer 1: 1 → 2 with arbitrary-ish init
            let l1w0 = Vector().push(0.3)
            let l1w1 = Vector().push(-0.7)
            let l1w = Matrix().push(l1w0).push(l1w1)
            let l1b = Vector().push(0.1).push(-0.2)
            let layer1 = Layer(l1w, l1b)

            -- Layer 2: 2 → 1
            let l2w0 = Vector().push(0.5).push(-0.4)
            let l2w = Matrix().push(l2w0)
            let l2b = Vector().push(0.0)
            let layer2 = Layer(l2w, l2b)

            let net0 = Network(LayerArray().push(layer1).push(layer2))

            let input = Vector().push(0.5)
            let target = Vector().push(0.9)

            let lossBefore = mse(forward(net0, input), target)

            -- 50 training steps
            let net = net0
            let i = 0
            while {i < 50} do {
              net = trainStep(net, input, target, 1.0)
              i = i + 1
            }
            let lossAfter = mse(forward(net, input), target)

            lossAfter < lossBefore && lossAfter < 0.001
            """));
    }

    @Test void twoLayerNetworkProducesInRange() {
        // 2 → 3 → 1. Any weights produce an output in (0, 1) after sigmoid.
        assertEquals(true, run("""
            import nn.network

            -- Layer 1: 2 → 3
            let l1_w0 = Vector().push(1.0).push(-1.0)
            let l1_w1 = Vector().push(0.5).push(0.5)
            let l1_w2 = Vector().push(-1.0).push(1.0)
            let l1w  = Matrix().push(l1_w0).push(l1_w1).push(l1_w2)
            let l1_b  = Vector().push(0.0).push(0.0).push(0.0)
            let layer1 = Layer(l1w, l1_b)

            -- Layer 2: 3 → 1
            let l2_w0 = Vector().push(1.0).push(1.0).push(1.0)
            let l2w  = Matrix().push(l2_w0)
            let l2_b  = Vector().push(-0.5)
            let layer2 = Layer(l2w, l2_b)

            let net = Network(LayerArray().push(layer1).push(layer2))
            let input = Vector().push(0.7).push(0.3)
            let out = forward(net, input)
            out[0] > 0.0 && out[0] < 1.0
            """));
    }

    @Test void demoTrainingLoopReducesLoss() {
        // Mirrors the demo's training loop shape: cycles through several
        // (a, b, (a+b)/18) examples chosen to span the full target range
        // and trains a 2→5→1 net. Confirms that multi-example SGD drives
        // the loss down meaningfully. Canvas imports live in demo.spn and
        // can't run here; this test covers the underlying training only.
        assertEquals(true, run("""
            import nn.network
            import Math (toFloat)

            -- Examples spanning the full [0.11, 1.0] target range.
            let n = 8

            let in0 = Vector().push(0.1).push(0.1)   let t0 = Vector().push(0.111)
            let in1 = Vector().push(0.9).push(0.9)   let t1 = Vector().push(1.0)
            let in2 = Vector().push(0.1).push(0.8)   let t2 = Vector().push(0.5)
            let in3 = Vector().push(0.9).push(0.1)   let t3 = Vector().push(0.556)
            let in4 = Vector().push(0.3).push(0.2)   let t4 = Vector().push(0.278)
            let in5 = Vector().push(0.6).push(0.8)   let t5 = Vector().push(0.778)
            let in6 = Vector().push(0.2).push(0.5)   let t6 = Vector().push(0.389)
            let in7 = Vector().push(0.8).push(0.4)   let t7 = Vector().push(0.667)

            let ins = Matrix().push(in0).push(in1).push(in2).push(in3)
                              .push(in4).push(in5).push(in6).push(in7)
            let ts  = Matrix().push(t0).push(t1).push(t2).push(t3)
                              .push(t4).push(t5).push(t6).push(t7)

            -- 2 → 5 → 1 with small hand-picked weights
            let l1w0 = Vector().push(0.2).push(-0.3)
            let l1w1 = Vector().push(-0.1).push(0.15)
            let l1w2 = Vector().push(0.25).push(0.05)
            let l1w3 = Vector().push(-0.2).push(-0.1)
            let l1w4 = Vector().push(0.1).push(0.3)
            let l1w  = Matrix().push(l1w0).push(l1w1).push(l1w2).push(l1w3).push(l1w4)
            let l1b  = Vector().push(0.0).push(0.05).push(-0.05).push(0.0).push(0.02)
            let layer1 = Layer(l1w, l1b)

            let l2w0 = Vector().push(0.2).push(-0.15).push(0.25).push(0.1).push(-0.2)
            let l2w = Matrix().push(l2w0)
            let l2b = Vector().push(0.0)
            let layer2 = Layer(l2w, l2b)

            let net0 = Network(LayerArray().push(layer1).push(layer2))

            let lossBefore = 0.0
            let k = 0
            while {k < n} do {
              lossBefore = lossBefore + mse(forward(net0, ins[k]), ts[k])
              k = k + 1
            }
            lossBefore = lossBefore / toFloat(n)

            -- 1500 steps cycling through examples
            let net = net0
            let s = 0
            while {s < 1500} do {
              let idx = s % n
              net = trainStep(net, ins[idx], ts[idx], 1.5)
              s = s + 1
            }

            let lossAfter = 0.0
            let k2 = 0
            while {k2 < n} do {
              lossAfter = lossAfter + mse(forward(net, ins[k2]), ts[k2])
              k2 = k2 + 1
            }
            lossAfter = lossAfter / toFloat(n)

            lossAfter < lossBefore * 0.3
            """));
    }
}
