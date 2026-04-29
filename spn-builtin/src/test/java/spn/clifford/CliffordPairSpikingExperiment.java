package spn.clifford;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Random;

/**
 * Probe of the conjecture that k traction is the natural primitive for
 * <b>event / spiking</b> computation, not regression. The wheel infinity
 * that breaks regression — produced when the input lies on the hyperplane
 * {@code a·y + b·x = 0} — IS the spike: the algebra is correctly saying
 * "input has crossed threshold."
 *
 * <p>Forward function is uniform across all four modes (η, ε, j, k) — no
 * special-casing of wheel data, no per-mode extraction policy. The single
 * shared forward is:
 * <pre>
 *   raw  = toDouble(weight.composeBilinear(input).top())   // ±Inf for k near hyperplane
 *   pred = 1 - 1 / (1 + |raw|)                              // saturating magnitude squash
 * </pre>
 * The squashing function maps {@code ±∞ → 1}, {@code 0 → 0}, monotone in
 * |raw|. In IEEE 754 this is well-defined at infinity ({@code 1/(1+Inf) = 0}),
 * so no branch is needed.
 *
 * <p>Target: binary. {@code 1} if input is within {@code BAND_HALFWIDTH} of
 * the hyperplane {@code y = x}, else {@code 0}. About 24% of standard
 * Gaussian samples fall in the band.
 *
 * <p>Predicted ordering:
 * <ul>
 *   <li><b>k</b>: should fit well — its bilinear naturally peaks on a line
 *       through origin. Weights {@code (a, b) = (1, -1)} put the peak on
 *       {@code y = x}.
 *   <li><b>η/ε/j</b>: should fit poorly — their outputs are linear-ish in
 *       input, so the squashed magnitude is large for large {@code |input|}
 *       and small near origin. They can't carve a thin band along a line.
 * </ul>
 */
class CliffordPairSpikingExperiment {

    private static final double BAND_HALFWIDTH = 0.3;

    // ── Forward: shared across all modes, no special-casing ─────────

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
        if (x instanceof CayleyDicksonPair p) {
            return toDouble(p.top());
        }
        throw new IllegalArgumentException("Cannot convert " + x.getClass());
    }

    /** Saturating magnitude squash: ±Inf → 1, 0 → 0, monotone in |raw|. */
    private static double squash(double raw) {
        return 1.0 - 1.0 / (1.0 + Math.abs(raw));
    }

    private static double forward(CliffordPair weight, double x, double y) {
        CliffordPair input = new CliffordPair(
                new CliffordDouble(x), new CliffordDouble(y), weight.delta());
        CliffordNumber out = weight.composeBilinear(input);
        double raw = toDouble(((CayleyDicksonPair) out).top());
        return squash(raw);
    }

    private static double meanSquaredError(CliffordPair weight, double[][] inputs, double[] targets) {
        double total = 0.0;
        int finite = 0;
        for (int i = 0; i < inputs.length; i++) {
            double pred = forward(weight, inputs[i][0], inputs[i][1]);
            double diff = pred - targets[i];
            double sq = diff * diff;
            if (Double.isFinite(sq)) {
                total += sq;
                finite++;
            }
        }
        if (finite == 0) return 1e12;
        return total / finite;
    }

    // ── SGD ──────────────────────────────────────────────────────────

    private static CliffordPair sgdStep(CliffordPair weight, double[][] inputs, double[] targets,
                                        double lr, double eps) {
        double topVal = ((CliffordDouble) weight.top()).value();
        double botVal = ((CliffordDouble) weight.bottom()).value();

        CliffordPair wTopPlus  = new CliffordPair(new CliffordDouble(topVal + eps), weight.bottom(), weight.delta());
        CliffordPair wTopMinus = new CliffordPair(new CliffordDouble(topVal - eps), weight.bottom(), weight.delta());
        double gradTop = (meanSquaredError(wTopPlus, inputs, targets)
                        - meanSquaredError(wTopMinus, inputs, targets)) / (2.0 * eps);

        CliffordPair wBotPlus  = new CliffordPair(weight.top(), new CliffordDouble(botVal + eps), weight.delta());
        CliffordPair wBotMinus = new CliffordPair(weight.top(), new CliffordDouble(botVal - eps), weight.delta());
        double gradBot = (meanSquaredError(wBotPlus, inputs, targets)
                        - meanSquaredError(wBotMinus, inputs, targets)) / (2.0 * eps);

        gradTop = Math.max(-1e3, Math.min(1e3, gradTop));
        gradBot = Math.max(-1e3, Math.min(1e3, gradBot));

        return new CliffordPair(
                new CliffordDouble(topVal - lr * gradTop),
                new CliffordDouble(botVal - lr * gradBot),
                weight.delta());
    }

    private static TrainResult trainOnce(CliffordNumber delta, double topInit, double botInit,
                                         double[][] inputs, double[] targets,
                                         int epochs, double lr) {
        CliffordPair weight = new CliffordPair(
                new CliffordDouble(topInit), new CliffordDouble(botInit), delta);
        double initialLoss = meanSquaredError(weight, inputs, targets);
        for (int epoch = 0; epoch < epochs; epoch++) {
            weight = sgdStep(weight, inputs, targets, lr, 1e-4);
        }
        double finalLoss = meanSquaredError(weight, inputs, targets);
        return new TrainResult(initialLoss, finalLoss,
                ((CliffordDouble) weight.top()).value(),
                ((CliffordDouble) weight.bottom()).value());
    }

    private static TrainResult train(CliffordNumber delta, double[][] inputs, double[] targets,
                                     int epochs, double lr) {
        Random rng = new Random(0xC1ff0d);
        int restarts = 8;
        TrainResult best = trainOnce(delta, 0.5, 0.5, inputs, targets, epochs, lr);
        for (int r = 1; r < restarts; r++) {
            double t = (rng.nextDouble() * 2 - 1) * 1.5;
            double b = (rng.nextDouble() * 2 - 1) * 1.5;
            TrainResult cand = trainOnce(delta, t, b, inputs, targets, epochs, lr);
            if (Double.isFinite(cand.finalLoss) && cand.finalLoss < best.finalLoss) {
                best = cand;
            }
        }
        return best;
    }

    private record TrainResult(double initialLoss, double finalLoss, double finalTop, double finalBot) {}

    // ── Mode metadata ────────────────────────────────────────────────

    private static final CliffordNumber[] MODES = {
            CliffordPair.DELTA_ELLIPTIC,
            CliffordPair.DELTA_PARABOLIC,
            CliffordPair.DELTA_HYPERBOLIC,
            CliffordPair.DELTA_TRACTION,
    };
    private static final String[] MODE_NAMES = {
            "η elliptic",
            "ε parabolic",
            "j hyperbolic",
            "k traction",
    };

    private static void runComparison(String taskName, double[][] inputs, double[] targets,
                                      int epochs, double lr) {
        System.out.println();
        System.out.println("── Spiking probe: " + taskName + " ──");
        // Constant-prediction baselines for context.
        double meanTarget = 0.0;
        for (double t : targets) meanTarget += t;
        meanTarget /= targets.length;
        double constMeanLoss = 0.0, constZeroLoss = 0.0;
        for (double t : targets) {
            constMeanLoss += (meanTarget - t) * (meanTarget - t);
            constZeroLoss += t * t;
        }
        constMeanLoss /= targets.length;
        constZeroLoss /= targets.length;
        System.out.printf(Locale.ROOT, "  baseline: const-zero loss = %.6f, const-mean(=%.3f) loss = %.6f%n",
                constZeroLoss, meanTarget, constMeanLoss);

        System.out.printf(Locale.ROOT, "%-15s  %12s  %12s  %18s%n",
                "mode", "init loss", "final loss", "weight (top, bot)");
        for (int m = 0; m < MODES.length; m++) {
            TrainResult r = train(MODES[m], inputs, targets, epochs, lr);
            System.out.printf(Locale.ROOT, "%-15s  %12.6f  %12.6f  (%7.4f, %7.4f)%n",
                    MODE_NAMES[m], r.initialLoss, r.finalLoss, r.finalTop, r.finalBot);
        }
    }

    // ── Probe: spike-near-hyperplane ─────────────────────────────────

    @Test
    void spikeNearDiagonal() {
        // True hyperplane: y = x. Band: |y - x| < 0.3 → target = 1, else 0.
        // k can match this exactly with (a, b) = (c, -c) for any c > 0.
        Random rng = new Random(42);
        int N = 500;
        double[][] inputs = new double[N][2];
        double[] targets = new double[N];
        int inBand = 0;
        for (int i = 0; i < N; i++) {
            inputs[i][0] = rng.nextGaussian();
            inputs[i][1] = rng.nextGaussian();
            double dist = Math.abs(inputs[i][1] - inputs[i][0]);
            targets[i] = dist < BAND_HALFWIDTH ? 1.0 : 0.0;
            if (targets[i] > 0.5) inBand++;
        }
        System.out.printf(Locale.ROOT, "  (band fraction: %d / %d = %.1f%%)%n",
                inBand, N, 100.0 * inBand / N);
        runComparison("spike when |y - x| < " + BAND_HALFWIDTH, inputs, targets, 800, 0.05);
    }

    @Test
    void spikeNearXAxis() {
        // Hyperplane: y = 0. Band: |y| < 0.3.
        // k matches with (a, b) = (1, 0): a·y + b·x = y, so wheel diverges
        // exactly when y = 0.
        Random rng = new Random(42);
        int N = 500;
        double[][] inputs = new double[N][2];
        double[] targets = new double[N];
        int inBand = 0;
        for (int i = 0; i < N; i++) {
            inputs[i][0] = rng.nextGaussian();
            inputs[i][1] = rng.nextGaussian();
            double dist = Math.abs(inputs[i][1]);
            targets[i] = dist < BAND_HALFWIDTH ? 1.0 : 0.0;
            if (targets[i] > 0.5) inBand++;
        }
        System.out.printf(Locale.ROOT, "  (band fraction: %d / %d = %.1f%%)%n",
                inBand, N, 100.0 * inBand / N);
        runComparison("spike when |y| < " + BAND_HALFWIDTH, inputs, targets, 800, 0.05);
    }

    @Test
    void spikeNearAngledLine() {
        // Hyperplane: y = 2x (line at slope 2 through origin).
        // k matches with (a, b) = (1, -2): a·y + b·x = y - 2x = 0 on the line.
        Random rng = new Random(42);
        int N = 500;
        double[][] inputs = new double[N][2];
        double[] targets = new double[N];
        int inBand = 0;
        // Distance from point to y = 2x line: |2x - y| / sqrt(5).
        double bandRescaled = BAND_HALFWIDTH * Math.sqrt(5.0);
        for (int i = 0; i < N; i++) {
            inputs[i][0] = rng.nextGaussian();
            inputs[i][1] = rng.nextGaussian();
            double dist = Math.abs(2.0 * inputs[i][0] - inputs[i][1]);
            targets[i] = dist < bandRescaled ? 1.0 : 0.0;
            if (targets[i] > 0.5) inBand++;
        }
        System.out.printf(Locale.ROOT, "  (band fraction: %d / %d = %.1f%%)%n",
                inBand, N, 100.0 * inBand / N);
        runComparison("spike when |y - 2x| / √5 < " + BAND_HALFWIDTH, inputs, targets, 800, 0.05);
    }
}
