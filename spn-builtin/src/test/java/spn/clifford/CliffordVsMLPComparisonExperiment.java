package spn.clifford;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Random;

/**
 * Apples-to-apples: plain 2-layer MLP vs stacked ε+ring on the same
 * nonlinear tasks, with matched parameter counts, identical activation,
 * identical training procedure (finite-diff SGD, 8 multi-restarts,
 * refinement stage at lr/10).
 *
 * <p>Architectures (2D in → 2D out):
 * <ul>
 *   <li><b>Stacked ε+ring</b>: 14 params total
 *       (cdT, cdB, rA, rB, s, biasA, biasB per layer × 2 layers; 2D hidden)</li>
 *   <li><b>MLP H=2</b>: 12 params (2×2 + 2 + 2×2 + 2; 2D hidden — same width)</li>
 *   <li><b>MLP H=3</b>: 17 params (slightly over ε+ring, 50% wider hidden)</li>
 *   <li><b>MLP H=4</b>: 22 params (significantly over)</li>
 * </ul>
 *
 * <p>If ε+ring with 14 params beats MLP-H2 (12), that's noise level.
 * If it beats MLP-H3 (17) consistently, ε+ring is a real architectural
 * win. If MLP-H4 beats ε+ring, plain wider is just better — the algebra
 * gives nothing.
 */
class CliffordVsMLPComparisonExperiment {

    private static double leakyRelu(double v) {
        return v > 0 ? v : 0.1 * v;
    }

    // ── Plain MLP architecture ───────────────────────────────────────

    /** Generic two-layer MLP with hidden width H. Stored as flat double arrays. */
    private record MLP(int hiddenH, double[] params) {

        /** Layer-1 weight matrix (H × 2). */
        double w1(int i, int j) { return params[i * 2 + j]; }
        /** Layer-1 bias (length H). */
        double b1(int i) { return params[hiddenH * 2 + i]; }
        /** Layer-2 weight matrix (2 × H). */
        double w2(int i, int j) { return params[hiddenH * 2 + hiddenH + i * hiddenH + j]; }
        /** Layer-2 bias (length 2). */
        double b2(int i) { return params[hiddenH * 2 + hiddenH + 2 * hiddenH + i]; }

        static int paramCount(int H) {
            return H * 2 + H + 2 * H + 2;  // = 5H + 2
        }

        double[] forward(double x, double y) {
            double[] h = new double[hiddenH];
            for (int i = 0; i < hiddenH; i++) {
                h[i] = leakyRelu(w1(i, 0) * x + w1(i, 1) * y + b1(i));
            }
            double[] out = new double[2];
            for (int i = 0; i < 2; i++) {
                double sum = b2(i);
                for (int j = 0; j < hiddenH; j++) sum += w2(i, j) * h[j];
                out[i] = sum;
            }
            return out;
        }
    }

    private static double mseMLP(MLP m, double[][] inputs, double[][] targets) {
        double total = 0.0;
        int finite = 0;
        for (int i = 0; i < inputs.length; i++) {
            double[] pred = m.forward(inputs[i][0], inputs[i][1]);
            double dx = pred[0] - targets[i][0];
            double dy = pred[1] - targets[i][1];
            double sq = dx * dx + dy * dy;
            if (Double.isFinite(sq)) { total += sq; finite++; }
        }
        if (finite == 0) return 1e12;
        return total / finite;
    }

    private static MLP sgdStepMLP(MLP m, double[][] inputs, double[][] targets,
                                   double lr, double eps) {
        double[] p = m.params.clone();
        double[] grad = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            double[] pp = p.clone(); pp[i] += eps;
            double[] pm = p.clone(); pm[i] -= eps;
            double g = (mseMLP(new MLP(m.hiddenH, pp), inputs, targets)
                     - mseMLP(new MLP(m.hiddenH, pm), inputs, targets)) / (2 * eps);
            grad[i] = Math.max(-1e3, Math.min(1e3, g));
        }
        for (int i = 0; i < p.length; i++) p[i] -= lr * grad[i];
        return new MLP(m.hiddenH, p);
    }

    private static MLP randomMLP(int H, Random rng) {
        double[] p = new double[MLP.paramCount(H)];
        for (int i = 0; i < p.length; i++) p[i] = rng.nextGaussian() * 0.5;
        return new MLP(H, p);
    }

    private static double trainMLP(int H, double[][] inputs, double[][] targets,
                                    int epochs, double lr) {
        Random rng = new Random(0xC1ff0d);
        MLP best = randomMLP(H, rng);
        double bestLoss = mseMLP(best, inputs, targets);
        int restarts = 8;
        for (int r = 0; r < restarts; r++) {
            MLP m = randomMLP(H, rng);
            for (int e = 0; e < epochs; e++) m = sgdStepMLP(m, inputs, targets, lr, 1e-4);
            for (int e = 0; e < epochs / 2; e++) m = sgdStepMLP(m, inputs, targets, lr / 10, 1e-5);
            double l = mseMLP(m, inputs, targets);
            if (Double.isFinite(l) && l < bestLoss) { bestLoss = l; best = m; }
        }
        return bestLoss;
    }

    // ── Stacked ε+ring (copy of the architecture from CliffordStackedNeuralNetExperiment) ──

    private record EpsilonRingLayer(
            double cdT, double cdB, double rA, double rB, double s,
            double biasA, double biasB
    ) {
        double[] forward(double x, double y) {
            double a = cdT * x + rA * x - rB * y + biasA;
            double b = cdT * y + cdB * x + rA * y + rB * x + rB * y * s + biasB;
            return new double[] { a, b };
        }
    }

    private record StackedEpsilonRing(EpsilonRingLayer l1, EpsilonRingLayer l2) {
        double[] forward(double x, double y) {
            double[] h = l1.forward(x, y);
            return l2.forward(leakyRelu(h[0]), leakyRelu(h[1]));
        }
    }

    private static double mseStacked(StackedEpsilonRing n,
                                      double[][] inputs, double[][] targets) {
        double total = 0.0;
        int finite = 0;
        for (int i = 0; i < inputs.length; i++) {
            double[] pred = n.forward(inputs[i][0], inputs[i][1]);
            double dx = pred[0] - targets[i][0];
            double dy = pred[1] - targets[i][1];
            double sq = dx * dx + dy * dy;
            if (Double.isFinite(sq)) { total += sq; finite++; }
        }
        if (finite == 0) return 1e12;
        return total / finite;
    }

    private static double[] packStacked(StackedEpsilonRing n) {
        return new double[] {
                n.l1.cdT, n.l1.cdB, n.l1.rA, n.l1.rB, n.l1.s, n.l1.biasA, n.l1.biasB,
                n.l2.cdT, n.l2.cdB, n.l2.rA, n.l2.rB, n.l2.s, n.l2.biasA, n.l2.biasB,
        };
    }
    private static StackedEpsilonRing unpackStacked(double[] p) {
        return new StackedEpsilonRing(
                new EpsilonRingLayer(p[0], p[1], p[2], p[3], p[4], p[5], p[6]),
                new EpsilonRingLayer(p[7], p[8], p[9], p[10], p[11], p[12], p[13])
        );
    }
    private static StackedEpsilonRing sgdStepStacked(StackedEpsilonRing n,
                                                      double[][] inputs, double[][] targets,
                                                      double lr, double eps) {
        double[] p = packStacked(n);
        double[] grad = new double[p.length];
        for (int i = 0; i < p.length; i++) {
            double[] pp = p.clone(); pp[i] += eps;
            double[] pm = p.clone(); pm[i] -= eps;
            double g = (mseStacked(unpackStacked(pp), inputs, targets)
                     - mseStacked(unpackStacked(pm), inputs, targets)) / (2 * eps);
            grad[i] = Math.max(-1e3, Math.min(1e3, g));
        }
        for (int i = 0; i < p.length; i++) p[i] -= lr * grad[i];
        return unpackStacked(p);
    }

    private static StackedEpsilonRing randomStacked(Random rng) {
        return new StackedEpsilonRing(
                new EpsilonRingLayer(rng.nextGaussian() * 0.5, rng.nextGaussian() * 0.5,
                        rng.nextGaussian() * 0.5, rng.nextGaussian() * 0.5,
                        (rng.nextDouble() * 4 - 2),
                        rng.nextGaussian() * 0.3, rng.nextGaussian() * 0.3),
                new EpsilonRingLayer(rng.nextGaussian() * 0.5, rng.nextGaussian() * 0.5,
                        rng.nextGaussian() * 0.5, rng.nextGaussian() * 0.5,
                        (rng.nextDouble() * 4 - 2),
                        rng.nextGaussian() * 0.3, rng.nextGaussian() * 0.3)
        );
    }

    private static double trainStacked(double[][] inputs, double[][] targets,
                                        int epochs, double lr) {
        Random rng = new Random(0xC1ff0d);
        StackedEpsilonRing best = randomStacked(rng);
        double bestLoss = mseStacked(best, inputs, targets);
        int restarts = 8;
        for (int r = 0; r < restarts; r++) {
            StackedEpsilonRing m = randomStacked(rng);
            for (int e = 0; e < epochs; e++) m = sgdStepStacked(m, inputs, targets, lr, 1e-4);
            for (int e = 0; e < epochs / 2; e++) m = sgdStepStacked(m, inputs, targets, lr / 10, 1e-5);
            double l = mseStacked(m, inputs, targets);
            if (Double.isFinite(l) && l < bestLoss) { bestLoss = l; best = m; }
        }
        return bestLoss;
    }

    // ── Comparison runner ───────────────────────────────────────────

    private static void runComparison(String taskName, double[][] inputs, double[][] targets,
                                       int epochs, double lr) {
        long t0 = System.currentTimeMillis();
        double lossEpsilonRing = trainStacked(inputs, targets, epochs, lr);
        long tEpsilonRing = System.currentTimeMillis() - t0;

        t0 = System.currentTimeMillis();
        double lossMLP2 = trainMLP(2, inputs, targets, epochs, lr);
        long tMLP2 = System.currentTimeMillis() - t0;

        t0 = System.currentTimeMillis();
        double lossMLP3 = trainMLP(3, inputs, targets, epochs, lr);
        long tMLP3 = System.currentTimeMillis() - t0;

        t0 = System.currentTimeMillis();
        double lossMLP4 = trainMLP(4, inputs, targets, epochs, lr);
        long tMLP4 = System.currentTimeMillis() - t0;

        System.out.println();
        System.out.println("── " + taskName + " ──");
        System.out.printf(Locale.ROOT, "  %-22s  %4d params  loss = %12.6f  (%5d ms)%n",
                "stacked ε+ring", 14, lossEpsilonRing, tEpsilonRing);
        System.out.printf(Locale.ROOT, "  %-22s  %4d params  loss = %12.6f  (%5d ms)%n",
                "MLP H=2",  MLP.paramCount(2), lossMLP2, tMLP2);
        System.out.printf(Locale.ROOT, "  %-22s  %4d params  loss = %12.6f  (%5d ms)%n",
                "MLP H=3",  MLP.paramCount(3), lossMLP3, tMLP3);
        System.out.printf(Locale.ROOT, "  %-22s  %4d params  loss = %12.6f  (%5d ms)%n",
                "MLP H=4",  MLP.paramCount(4), lossMLP4, tMLP4);
    }

    // ── Tasks ────────────────────────────────────────────────────────

    @Test
    void compare_linearTask() {
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
        runComparison("Linear M·input  (linear baseline)", in, tgt, 1200, 0.05);
    }

    @Test
    void compare_reluTask() {
        Random rng = new Random(42);
        int N = 300;
        double[][] in = new double[N][2], tgt = new double[N][2];
        for (int i = 0; i < N; i++) {
            in[i][0] = rng.nextGaussian();
            in[i][1] = rng.nextGaussian();
            tgt[i][0] = Math.max(0, in[i][0]);
            tgt[i][1] = Math.max(0, in[i][1]);
        }
        runComparison("(ReLU(x), ReLU(y))  (matches the activation)", in, tgt, 1200, 0.05);
    }

    @Test
    void compare_absTask() {
        Random rng = new Random(42);
        int N = 300;
        double[][] in = new double[N][2], tgt = new double[N][2];
        for (int i = 0; i < N; i++) {
            in[i][0] = rng.nextGaussian();
            in[i][1] = rng.nextGaussian();
            tgt[i][0] = Math.abs(in[i][0]);
            tgt[i][1] = Math.abs(in[i][1]);
        }
        runComparison("(|x|, |y|)  (needs 2 ReLUs per output dim)", in, tgt, 1200, 0.05);
    }

    @Test
    void compare_squareTask() {
        Random rng = new Random(42);
        int N = 300;
        double[][] in = new double[N][2], tgt = new double[N][2];
        for (int i = 0; i < N; i++) {
            in[i][0] = rng.nextGaussian();
            in[i][1] = rng.nextGaussian();
            tgt[i][0] = in[i][0] * in[i][0];
            tgt[i][1] = in[i][1] * in[i][1];
        }
        runComparison("(x², y²)  (quadratic, hard for narrow nets)", in, tgt, 1200, 0.05);
    }
}
