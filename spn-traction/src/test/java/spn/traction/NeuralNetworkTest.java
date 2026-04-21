package spn.traction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import spn.lang.ClasspathModuleLoader;
import spn.lang.FilesystemModuleLoader;
import spn.lang.SpnParser;
import spn.language.SpnModuleRegistry;
import spn.node.SpnRootNode;
import spn.type.SpnSymbolTable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class NeuralNetworkTest {

    private SpnSymbolTable symbolTable;
    private SpnModuleRegistry registry;

    @BeforeEach
    void setUp() {
        symbolTable = new SpnSymbolTable();
        registry = new SpnModuleRegistry();
        spn.stdlib.gen.StdlibModuleLoader.registerAll(registry);
        registry.addLoader(new ClasspathModuleLoader(null, symbolTable));
        Path root = Path.of(System.getProperty("user.dir"));
        if (!Files.isRegularFile(root.resolve("module.spn")))
            root = root.resolve("spn-traction");
        registry.addLoader(new FilesystemModuleLoader(
                root, "sibarum.spn.traction", null, symbolTable));
    }

    private Object run(String source) {
        SpnParser parser = new SpnParser(source, null, null, symbolTable, registry);
        SpnRootNode root = parser.parse();
        return root.getCallTarget().call();
    }

    @Test void networkLoadsAndSingleLayerRuns() {
        // Minimal 1-in → 1-out network. Identity weight, zero bias,
        // sigmoid applied. For x=0, σ(0) = 0.5 exactly.
        assertEquals(true, run("""
            import nn.network
            import Array (append)

            let w0 = append([], 1.0)               -- [1.0]
            let ws = append([], w0)                -- [[1.0]]
            let b  = append([], 0.0)               -- [0.0]
            let layer = Layer(ws, b)
            let net = Network(append([], layer))

            let out = forward(net, append([], 0.0))
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
            import Array (append)

            -- Layer 1: 1 → 2 with arbitrary-ish init
            let l1w0 = append([], 0.3)
            let l1w1 = append([], -0.7)
            let l1w = append(append([], l1w0), l1w1)
            let l1b = append(append([], 0.1), -0.2)
            let layer1 = Layer(l1w, l1b)

            -- Layer 2: 2 → 1
            let l2w0 = append(append([], 0.5), -0.4)
            let l2w = append([], l2w0)
            let l2b = append([], 0.0)
            let layer2 = Layer(l2w, l2b)

            let net0 = Network(append(append([], layer1), layer2))

            let input = append([], 0.5)
            let target = append([], 0.9)

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
            import Array (append)

            -- Layer 1: 2 → 3
            let l1_w0 = append(append([], 1.0), -1.0)
            let l1_w1 = append(append([], 0.5),  0.5)
            let l1_w2 = append(append([], -1.0), 1.0)
            let l1w  = append(append(append([], l1_w0), l1_w1), l1_w2)
            let l1_b  = append(append(append([], 0.0), 0.0), 0.0)
            let layer1 = Layer(l1w, l1_b)

            -- Layer 2: 3 → 1
            let l2_w0 = append(append(append([], 1.0), 1.0), 1.0)
            let l2w  = append([], l2_w0)
            let l2_b  = append([], -0.5)
            let layer2 = Layer(l2w, l2_b)

            let net = Network(append(append([], layer1), layer2))
            let input = append(append([], 0.7), 0.3)
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
            import Array (append)
            import Math (toFloat)

            -- Examples spanning the full [0.11, 1.0] target range.
            let n = 8

            let in0 = append(append([], 0.1), 0.1)   let t0 = append([], 0.111)
            let in1 = append(append([], 0.9), 0.9)   let t1 = append([], 1.0)
            let in2 = append(append([], 0.1), 0.8)   let t2 = append([], 0.5)
            let in3 = append(append([], 0.9), 0.1)   let t3 = append([], 0.556)
            let in4 = append(append([], 0.3), 0.2)   let t4 = append([], 0.278)
            let in5 = append(append([], 0.6), 0.8)   let t5 = append([], 0.778)
            let in6 = append(append([], 0.2), 0.5)   let t6 = append([], 0.389)
            let in7 = append(append([], 0.8), 0.4)   let t7 = append([], 0.667)

            let ins = append(append(append(append(append(append(append(append([],
                in0), in1), in2), in3), in4), in5), in6), in7)
            let ts  = append(append(append(append(append(append(append(append([],
                t0), t1), t2), t3), t4), t5), t6), t7)

            -- 2 → 5 → 1 with small hand-picked weights
            let l1w0 = append(append([], 0.2), -0.3)
            let l1w1 = append(append([], -0.1), 0.15)
            let l1w2 = append(append([], 0.25), 0.05)
            let l1w3 = append(append([], -0.2), -0.1)
            let l1w4 = append(append([], 0.1), 0.3)
            let l1w  = append(append(append(append(append([],
                l1w0), l1w1), l1w2), l1w3), l1w4)
            let l1b  = append(append(append(append(append([],
                0.0), 0.05), -0.05), 0.0), 0.02)
            let layer1 = Layer(l1w, l1b)

            let l2w0 = append(append(append(append(append([],
                0.2), -0.15), 0.25), 0.1), -0.2)
            let l2w = append([], l2w0)
            let l2b = append([], 0.0)
            let layer2 = Layer(l2w, l2b)

            let net0 = Network(append(append([], layer1), layer2))

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
