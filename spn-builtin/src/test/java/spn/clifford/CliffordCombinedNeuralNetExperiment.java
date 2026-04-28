package spn.clifford;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Random;

/**
 * Combined-neuron experiment: a single neuron that holds BOTH a
 * {@link CayleyDicksonPair} weight (compile-time-fixed signature corner)
 * AND a {@link CliffordRingElement} weight (continuous s-dial). Output is
 * the component-wise sum of both products.
 *
 * <p>The 5 learnable parameters are: {@code (cd_top, cd_bot, ring_a, ring_b, ring_s)}.
 *
 * <p><b>Why this matters for the η-j vs ε-k pairing question:</b> a single
 * Cayley-Dickson mode at level 1 produces a 2×2 matrix family with diagonal
 * symmetry — {@code newA, newB} both depend on {@code cd_top} the same way.
 * No pairing of two CD modes can break this diagonal symmetry; their sums
 * still have {@code m11 = m22}.
 *
 * <p>The ring's bilinear formula has a {@code b·s·y} term in {@code newB}
 * that breaks diagonal symmetry — {@code m22 = a + b·s} while {@code m11 = a}.
 * So ring is the missing ingredient. When you add ring to ANY CD mode, you
 * get a 5-parameter model that can fit arbitrary 2×2 linear maps.
 *
 * <p>The interesting question becomes: which CD partner does the ring pair
 * most naturally with for which tasks? And does the ring's s converge to a
 * meaningful signature region per task?
 */
class CliffordCombinedNeuralNetExperiment {

    // ── Combined weight (immutable container) ────────────────────────

    private record CombinedWeight(
            CayleyDicksonPair cdPair,
            CliffordRingElement ringEl
    ) {}

    /** Build a CD pair of the chosen mode with given top/bot. */
    private static CayleyDicksonPair makeCd(String mode, double top, double bot) {
        CliffordDouble t = new CliffordDouble(top), b = new CliffordDouble(bot);
        return switch (mode) {
            case "η" -> new CliffordEllipticPair(t, b);
            case "ε" -> new CliffordParabolicPair(t, b);
            case "j" -> new CliffordHyperbolicPair(t, b);
            case "k" -> new CliffordTractionPair(t, b);
            default  -> throw new IllegalArgumentException("Unknown mode " + mode);
        };
    }

    /** Convert input (x, y) to a CD pair of the right concrete type. */
    private static CayleyDicksonPair makeCdInput(String mode, double x, double y) {
        return makeCd(mode, x, y);
    }

    // ── Forward / loss ──────────────────────────────────────────────

    private static double toDouble(CliffordNumber x) {
        if (x instanceof CliffordDouble(double v)) return v;
        if (x instanceof CliffordInteger(long v)) return v;
        if (x instanceof FractionalElement f) {
            double t = toDouble(f.top());
            double b = toDouble(f.bottom());
            if (b == 0.0) {
                if (t == 0.0) return 1.0;
                return Math.copySign(Double.POSITIVE_INFINITY, t);
            }
            return t / b;
        }
        if (x instanceof CayleyDicksonPair p) return toDouble(p.top());
        throw new IllegalArgumentException("Cannot convert " + x.getClass());
    }

    private static double[] forward(String cdMode, CombinedWeight w, double x, double y) {
        // CD path
        CayleyDicksonPair cdInput = makeCdInput(cdMode, x, y);
        CayleyDicksonPair cdOut = (CayleyDicksonPair) w.cdPair.composeBilinear(cdInput);
        double cdA = toDouble(cdOut.top());
        double cdB = toDouble(cdOut.bottom());

        // Ring path
        CliffordRingElement ringInput = new CliffordRingElement(x, y, w.ringEl.s());
        CliffordRingElement ringOut = w.ringEl.mult(ringInput);

        return new double[] { cdA + ringOut.a(), cdB + ringOut.b() };
    }

    private static double mse(String cdMode, CombinedWeight w,
                              double[][] inputs, double[][] targets) {
        double total = 0.0;
        int finite = 0;
        for (int i = 0; i < inputs.length; i++) {
            double[] pred = forward(cdMode, w, inputs[i][0], inputs[i][1]);
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

    // ── Finite-diff SGD on (cd_top, cd_bot, ring_a, ring_b, ring_s) ─

    private static double clip(double g) { return Math.max(-1e3, Math.min(1e3, g)); }

    private static CombinedWeight sgdStep(String cdMode, CombinedWeight w,
                                          double[][] inputs, double[][] targets,
                                          double lrCd, double lrRingAB, double lrS, double eps) {
        double cdT = ((CliffordDouble) w.cdPair.top()).value();
        double cdB = ((CliffordDouble) w.cdPair.bottom()).value();
        double rA = w.ringEl.a(), rB = w.ringEl.b(), rS = w.ringEl.s();

        // Gradients via finite difference
        double dCdT = (mse(cdMode, new CombinedWeight(makeCd(cdMode, cdT + eps, cdB), w.ringEl), inputs, targets)
                     - mse(cdMode, new CombinedWeight(makeCd(cdMode, cdT - eps, cdB), w.ringEl), inputs, targets)) / (2 * eps);
        double dCdB = (mse(cdMode, new CombinedWeight(makeCd(cdMode, cdT, cdB + eps), w.ringEl), inputs, targets)
                     - mse(cdMode, new CombinedWeight(makeCd(cdMode, cdT, cdB - eps), w.ringEl), inputs, targets)) / (2 * eps);
        double dRA  = (mse(cdMode, new CombinedWeight(w.cdPair, new CliffordRingElement(rA + eps, rB, rS)), inputs, targets)
                     - mse(cdMode, new CombinedWeight(w.cdPair, new CliffordRingElement(rA - eps, rB, rS)), inputs, targets)) / (2 * eps);
        double dRB  = (mse(cdMode, new CombinedWeight(w.cdPair, new CliffordRingElement(rA, rB + eps, rS)), inputs, targets)
                     - mse(cdMode, new CombinedWeight(w.cdPair, new CliffordRingElement(rA, rB - eps, rS)), inputs, targets)) / (2 * eps);
        double dRS  = (mse(cdMode, new CombinedWeight(w.cdPair, new CliffordRingElement(rA, rB, rS + eps)), inputs, targets)
                     - mse(cdMode, new CombinedWeight(w.cdPair, new CliffordRingElement(rA, rB, rS - eps)), inputs, targets)) / (2 * eps);

        return new CombinedWeight(
                makeCd(cdMode, cdT - lrCd * clip(dCdT), cdB - lrCd * clip(dCdB)),
                new CliffordRingElement(
                        rA - lrRingAB * clip(dRA),
                        rB - lrRingAB * clip(dRB),
                        rS - lrS * clip(dRS))
        );
    }

    // ── Multi-restart training ───────────────────────────────────────

    private static CombinedWeight trainOnce(String cdMode,
                                            double cdT0, double cdB0,
                                            double rA0, double rB0, double rS0,
                                            double[][] inputs, double[][] targets,
                                            int epochs, double lrCd, double lrRingAB, double lrS) {
        CombinedWeight w = new CombinedWeight(
                makeCd(cdMode, cdT0, cdB0),
                new CliffordRingElement(rA0, rB0, rS0));
        for (int e = 0; e < epochs; e++) {
            w = sgdStep(cdMode, w, inputs, targets, lrCd, lrRingAB, lrS, 1e-4);
        }
        return w;
    }

    private record TrainResult(CombinedWeight weight, double finalLoss, double initLoss) {}

    private static TrainResult train(String cdMode, double[][] inputs, double[][] targets,
                                      int epochs, double lrCd, double lrRingAB, double lrS) {
        Random rng = new Random(0xC1ff0d);
        CombinedWeight w0 = new CombinedWeight(
                makeCd(cdMode, 0.5, 0.5),
                new CliffordRingElement(0.5, 0.5, 0.0));
        double initLoss = mse(cdMode, w0, inputs, targets);

        CombinedWeight best = trainOnce(cdMode, 0.5, 0.5, 0.5, 0.5, 0.0,
                inputs, targets, epochs, lrCd, lrRingAB, lrS);
        double bestLoss = mse(cdMode, best, inputs, targets);

        double[] sStarts = { -3.0, -1.0, 0.0, 1.0, 3.0 };
        for (double s0 : sStarts) {
            double cdT0 = (rng.nextDouble() * 2 - 1);
            double cdB0 = (rng.nextDouble() * 2 - 1);
            double rA0  = (rng.nextDouble() * 2 - 1);
            double rB0  = (rng.nextDouble() * 2 - 1);
            CombinedWeight cand = trainOnce(cdMode, cdT0, cdB0, rA0, rB0, s0,
                    inputs, targets, epochs, lrCd, lrRingAB, lrS);
            double l = mse(cdMode, cand, inputs, targets);
            if (Double.isFinite(l) && l < bestLoss) { bestLoss = l; best = cand; }
        }
        return new TrainResult(best, bestLoss, initLoss);
    }

    private static void runComparison(String taskName, double[][] inputs, double[][] targets) {
        System.out.println();
        System.out.println("── Task: " + taskName + " ──");
        System.out.printf(Locale.ROOT, "%-8s  %12s  %18s  %22s%n",
                "CD mode", "final loss", "CD (top, bot)", "ring (a, b, s)");
        for (String mode : new String[] { "η", "ε", "j", "k" }) {
            TrainResult r = train(mode, inputs, targets, 800, 0.05, 0.05, 0.01);
            double cdT = ((CliffordDouble) r.weight.cdPair.top()).value();
            double cdB = ((CliffordDouble) r.weight.cdPair.bottom()).value();
            double rA  = r.weight.ringEl.a();
            double rB  = r.weight.ringEl.b();
            double rS  = r.weight.ringEl.s();
            System.out.printf(Locale.ROOT, "%-8s  %12.6f  (%+6.3f, %+6.3f)  (%+6.3f, %+6.3f, %+6.3f)%n",
                    mode + " + ring", r.finalLoss, cdT, cdB, rA, rB, rS);
        }
    }

    // ── Tasks ────────────────────────────────────────────────────────

    @Test
    void arbitraryLinear2x2_diagonalAsymmetric() {
        // M = [[1, 2], [3, -1]]: m11 ≠ m22, so no single CD mode and no
        // CD-CD pair can fit. Ring's b·s·y term is the only way to break
        // diagonal symmetry. Expect: ring contributes meaningfully (b ≠ 0,
        // s ≠ 0), CD picks up the rest.
        Random rng = new Random(42);
        int N = 200;
        double[][] in = new double[N][2], tgt = new double[N][2];
        for (int i = 0; i < N; i++) {
            in[i][0] = rng.nextGaussian();
            in[i][1] = rng.nextGaussian();
            tgt[i][0] = 1.0 * in[i][0] + 2.0 * in[i][1];
            tgt[i][1] = 3.0 * in[i][0] + (-1.0) * in[i][1];
        }
        runComparison("Arbitrary 2x2 [[1,2],[3,-1]] (only CD+ring should fit)", in, tgt);
    }

    @Test
    void lorentzBoost_revisited() {
        // Boost target = (a·x + b·y, b·x + a·y). Single mode CD: only j
        // fits exactly. Ring alone: can't fit at any s. CD+ring: should
        // fit easily for j; will the others get help?
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
        runComparison("Lorentz boost (j wins single; combined?)", in, tgt);
    }

    @Test
    void rotation_revisited() {
        // η fits exactly alone. Combined model should also fit, with ring
        // contributing nothing (s ≈ 0, b ≈ 0). Sanity check that the
        // combined model doesn't break what already works.
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
        runComparison("Rotation π/4 (η fits alone — sanity)", in, tgt);
    }

    /** Build a dataset whose target is the linear transformation
     *  {@code (x, y) → (m11·x + m12·y, m21·x + m22·y)}. */
    private static void buildLinearTask(double[][] M, double[][] in, double[][] tgt, Random rng) {
        for (int i = 0; i < in.length; i++) {
            in[i][0] = rng.nextGaussian();
            in[i][1] = rng.nextGaussian();
            tgt[i][0] = M[0][0] * in[i][0] + M[0][1] * in[i][1];
            tgt[i][1] = M[1][0] * in[i][0] + M[1][1] * in[i][1];
        }
    }

    /** Quick statistics summary. */
    private record SweepStats(double median, double max, double meanLoss,
                              int converged, int total) {
        double convergedPct() { return 100.0 * converged / total; }
    }

    private static SweepStats summarize(double[] losses, double convergedThreshold) {
        double[] sorted = losses.clone();
        java.util.Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        double max = sorted[sorted.length - 1];
        double sum = 0;
        int converged = 0;
        for (double l : losses) {
            sum += l;
            if (l < convergedThreshold) converged++;
        }
        return new SweepStats(median, max, sum / losses.length, converged, losses.length);
    }

    @Test
    void universalFitterSweep_randomMatrices() {
        // Generate N random 2x2 matrices; train each CD + ring mode on each;
        // measure how often each mode hits "zero" loss.
        // Hypothesis: ε + ring should be a universal fitter (≥99% converged).
        int N = 30;
        int samples = 200;
        long matrixSeed = 0xCD; // for reproducibility

        Random matrixRng = new Random(matrixSeed);
        double[][][] matrices = new double[N][2][2];
        for (int i = 0; i < N; i++) {
            matrices[i][0][0] = matrixRng.nextGaussian();
            matrices[i][0][1] = matrixRng.nextGaussian();
            matrices[i][1][0] = matrixRng.nextGaussian();
            matrices[i][1][1] = matrixRng.nextGaussian();
        }

        double convergedThreshold = 1e-4;
        System.out.println();
        System.out.println("── Universal-fitter sweep: " + N + " random 2x2 matrices ──");
        System.out.printf(Locale.ROOT, "%-10s  %10s  %12s  %12s  %12s%n",
                "mode", "converged", "median loss", "mean loss", "max loss");

        for (String mode : new String[] { "η", "ε", "j", "k" }) {
            double[] finalLosses = new double[N];
            for (int i = 0; i < N; i++) {
                Random dataRng = new Random(42L + i);
                double[][] in = new double[samples][2];
                double[][] tgt = new double[samples][2];
                buildLinearTask(matrices[i], in, tgt, dataRng);
                TrainResult r = train(mode, in, tgt, 800, 0.05, 0.05, 0.01);
                finalLosses[i] = r.finalLoss;
            }
            SweepStats s = summarize(finalLosses, convergedThreshold);
            System.out.printf(Locale.ROOT, "%-10s  %4d / %2d   %12.6e  %12.6e  %12.6e%n",
                    mode + " + ring", s.converged, s.total, s.median, s.meanLoss, s.max);
        }
    }

    /** Variant of train() that takes a custom s_starts array. */
    private static TrainResult trainWithStarts(String cdMode, double[][] inputs, double[][] targets,
                                                int epochs, double lrCd, double lrRingAB, double lrS,
                                                double[] sStarts) {
        Random rng = new Random(0xC1ff0d);
        CombinedWeight w0 = new CombinedWeight(
                makeCd(cdMode, 0.5, 0.5),
                new CliffordRingElement(0.5, 0.5, 0.0));
        double initLoss = mse(cdMode, w0, inputs, targets);

        CombinedWeight best = trainOnce(cdMode, 0.5, 0.5, 0.5, 0.5, 0.0,
                inputs, targets, epochs, lrCd, lrRingAB, lrS);
        double bestLoss = mse(cdMode, best, inputs, targets);

        for (double s0 : sStarts) {
            double cdT0 = (rng.nextDouble() * 2 - 1);
            double cdB0 = (rng.nextDouble() * 2 - 1);
            double rA0  = (rng.nextDouble() * 2 - 1);
            double rB0  = (rng.nextDouble() * 2 - 1);
            CombinedWeight cand = trainOnce(cdMode, cdT0, cdB0, rA0, rB0, s0,
                    inputs, targets, epochs, lrCd, lrRingAB, lrS);
            double l = mse(cdMode, cand, inputs, targets);
            if (Double.isFinite(l) && l < bestLoss) { bestLoss = l; best = cand; }
        }
        return new TrainResult(best, bestLoss, initLoss);
    }

    @Test
    void universalFitterSweep_widerInit() {
        // Expanded s starts from ±15 to test whether SGD with wide init can
        // find the right basin for arbitrary matrices.
        int N = 30, samples = 200;
        Random matrixRng = new Random(0xCD);
        double[][][] matrices = new double[N][2][2];
        for (int i = 0; i < N; i++) {
            matrices[i][0][0] = matrixRng.nextGaussian();
            matrices[i][0][1] = matrixRng.nextGaussian();
            matrices[i][1][0] = matrixRng.nextGaussian();
            matrices[i][1][1] = matrixRng.nextGaussian();
        }

        double[] wideStarts = { -15.0, -8.0, -3.0, -1.0, 0.0, 1.0, 3.0, 8.0, 15.0 };
        System.out.println();
        System.out.println("── Wider s init: 30 random matrices, s starts " +
                java.util.Arrays.toString(wideStarts) + " ──");
        System.out.printf(Locale.ROOT, "%-10s  %10s  %12s  %12s  %12s%n",
                "mode", "converged", "median loss", "mean loss", "max loss");

        for (String mode : new String[] { "η", "ε", "j", "k" }) {
            double[] finalLosses = new double[N];
            for (int i = 0; i < N; i++) {
                Random dataRng = new Random(42L + i);
                double[][] in = new double[samples][2];
                double[][] tgt = new double[samples][2];
                buildLinearTask(matrices[i], in, tgt, dataRng);
                TrainResult r = trainWithStarts(mode, in, tgt, 800, 0.05, 0.05, 0.01, wideStarts);
                finalLosses[i] = r.finalLoss;
            }
            SweepStats s = summarize(finalLosses, 1e-4);
            System.out.printf(Locale.ROOT, "%-10s  %4d / %2d   %12.6e  %12.6e  %12.6e%n",
                    mode + " + ring", s.converged, s.total, s.median, s.meanLoss, s.max);
        }
    }

    /** Two-stage training: main SGD, then a refinement stage at lr/10. */
    private static CombinedWeight trainWithRefinement(String cdMode,
                                                       double cdT0, double cdB0,
                                                       double rA0, double rB0, double rS0,
                                                       double[][] inputs, double[][] targets,
                                                       int mainEpochs, int refineEpochs,
                                                       double lrCd, double lrRingAB, double lrS) {
        CombinedWeight w = new CombinedWeight(
                makeCd(cdMode, cdT0, cdB0),
                new CliffordRingElement(rA0, rB0, rS0));
        for (int e = 0; e < mainEpochs; e++) {
            w = sgdStep(cdMode, w, inputs, targets, lrCd, lrRingAB, lrS, 1e-4);
        }
        for (int e = 0; e < refineEpochs; e++) {
            w = sgdStep(cdMode, w, inputs, targets, lrCd / 10, lrRingAB / 10, lrS / 10, 1e-5);
        }
        return w;
    }

    @Test
    void analyticalSolution_proveUniversalityClaim() {
        // No SGD. Just plug in the closed-form solution for ε + ring on
        // each random 2x2 matrix and report the loss.
        //
        // For ε + ring, the algebra says:
        //   r_b  = -m12
        //   cd_b = m21 + m12
        //   cd_t + r_a = m11   (split is free; pick cd_t = m11, r_a = 0)
        //   s    = (m22 - m11) / r_b = -(m22 - m11) / m12       if m12 ≠ 0
        //
        // If loss is ~0 for every matrix with m12 ≠ 0, the math claim is
        // proven and earlier SGD failures were pure optimization.
        int N = 30, samples = 200;
        Random matrixRng = new Random(0xCD);
        double[][][] matrices = new double[N][2][2];
        for (int i = 0; i < N; i++) {
            matrices[i][0][0] = matrixRng.nextGaussian();
            matrices[i][0][1] = matrixRng.nextGaussian();
            matrices[i][1][0] = matrixRng.nextGaussian();
            matrices[i][1][1] = matrixRng.nextGaussian();
        }

        System.out.println();
        System.out.println("── Analytical ε + ring solution: 30 matrices, no SGD ──");
        int fitted = 0;
        double maxLoss = 0;
        for (int i = 0; i < N; i++) {
            Random dataRng = new Random(42L + i);
            double[][] in = new double[samples][2];
            double[][] tgt = new double[samples][2];
            buildLinearTask(matrices[i], in, tgt, dataRng);

            double m11 = matrices[i][0][0];
            double m12 = matrices[i][0][1];
            double m21 = matrices[i][1][0];
            double m22 = matrices[i][1][1];

            double rB = -m12;
            double cdB = m21 + m12;
            double cdT = m11;
            double rA = 0.0;
            double s = (Math.abs(m12) > 1e-9) ? (m22 - m11) / rB : 0.0;

            CombinedWeight w = new CombinedWeight(
                    makeCd("ε", cdT, cdB),
                    new CliffordRingElement(rA, rB, s));
            double loss = mse("ε", w, in, tgt);
            if (loss < 1e-8) fitted++;
            maxLoss = Math.max(maxLoss, loss);
            if (loss > 1e-6) {
                System.out.printf(Locale.ROOT,
                        "  matrix #%d: m12 = %+.4f, m22-m11 = %+.4f, s* = %+.4f, loss = %.6e%n",
                        i, m12, m22 - m11, s, loss);
            }
        }
        System.out.printf(Locale.ROOT, "  fitted to <1e-8 loss: %d / %d   (max loss across all: %.6e)%n",
                fitted, N, maxLoss);
    }

    @Test
    void universalFitterSweep_aggressiveOptimizer() {
        // Direct s* init + 12 restarts with perturbations + refinement stage.
        // The math says ε + ring fits any matrix with m12 ≠ 0. If this still
        // doesn't hit 100%, something more subtle is going on.
        int N = 30, samples = 200;
        Random matrixRng = new Random(0xCD);
        double[][][] matrices = new double[N][2][2];
        for (int i = 0; i < N; i++) {
            matrices[i][0][0] = matrixRng.nextGaussian();
            matrices[i][0][1] = matrixRng.nextGaussian();
            matrices[i][1][0] = matrixRng.nextGaussian();
            matrices[i][1][1] = matrixRng.nextGaussian();
        }

        System.out.println();
        System.out.println("── Aggressive optimizer (direct s*, 12 restarts, refinement): 30 matrices ──");
        System.out.printf(Locale.ROOT, "%-10s  %10s  %12s  %12s  %12s%n",
                "mode", "converged", "median loss", "mean loss", "max loss");

        Random restartRng = new Random(0xC1ff0d);
        for (String mode : new String[] { "η", "ε", "j" }) {  // skip k (always stuck)
            double[] finalLosses = new double[N];
            for (int i = 0; i < N; i++) {
                Random dataRng = new Random(42L + i);
                double[][] in = new double[samples][2];
                double[][] tgt = new double[samples][2];
                buildLinearTask(matrices[i], in, tgt, dataRng);

                double m11 = matrices[i][0][0], m12 = matrices[i][0][1];
                double m22 = matrices[i][1][1];
                double sStar = (Math.abs(m12) > 1e-9) ? (m11 - m22) / m12 : 0.0;

                CombinedWeight bestW = null;
                double bestLoss = Double.POSITIVE_INFINITY;
                // 12 restarts: s* and 11 perturbed versions, varied init for cd/ring.
                for (int r = 0; r < 12; r++) {
                    double s0 = (r == 0) ? sStar : sStar + (restartRng.nextDouble() - 0.5) * 4.0;
                    double cdT0 = restartRng.nextGaussian();
                    double cdB0 = restartRng.nextGaussian();
                    double rA0  = restartRng.nextGaussian();
                    double rB0  = restartRng.nextGaussian();
                    CombinedWeight cand = trainWithRefinement(mode, cdT0, cdB0, rA0, rB0, s0,
                            in, tgt, 800, 400, 0.05, 0.05, 0.01);
                    double l = mse(mode, cand, in, tgt);
                    if (Double.isFinite(l) && l < bestLoss) { bestLoss = l; bestW = cand; }
                }
                finalLosses[i] = bestLoss;
            }
            SweepStats s = summarize(finalLosses, 1e-4);
            System.out.printf(Locale.ROOT, "%-10s  %4d / %2d   %12.6e  %12.6e  %12.6e%n",
                    mode + " + ring", s.converged, s.total, s.median, s.meanLoss, s.max);
        }
    }

    @Test
    void universalFitterSweep_directInit() {
        // Test the MATH claim, not SGD: init s near s* = (m11 - m22) / m12,
        // the analytical solution for ε + ring on a generic 2x2. If
        // convergence is now ~100%, we've confirmed the algebra is universal
        // and our previous failures were optimization, not expressiveness.
        int N = 30, samples = 200;
        Random matrixRng = new Random(0xCD);
        double[][][] matrices = new double[N][2][2];
        for (int i = 0; i < N; i++) {
            matrices[i][0][0] = matrixRng.nextGaussian();
            matrices[i][0][1] = matrixRng.nextGaussian();
            matrices[i][1][0] = matrixRng.nextGaussian();
            matrices[i][1][1] = matrixRng.nextGaussian();
        }

        System.out.println();
        System.out.println("── Direct init at s* = (m11-m22)/m12: 30 random matrices ──");
        System.out.printf(Locale.ROOT, "%-10s  %10s  %12s  %12s  %12s%n",
                "mode", "converged", "median loss", "mean loss", "max loss");

        for (String mode : new String[] { "η", "ε", "j", "k" }) {
            double[] finalLosses = new double[N];
            for (int i = 0; i < N; i++) {
                Random dataRng = new Random(42L + i);
                double[][] in = new double[samples][2];
                double[][] tgt = new double[samples][2];
                buildLinearTask(matrices[i], in, tgt, dataRng);

                // Analytical s*: assumes ε + ring algebra. For other CD
                // modes the formula differs slightly, but using the same
                // s* gives reasonable starting basin in most cases.
                double m11 = matrices[i][0][0], m12 = matrices[i][0][1];
                double m22 = matrices[i][1][1];
                double sStar = (Math.abs(m12) > 1e-9) ? (m11 - m22) / m12 : 0.0;

                // Three restarts near s*: s*, s* + 0.5, s* - 0.5
                double[] starts = { sStar, sStar + 0.5, sStar - 0.5 };
                TrainResult r = trainWithStarts(mode, in, tgt, 800, 0.05, 0.05, 0.01, starts);
                finalLosses[i] = r.finalLoss;
            }
            SweepStats s = summarize(finalLosses, 1e-4);
            System.out.printf(Locale.ROOT, "%-10s  %4d / %2d   %12.6e  %12.6e  %12.6e%n",
                    mode + " + ring", s.converged, s.total, s.median, s.meanLoss, s.max);
        }
    }

    @Test
    void shearRevisited() {
        // ε fits alone. Combined model should also fit.
        Random rng = new Random(42);
        int N = 200;
        double[][] in = new double[N][2], tgt = new double[N][2];
        for (int i = 0; i < N; i++) {
            in[i][0] = rng.nextGaussian();
            in[i][1] = rng.nextGaussian();
            tgt[i][0] = in[i][0];
            tgt[i][1] = in[i][1] + 0.5 * in[i][0];
        }
        runComparison("Shear y' = y + 0.5x (ε fits alone — sanity)", in, tgt);
    }
}
