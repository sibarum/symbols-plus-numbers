package spn.traction;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Synthetic shape classification — circle vs square vs triangle from
 * a K-point outline rotated to a random orientation in 3D. The task is
 * naturally rotation-equivariant, unlike the prime-factorization phase
 * test which was effectively scalar.
 *
 * <p>Each test runs a depth (1, 2, or 4 layers) at a fixed
 * hyperparameter setting and reports loss-before vs loss-after, then
 * asserts a loose floor (loss-after &lt; loss-before). The numbers
 * matter more than the assertions — the goal is to learn whether plain
 * identity-activation quaternion networks scale with depth on a real
 * geometric task.
 */
@Disabled // Takes too long to run, too resource intensive.
class ShapeTest extends TractionTestBase {

    private double measure(String netExpr, int epochs, double lr) {
        String trainScript = """
            import factor.shapes
            let net0 = """ + netExpr + """

            let trained = shapeTrain(net0, 1, 8, 30, """ + epochs + """
            , """ + lr + """
            )
            shapeAvgLoss(trained, 1000, 8, 60)
            """;
        return ((Number) run(trainScript)).doubleValue();
    }

    private double measureBefore(String netExpr) {
        String beforeScript = """
            import factor.shapes
            let net0 = """ + netExpr + """

            shapeAvgLoss(net0, 1000, 8, 60)
            """;
        return ((Number) run(beforeScript)).doubleValue();
    }

    @Test void shapeDepth1() {
        String net = "buildShapeNet1(8)";
        double before = measureBefore(net);
        double after = measure(net, 30, 0.05);
        System.out.printf("[shapeDepth1]  before=%.4f  after=%.4f%n", before, after);
        assertEquals(true, after < before, "depth-1 didn't reduce loss");
    }

    @Test void shapeDepth2() {
        String net = "buildShapeNet2(8, 8)";
        double before = measureBefore(net);
        double after = measure(net, 30, 0.05);
        System.out.printf("[shapeDepth2]  before=%.4f  after=%.4f%n", before, after);
        assertEquals(true, after < before, "depth-2 didn't reduce loss");
    }

    @Test void shapeDepth4() {
        String net = "buildShapeNet4(8, 8)";
        double before = measureBefore(net);
        double after = measure(net, 30, 0.05);
        System.out.printf("[shapeDepth4]  before=%.4f  after=%.4f%n", before, after);
        assertEquals(true, after < before, "depth-4 didn't reduce loss");
    }

    // ── Identity-init sanity tests ─────────────────────────────────────
    // All weights initialized to 1+0i+0j+0k, biases to 0. At init every
    // neuron computes Σx_j (sum of its inputs), so all neurons in a layer
    // see the same value. Question: does the algebraic update rule break
    // that symmetry, or do all neurons stay locked together (dead) — and
    // separately, does the network escape the predict-zero attractor that
    // the random-init runs collapse into. Reports only; no convergence
    // assertion.

    @Test void shapeDepth1Identity() {
        String net = "buildShapeNet1Identity(8)";
        double before = measureBefore(net);
        double after = measure(net, 30, 0.05);
        System.out.printf("[shapeDepth1Identity]  before=%.4f  after=%.4f%n", before, after);
    }

    @Test void shapeDepth2Identity() {
        String net = "buildShapeNet2Identity(8, 8)";
        double before = measureBefore(net);
        double after = measure(net, 30, 0.05);
        System.out.printf("[shapeDepth2Identity]  before=%.4f  after=%.4f%n", before, after);
    }

    @Test void shapeDepth4Identity() {
        String net = "buildShapeNet4Identity(8, 8)";
        double before = measureBefore(net);
        double after = measure(net, 30, 0.05);
        System.out.printf("[shapeDepth4Identity]  before=%.4f  after=%.4f%n", before, after);
    }
}
