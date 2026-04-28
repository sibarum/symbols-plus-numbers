package spn.clifford;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Random;

/**
 * Stacked-architecture experiments. Two ε+ring layers with a ReLU
 * nonlinearity between them: enough capacity (10 params) to fit some
 * genuinely nonlinear targets, far short of "deep learning" scale but
 * enough to validate that the substrate composes through nonlinearities.
 *
 * <p>Per-layer forward:
 * {@code layer_output = ε.composeBilinear(input) + ring.mult(input)}.
 *
 * <p>Net:
 * <pre>
 *   x → layer_1 → ReLU → layer_2 → output
 * </pre>
 *
 * <p>Each layer has 5 params: 2 ε params + 3 ring params (a, b, s).
 * Total: 10 params.
 */
class CliffordStackedNeuralNetExperiment {

    // ── Single-layer ε+ring forward (no shared state) ───────────────

    private record EpsilonRingLayer(
            double cdT, double cdB,
            double rA, double rB, double s,
            double biasA, double biasB
    ) {
        double[] forward(double x, double y) {
            double a = cdT * x + rA * x - rB * y + biasA;
            double b = cdT * y + cdB * x + rA * y + rB * x + rB * y * s + biasB;
            return new double[] { a, b };
        }
    }

    /** Leaky ReLU with slope 0.1 for negatives — preserves gradient flow
     *  through the activation, important for our small-capacity network. */
    private static double leakyRelu(double v) {
        return v > 0 ? v : 0.1 * v;
    }

    private record StackedNet(EpsilonRingLayer l1, EpsilonRingLayer l2) {
        double[] forward(double x, double y) {
            double[] h = l1.forward(x, y);
            double h0 = leakyRelu(h[0]);
            double h1 = leakyRelu(h[1]);
            return l2.forward(h0, h1);
        }
    }

    // ── Loss / SGD via finite differences over 10 params ────────────

    private static double mse(StackedNet net, double[][] inputs, double[][] targets) {
        double total = 0.0;
        int finite = 0;
        for (int i = 0; i < inputs.length; i++) {
            double[] pred = net.forward(inputs[i][0], inputs[i][1]);
            double dx = pred[0] - targets[i][0];
            double dy = pred[1] - targets[i][1];
            double sq = dx * dx + dy * dy;
            if (Double.isFinite(sq)) { total += sq; finite++; }
        }
        if (finite == 0) return 1e12;
        return total / finite;
    }

    /** Pack/unpack 14 params (7 per layer including biases). */
    private static double[] pack(StackedNet n) {
        return new double[] {
                n.l1.cdT, n.l1.cdB, n.l1.rA, n.l1.rB, n.l1.s, n.l1.biasA, n.l1.biasB,
                n.l2.cdT, n.l2.cdB, n.l2.rA, n.l2.rB, n.l2.s, n.l2.biasA, n.l2.biasB,
        };
    }
    private static StackedNet unpack(double[] p) {
        return new StackedNet(
                new EpsilonRingLayer(p[0], p[1], p[2], p[3], p[4], p[5], p[6]),
                new EpsilonRingLayer(p[7], p[8], p[9], p[10], p[11], p[12], p[13])
        );
    }

    private static StackedNet sgdStep(StackedNet net, double[][] inputs, double[][] targets,
                                       double lr, double eps) {
        double[] p = pack(net);
        double[] grad = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            double[] pp = p.clone(); pp[i] += eps;
            double[] pm = p.clone(); pm[i] -= eps;
            double g = (mse(unpack(pp), inputs, targets) - mse(unpack(pm), inputs, targets)) / (2 * eps);
            grad[i] = Math.max(-1e3, Math.min(1e3, g));
        }
        for (int i = 0; i < p.length; i++) p[i] -= lr * grad[i];
        return unpack(p);
    }

    // ── Multi-restart training ───────────────────────────────────────

    private record TrainResult(StackedNet net, double finalLoss, double initLoss) {}

    private static StackedNet randomNet(Random rng) {
        return new StackedNet(
                new EpsilonRingLayer(
                        rng.nextGaussian() * 0.5, rng.nextGaussian() * 0.5,
                        rng.nextGaussian() * 0.5, rng.nextGaussian() * 0.5,
                        (rng.nextDouble() * 4 - 2),
                        rng.nextGaussian() * 0.3, rng.nextGaussian() * 0.3),
                new EpsilonRingLayer(
                        rng.nextGaussian() * 0.5, rng.nextGaussian() * 0.5,
                        rng.nextGaussian() * 0.5, rng.nextGaussian() * 0.5,
                        (rng.nextDouble() * 4 - 2),
                        rng.nextGaussian() * 0.3, rng.nextGaussian() * 0.3)
        );
    }

    private static TrainResult train(double[][] inputs, double[][] targets, int epochs, double lr) {
        Random rng = new Random(0xC1ff0d);
        StackedNet best = randomNet(rng);
        double bestLoss = mse(best, inputs, targets);
        double initLoss = bestLoss;

        int restarts = 8;
        for (int r = 0; r < restarts; r++) {
            StackedNet w = randomNet(rng);
            for (int e = 0; e < epochs; e++) w = sgdStep(w, inputs, targets, lr, 1e-4);
            // Refinement at lower lr
            for (int e = 0; e < epochs / 2; e++) w = sgdStep(w, inputs, targets, lr / 10, 1e-5);
            double l = mse(w, inputs, targets);
            if (Double.isFinite(l) && l < bestLoss) { bestLoss = l; best = w; }
        }
        return new TrainResult(best, bestLoss, initLoss);
    }

    private static void runAndPrint(String name, double[][] inputs, double[][] targets,
                                     int epochs, double lr) {
        long t0 = System.currentTimeMillis();
        TrainResult r = train(inputs, targets, epochs, lr);
        long elapsed = System.currentTimeMillis() - t0;
        System.out.println();
        System.out.println("── Stacked ε+ring + ReLU + ε+ring: " + name + " ──");
        System.out.printf(Locale.ROOT, "  init loss  : %.4f%n", r.initLoss);
        System.out.printf(Locale.ROOT, "  final loss : %.6f%n", r.finalLoss);
        System.out.printf(Locale.ROOT, "  layer1 (cdT, cdB, rA, rB, s, bA, bB) = (%+.3f, %+.3f, %+.3f, %+.3f, %+.3f, %+.3f, %+.3f)%n",
                r.net.l1.cdT, r.net.l1.cdB, r.net.l1.rA, r.net.l1.rB, r.net.l1.s, r.net.l1.biasA, r.net.l1.biasB);
        System.out.printf(Locale.ROOT, "  layer2 (cdT, cdB, rA, rB, s, bA, bB) = (%+.3f, %+.3f, %+.3f, %+.3f, %+.3f, %+.3f, %+.3f)%n",
                r.net.l2.cdT, r.net.l2.cdB, r.net.l2.rA, r.net.l2.rB, r.net.l2.s, r.net.l2.biasA, r.net.l2.biasB);
        System.out.printf(Locale.ROOT, "  trained in %d ms%n", elapsed);
    }

    // ── Nonlinear tasks ──────────────────────────────────────────────

    @Test
    void absoluteValueTask() {
        // target = (|x|, |y|).  Single linear layer can't fit. ReLU
        // identities can: |x| = ReLU(x) + ReLU(-x). Two layers + ReLU
        // should approximate.
        Random rng = new Random(42);
        int N = 300;
        double[][] in = new double[N][2], tgt = new double[N][2];
        for (int i = 0; i < N; i++) {
            in[i][0] = rng.nextGaussian();
            in[i][1] = rng.nextGaussian();
            tgt[i][0] = Math.abs(in[i][0]);
            tgt[i][1] = Math.abs(in[i][1]);
        }
        runAndPrint("target = (|x|, |y|)", in, tgt, 1200, 0.05);
    }

    @Test
    void relUTask() {
        // target = (ReLU(x), ReLU(y)). Should be the EASIEST case — a
        // 2-layer ReLU model is overkill. Confirms the stack works.
        Random rng = new Random(42);
        int N = 300;
        double[][] in = new double[N][2], tgt = new double[N][2];
        for (int i = 0; i < N; i++) {
            in[i][0] = rng.nextGaussian();
            in[i][1] = rng.nextGaussian();
            tgt[i][0] = Math.max(0, in[i][0]);
            tgt[i][1] = Math.max(0, in[i][1]);
        }
        runAndPrint("target = (ReLU(x), ReLU(y)) [trivial]", in, tgt, 1200, 0.05);
    }

    @Test
    void squaringTask() {
        // target = (x², y²). Polynomial nonlinearity. ReLU networks need
        // width to approximate this — 2-unit hidden layer might struggle.
        Random rng = new Random(42);
        int N = 300;
        double[][] in = new double[N][2], tgt = new double[N][2];
        for (int i = 0; i < N; i++) {
            in[i][0] = rng.nextGaussian();
            in[i][1] = rng.nextGaussian();
            tgt[i][0] = in[i][0] * in[i][0];
            tgt[i][1] = in[i][1] * in[i][1];
        }
        runAndPrint("target = (x², y²) [hard for 2-unit hidden]", in, tgt, 1200, 0.05);
    }

    @Test
    void linearSanityCheck() {
        // Linear target — single-layer ε+ring already fits this. The 2-layer
        // model is over-parameterized and should converge effortlessly.
        // If it DOESN'T, the stacked optimizer is broken.
        Random rng = new Random(42);
        int N = 300;
        double[][] in = new double[N][2], tgt = new double[N][2];
        double[][] M = { { 1.0, 2.0 }, { 3.0, -1.0 } };
        for (int i = 0; i < N; i++) {
            in[i][0] = rng.nextGaussian();
            in[i][1] = rng.nextGaussian();
            tgt[i][0] = M[0][0] * in[i][0] + M[0][1] * in[i][1];
            tgt[i][1] = M[1][0] * in[i][0] + M[1][1] * in[i][1];
        }
        runAndPrint("target = M·input  (sanity, should fit)", in, tgt, 1200, 0.05);
    }
}
