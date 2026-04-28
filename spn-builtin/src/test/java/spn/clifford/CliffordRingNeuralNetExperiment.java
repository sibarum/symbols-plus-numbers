package spn.clifford;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Random;

/**
 * NN experiment using {@link CliffordRingElement} — the Q[s][g] / (g² − sg + 1)
 * algebra where {@code s} is a learnable parameter alongside {@code (a, b)}.
 *
 * <p>The hypothesis under test: each geometric task should pull {@code s}
 * toward a specific signature region.
 * <ul>
 *   <li>{@code |s| < 2} (elliptic) — rotation-flavored tasks</li>
 *   <li>{@code |s| ≈ 2} (parabolic) — shear-flavored tasks</li>
 *   <li>{@code |s| > 2} (hyperbolic) — boost-flavored tasks</li>
 * </ul>
 *
 * <p>The discriminant flips sign at {@code |s| = 2}, so SGD across that
 * boundary is genuinely a phase transition — gradient descent may avoid it
 * or get stuck on one side. That's part of what we're measuring.
 *
 * <p>Single-neuron model: {@code output = weight.mult(input)} where input
 * is {@code (x, y)} embedded as {@code x + y·g} with the weight's current
 * {@code s}.
 */
class CliffordRingNeuralNetExperiment {

    // ── Forward / loss ──────────────────────────────────────────────

    private static double[] forward(CliffordRingElement w, double x, double y) {
        CliffordRingElement input = new CliffordRingElement(x, y, w.s());
        CliffordRingElement out = w.mult(input);
        return new double[] { out.a(), out.b() };
    }

    private static double mse(CliffordRingElement w, double[][] inputs, double[][] targets) {
        double total = 0.0;
        int finite = 0;
        for (int i = 0; i < inputs.length; i++) {
            double[] pred = forward(w, inputs[i][0], inputs[i][1]);
            double dx = pred[0] - targets[i][0];
            double dy = pred[1] - targets[i][1];
            double sq = dx * dx + dy * dy;
            if (Double.isFinite(sq)) {
                total += sq;
                finite++;
            }
        }
        if (finite == 0) return 1e12;
        return total / finite;
    }

    // ── Finite-diff SGD on (a, b, s) ─────────────────────────────────

    private static CliffordRingElement sgdStep(CliffordRingElement w,
                                               double[][] inputs, double[][] targets,
                                               double lrAB, double lrS, double eps) {
        double a = w.a(), b = w.b(), s = w.s();

        double dA = (mse(new CliffordRingElement(a + eps, b, s), inputs, targets)
                  -  mse(new CliffordRingElement(a - eps, b, s), inputs, targets)) / (2 * eps);
        double dB = (mse(new CliffordRingElement(a, b + eps, s), inputs, targets)
                  -  mse(new CliffordRingElement(a, b - eps, s), inputs, targets)) / (2 * eps);
        double dS = (mse(new CliffordRingElement(a, b, s + eps), inputs, targets)
                  -  mse(new CliffordRingElement(a, b, s - eps), inputs, targets)) / (2 * eps);

        // Mild gradient clipping for stability near the |s| = 2 phase transition.
        dA = clip(dA);
        dB = clip(dB);
        dS = clip(dS);

        return new CliffordRingElement(a - lrAB * dA, b - lrAB * dB, s - lrS * dS);
    }

    private static double clip(double g) {
        return Math.max(-1e3, Math.min(1e3, g));
    }

    // ── Multi-restart training ───────────────────────────────────────

    private static CliffordRingElement trainOnce(double a0, double b0, double s0,
                                                  double[][] inputs, double[][] targets,
                                                  int epochs, double lrAB, double lrS) {
        CliffordRingElement w = new CliffordRingElement(a0, b0, s0);
        for (int epoch = 0; epoch < epochs; epoch++) {
            w = sgdStep(w, inputs, targets, lrAB, lrS, 1e-4);
        }
        return w;
    }

    private record TrainSummary(CliffordRingElement bestWeight, double bestLoss,
                                double initLoss, double[] sTrajectory) {}

    private static TrainSummary train(double[][] inputs, double[][] targets,
                                       int epochs, double lrAB, double lrS) {
        return train(inputs, targets, epochs, lrAB, lrS,
                new double[] { -3.0, -1.0, 0.5, 1.0, 3.0 });
    }

    private static TrainSummary train(double[][] inputs, double[][] targets,
                                       int epochs, double lrAB, double lrS,
                                       double[] sStarts) {
        Random rng = new Random(0xC1ff0d);
        // Initial restart at canonical (0.5, 0.5, 0).
        double initLoss = mse(new CliffordRingElement(0.5, 0.5, 0.0), inputs, targets);
        CliffordRingElement best = trainOnce(0.5, 0.5, 0.0, inputs, targets, epochs, lrAB, lrS);
        double bestLoss = mse(best, inputs, targets);

        for (double s0 : sStarts) {
            double a0 = (rng.nextDouble() * 2 - 1);
            double b0 = (rng.nextDouble() * 2 - 1);
            CliffordRingElement cand = trainOnce(a0, b0, s0, inputs, targets, epochs, lrAB, lrS);
            double candLoss = mse(cand, inputs, targets);
            if (Double.isFinite(candLoss) && candLoss < bestLoss) {
                bestLoss = candLoss;
                best = cand;
            }
        }
        return new TrainSummary(best, bestLoss, initLoss, new double[0]);
    }

    /** Run a task and ALSO report what each starting-s region converged to,
     *  not just the best. Useful for probing the |s| = 2 phase transition. */
    private static void runAndPrintPerStart(String taskName, double[][] inputs, double[][] targets,
                                             double[] sStarts) {
        System.out.println();
        System.out.println("── Task (per-start probe): " + taskName + " ──");
        Random rng = new Random(0xC1ff0d);
        for (double s0 : sStarts) {
            double a0 = (rng.nextDouble() * 2 - 1);
            double b0 = (rng.nextDouble() * 2 - 1);
            CliffordRingElement w = trainOnce(a0, b0, s0, inputs, targets, 800, 0.05, 0.01);
            double l = mse(w, inputs, targets);
            System.out.printf(Locale.ROOT,
                    "  init s = %+5.1f  →  final s = %+8.4f, loss = %10.6f, region = %s%n",
                    s0, w.s(), l, w.signatureRegion());
        }
    }

    private static void runAndPrint(String taskName, double[][] inputs, double[][] targets) {
        TrainSummary s = train(inputs, targets, 800, 0.05, 0.01);
        System.out.println();
        System.out.println("── Task: " + taskName + " ──");
        System.out.printf(Locale.ROOT,
                "  init loss  : %.4f%n", s.initLoss);
        System.out.printf(Locale.ROOT,
                "  final loss : %.6f%n", s.bestLoss);
        System.out.printf(Locale.ROOT,
                "  weight     : a = %+.4f,  b = %+.4f,  s = %+.4f  (%s)%n",
                s.bestWeight.a(), s.bestWeight.b(), s.bestWeight.s(),
                s.bestWeight.signatureRegion());
    }

    // ── Experiments ──────────────────────────────────────────────────

    /**
     * Tasks of the form {@code target = (−y, x + c·y)} are fit EXACTLY by
     * weight {@code (a, b) = (0, 1)} at {@code s = c}, since
     *   newA = 0·x − 1·y = −y
     *   newB = 0·y + 1·x + 1·y·s = x + s·y
     * So this is the natural probe of the s-dial: the target's c value
     * should pull SGD's learned s toward c.
     */
    private static void buildSPullTask(double c, double[][] in, double[][] tgt, Random rng) {
        for (int i = 0; i < in.length; i++) {
            in[i][0] = rng.nextGaussian();
            in[i][1] = rng.nextGaussian();
            tgt[i][0] = -in[i][1];
            tgt[i][1] = in[i][0] + c * in[i][1];
        }
    }

    @Test
    void sPullElliptic_c1() {
        // c = 1: target fittable at s = 1, |s| < 2 (elliptic interior).
        Random rng = new Random(42);
        int N = 200;
        double[][] in = new double[N][2], tgt = new double[N][2];
        buildSPullTask(1.0, in, tgt, rng);
        runAndPrint("s-pull c=1 (predict s → +1, elliptic)", in, tgt);
    }

    @Test
    void sPullEllipticNegative() {
        // c = -1.5: target fittable at s = -1.5 (elliptic interior, negative).
        Random rng = new Random(42);
        int N = 200;
        double[][] in = new double[N][2], tgt = new double[N][2];
        buildSPullTask(-1.5, in, tgt, rng);
        runAndPrint("s-pull c=-1.5 (predict s → -1.5, elliptic)", in, tgt);
    }

    @Test
    void sPullParabolicEdge() {
        // c = 2: target fittable at s = 2, the |s| = 2 phase transition.
        // SGD may approach the boundary or stick on one side.
        Random rng = new Random(42);
        int N = 200;
        double[][] in = new double[N][2], tgt = new double[N][2];
        buildSPullTask(2.0, in, tgt, rng);
        runAndPrint("s-pull c=2 (predict s → +2, parabolic edge)", in, tgt);
    }

    @Test
    void sPullParabolicEdgePerStart() {
        // Probe the |s|=2 phase transition from BOTH sides.
        // Init s at -5, -3, +1, +3, +5 — does SGD descend to s=2 from above?
        // From below? Both? Neither?
        Random rng = new Random(42);
        int N = 200;
        double[][] in = new double[N][2], tgt = new double[N][2];
        buildSPullTask(2.0, in, tgt, rng);
        runAndPrintPerStart("c=2 (boundary attractor probe)", in, tgt,
                new double[] { -5.0, -3.0, +1.0, +3.0, +5.0 });
    }

    @Test
    void sPullHyperbolicInterior() {
        // c = 3: target fittable at s = 3, deep in hyperbolic regime.
        // Tests whether SGD will cross |s| = 2 to find the right minimum.
        Random rng = new Random(42);
        int N = 200;
        double[][] in = new double[N][2], tgt = new double[N][2];
        buildSPullTask(3.0, in, tgt, rng);
        runAndPrint("s-pull c=3 (predict s → +3, hyperbolic)", in, tgt);
    }

    @Test
    void rotationTask() {
        // (cos θ · x − sin θ · y,  sin θ · x + cos θ · y)  by π/4.
        // Q[s][g] at s = 0 is exactly elliptic; perfect fit at s ≈ 0.
        Random rng = new Random(42);
        int N = 200;
        double[][] in = new double[N][2], tgt = new double[N][2];
        double cos = Math.cos(Math.PI / 4), sin = Math.sin(Math.PI / 4);
        for (int i = 0; i < N; i++) {
            in[i][0] = rng.nextGaussian();
            in[i][1] = rng.nextGaussian();
            tgt[i][0] = cos * in[i][0] - sin * in[i][1];
            tgt[i][1] = sin * in[i][0] + cos * in[i][1];
        }
        runAndPrint("2D rotation by π/4 (predict s → 0)", in, tgt);
    }

    @Test
    void shearTask() {
        // y' = y + α·x. The bilinear has b·s coupling — interesting to see
        // what s value lets the weight (a, b) reproduce a shear best.
        Random rng = new Random(42);
        int N = 200;
        double[][] in = new double[N][2], tgt = new double[N][2];
        for (int i = 0; i < N; i++) {
            in[i][0] = rng.nextGaussian();
            in[i][1] = rng.nextGaussian();
            tgt[i][0] = in[i][0];
            tgt[i][1] = in[i][1] + 0.5 * in[i][0];
        }
        runAndPrint("2D shear y' = y + 0.5x (predict s → ±2)", in, tgt);
    }

    @Test
    void lorentzBoostTask() {
        // a·x + b·y, b·x + a·y with a² − b² = 1.
        // Q[s][g] at hyperbolic s should approximate this best.
        Random rng = new Random(42);
        int N = 200;
        double[][] in = new double[N][2], tgt = new double[N][2];
        double a = Math.sqrt(2.0), b = 1.0;
        for (int i = 0; i < N; i++) {
            in[i][0] = rng.nextGaussian();
            in[i][1] = rng.nextGaussian();
            tgt[i][0] = a * in[i][0] + b * in[i][1];
            tgt[i][1] = b * in[i][0] + a * in[i][1];
        }
        runAndPrint("2D Lorentz boost (predict |s| > 2)", in, tgt);
    }

    @Test
    void projectiveSwapTask() {
        // (x, y) → (y, x).  In Q[s][g], for newA = a·x − b·y to equal y
        // and newB = a·y + b·x + b·y·s to equal x: tricky to fit, but s
        // pull-direction is informative.
        Random rng = new Random(42);
        int N = 200;
        double[][] in = new double[N][2], tgt = new double[N][2];
        for (int i = 0; i < N; i++) {
            in[i][0] = rng.nextGaussian();
            in[i][1] = rng.nextGaussian();
            tgt[i][0] = in[i][1];
            tgt[i][1] = in[i][0];
        }
        runAndPrint("Projective swap (x, y) → (y, x)", in, tgt);
    }

    @Test
    void rotationByQuarterTurn() {
        // (x, y) → (-y, x) — 90° rotation, exactly fits at s = 0 with (a, b) = (0, 1):
        //   newA = 0·x − 1·y = −y  ✓
        //   newB = 0·y + 1·x + 1·y·0 = x  ✓
        Random rng = new Random(42);
        int N = 200;
        double[][] in = new double[N][2], tgt = new double[N][2];
        for (int i = 0; i < N; i++) {
            in[i][0] = rng.nextGaussian();
            in[i][1] = rng.nextGaussian();
            tgt[i][0] = -in[i][1];
            tgt[i][1] = in[i][0];
        }
        runAndPrint("90° rotation (x,y)→(−y, x) (s = 0 fits exactly)", in, tgt);
    }
}
