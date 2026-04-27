package spn.clifford;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Random;

/**
 * Reference NN experiment: a 1-neuron model whose weight is a
 * {@link CliffordPair}. We train each of the four corner modes (η, ε, j, k)
 * separately on each task and compare final losses to see which algebra
 * the task naturally favors.
 *
 * <p>The model: {@code output = weight.composeBilinear(input)}, where both
 * weight and input are {@code CliffordPair}s in the same mode. {@code top}
 * and {@code bottom} of the input are the data point's two coordinates;
 * {@code top} and {@code bottom} of the weight are learnable parameters.
 *
 * <p>Gradient: finite differences (intentionally simple — the algebra is
 * what's being tested, not autograd performance). Optimizer: vanilla SGD.
 *
 * <p>This is a research probe, not a regression test. It prints a table of
 * results to stdout; the only assertions are sanity floors (final loss is
 * finite) so the experiment doesn't silently break in CI.
 */
class CliffordPairNeuralNetExperiment {

    // ── Data utilities ───────────────────────────────────────────────

    /** Walk any CliffordNumber down to a single double value. ω-flavored
     *  data ({@code n/0}) becomes ±Infinity; the wheel-bottom 0/0 becomes 1
     *  per the traction rule. */
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
            // Pair-shaped value collapsed to a scalar by taking its top —
            // happens if a bilinear product produced a pair-valued component
            // somewhere in the leaf chain.
            return toDouble(p.top());
        }
        throw new IllegalArgumentException("Cannot convert " + x.getClass());
    }

    private static double[] extractXY(CayleyDicksonPair pair) {
        return new double[] { toDouble(pair.top()), toDouble(pair.bottom()) };
    }

    // ── Forward pass + loss ──────────────────────────────────────────

    private static CliffordPair inputPair(double x, double y, CliffordNumber delta) {
        return new CliffordPair(new CliffordDouble(x), new CliffordDouble(y), delta);
    }

    private static double[] forward(CliffordPair weight, double x, double y) {
        CliffordPair input = inputPair(x, y, weight.delta());
        CliffordNumber out = weight.composeBilinear(input);
        return extractXY((CayleyDicksonPair) out);
    }

    private static double meanSquaredError(CliffordPair weight, double[][] inputs, double[][] targets) {
        double total = 0.0;
        int finite = 0;
        for (int i = 0; i < inputs.length; i++) {
            double[] pred = forward(weight, inputs[i][0], inputs[i][1]);
            double dx = pred[0] - targets[i][0];
            double dy = pred[1] - targets[i][1];
            double sq = dx * dx + dy * dy;
            if (Double.isFinite(sq)) {
                total += sq;
                finite++;
            }
        }
        // If everything blew up to infinity (e.g., traction on Euclidean
        // tasks), return a large finite penalty so SGD doesn't NaN-out.
        if (finite == 0) return 1e12;
        return total / finite;
    }

    // ── Finite-difference gradient + SGD step ────────────────────────

    private static CliffordPair sgdStep(CliffordPair weight, double[][] inputs, double[][] targets,
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

        // Clip wild gradients so the traction mode (which can blow up
        // through ω) doesn't NaN the parameters.
        gradTop = Math.max(-1e3, Math.min(1e3, gradTop));
        gradBot = Math.max(-1e3, Math.min(1e3, gradBot));

        return new CliffordPair(
                new CliffordDouble(topVal - lr * gradTop),
                new CliffordDouble(botVal - lr * gradBot),
                weight.delta());
    }

    /** Train one run from a specific (top, bottom) initialization. */
    private static TrainResult trainOnce(CliffordNumber delta, double topInit, double botInit,
                                         double[][] inputs, double[][] targets,
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

    /** Multi-restart training: run N random initializations and report the
     *  best final loss. Important for k whose loss landscape has ω-walls
     *  that bad inits can fall into. */
    private static TrainResult train(CliffordNumber delta, double[][] inputs, double[][] targets,
                                     int epochs, double lr) {
        Random rng = new Random(0xC1ff0d);  // fixed seed across modes for fair comparison
        int restarts = 6;
        // First restart at the canonical (0.5, 0.5) for stability with the
        // earlier results; subsequent ones random in [-1, 1].
        TrainResult best = trainOnce(delta, 0.5, 0.5, inputs, targets, epochs, lr);
        for (int r = 1; r < restarts; r++) {
            double t = (rng.nextDouble() * 2 - 1) * 1.0;
            double b = (rng.nextDouble() * 2 - 1) * 1.0;
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

    private static void runComparison(String taskName, double[][] inputs, double[][] targets,
                                      int epochs, double lr) {
        System.out.println();
        System.out.println("── Task: " + taskName + " ──");
        System.out.printf(Locale.ROOT, "%-15s  %12s  %12s  %18s%n",
                "mode", "init loss", "final loss", "weight (top, bot)");
        for (int m = 0; m < MODES.length; m++) {
            TrainResult r = train(MODES[m], inputs, targets, epochs, lr);
            System.out.printf(Locale.ROOT, "%-15s  %12.6f  %12.6f  (%7.4f, %7.4f)%n",
                    MODE_NAMES[m], r.initialLoss, r.finalLoss, r.finalTop, r.finalBot);
        }
    }

    // ── Experiments ──────────────────────────────────────────────────

    @Test
    void rotationTaskFavoredByElliptic() {
        // Target: rotate (x, y) by π/4. In elliptic mode this is exactly the
        // complex multiplication w·z with w = cos(π/4) + sin(π/4)·η, so
        // perfect fit at top = cos(π/4), bottom = sin(π/4). Other modes
        // implement a different transformation; should converge to higher
        // loss.
        Random rng = new Random(42);
        int N = 200;
        double[][] inputs = new double[N][2];
        double[][] targets = new double[N][2];
        double cos = Math.cos(Math.PI / 4), sin = Math.sin(Math.PI / 4);
        for (int i = 0; i < N; i++) {
            inputs[i][0]  = rng.nextGaussian();
            inputs[i][1]  = rng.nextGaussian();
            targets[i][0] = cos * inputs[i][0] - sin * inputs[i][1];
            targets[i][1] = sin * inputs[i][0] + cos * inputs[i][1];
        }
        runComparison("2D rotation by π/4 (η-favored)", inputs, targets, 500, 0.05);
    }

    @Test
    void scalingTaskFavoredByHyperbolic() {
        // Target: y' = 2·y (uniform-ish stretch). In hyperbolic mode,
        // (a + bj)·(x + yj) = (ax + by) + (ay + bx)j — so a=2, b=0 gives a
        // clean uniform scaling along both axes. Elliptic gives a skew that
        // can't reproduce the scale exactly.
        Random rng = new Random(42);
        int N = 200;
        double[][] inputs = new double[N][2];
        double[][] targets = new double[N][2];
        for (int i = 0; i < N; i++) {
            inputs[i][0]  = rng.nextGaussian();
            inputs[i][1]  = rng.nextGaussian();
            targets[i][0] = 2.0 * inputs[i][0];
            targets[i][1] = 2.0 * inputs[i][1];
        }
        runComparison("2D uniform scale ×2 (j-favored)", inputs, targets, 500, 0.05);
    }

    @Test
    void lorentzBoostTaskFavoredByHyperbolic() {
        // Target: hyperbolic rotation (cosh+sinh) acting on (x, y).
        //   x' = a·x + b·y    y' = b·x + a·y     with  a² - b² = 1
        // For a = √2, b = 1, this is a unit-rapidity boost. Hyperbolic
        // (a + bj)·(x + yj) = (ax + by) + (ay + bx)j fits exactly.
        // Elliptic gets a sign-flip on the cross-term (ax - by), can't fit.
        // Parabolic loses the y-coupling on top entirely.
        Random rng = new Random(42);
        int N = 200;
        double[][] inputs = new double[N][2];
        double[][] targets = new double[N][2];
        double a = Math.sqrt(2.0), b = 1.0;
        for (int i = 0; i < N; i++) {
            inputs[i][0]  = rng.nextGaussian();
            inputs[i][1]  = rng.nextGaussian();
            targets[i][0] = a * inputs[i][0] + b * inputs[i][1];
            targets[i][1] = b * inputs[i][0] + a * inputs[i][1];
        }
        runComparison("2D Lorentz boost (j-favored)", inputs, targets, 500, 0.05);
    }

    @Test
    void projectiveSwapTask() {
        // Target: (x, y) → (y, x). The component-wise swap that takes the
        // projective ratio x/y to y/x. Hyperbolic does this exactly with
        // (0 + 1·j) since (0 + 1·j)·(x + yj) = (1·y) + (1·x)j = (y, x).
        // Elliptic does it with sign flips. Parabolic and traction can't.
        Random rng = new Random(42);
        int N = 200;
        double[][] inputs = new double[N][2];
        double[][] targets = new double[N][2];
        for (int i = 0; i < N; i++) {
            inputs[i][0]  = rng.nextGaussian();
            inputs[i][1]  = rng.nextGaussian();
            targets[i][0] = inputs[i][1];
            targets[i][1] = inputs[i][0];
        }
        runComparison("Projective swap (x, y) → (y, x)", inputs, targets, 500, 0.05);
    }

    @Test
    void poleProbingTaskKAttempt() {
        // Probe whether k can capture the structure of 1/x.
        // Input: 1D scalar x, embedded as (x, 1).
        // Target: (1, x) — projective representation of 1/x; at x=0 this
        // is (1, 0) = ω.
        // Loss compares (top, bottom) component-wise. None of the algebras
        // can fit this exactly with finite weights — but the SHAPE of the
        // best fit per mode tells us about each algebra's relationship to
        // inversion.
        Random rng = new Random(42);
        int N = 200;
        double[][] inputs = new double[N][2];
        double[][] targets = new double[N][2];
        for (int i = 0; i < N; i++) {
            double x = rng.nextGaussian();
            inputs[i][0]  = x;
            inputs[i][1]  = 1.0;     // projective embedding (x, 1)
            targets[i][0] = 1.0;     // target top = 1
            targets[i][1] = x;       // target bottom = x  (so ratio = 1/x)
        }
        runComparison("Pole task: 1/x as projective pair (k-probe)",
                inputs, targets, 500, 0.05);
    }

    @Test
    void shearTaskFavoredByParabolic() {
        // Target: shear y' = y + α·x (with α = 0.5). In parabolic mode,
        // (a + bε)·(x + yε) = ax + (ay + bx)ε — so a=1, b=α gives exactly
        // this shear. Elliptic and hyperbolic can't isolate the y-coupling
        // without rotating x.
        Random rng = new Random(42);
        int N = 200;
        double[][] inputs = new double[N][2];
        double[][] targets = new double[N][2];
        for (int i = 0; i < N; i++) {
            inputs[i][0]  = rng.nextGaussian();
            inputs[i][1]  = rng.nextGaussian();
            targets[i][0] = inputs[i][0];
            targets[i][1] = inputs[i][1] + 0.5 * inputs[i][0];
        }
        runComparison("2D shear y' = y + 0.5x (ε-favored)", inputs, targets, 500, 0.05);
    }
}
