package spn.traction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercise makeNeuralNet<T> end-to-end with TractionQuaternion.
 *
 * <p>Covers: macro expansion + emit, Net construction via pushLayer,
 * single-layer and stacked forward pass, and algebraic trainStep
 * reducing the output-vs-target error.
 */
class MakeNeuralNetTest extends TractionTestBase {

    private static final String SETUP = """
            import factor.neural
            import factor.neural_traction
            import numerics.traction
            import Math (sqrt)

            type QNet = makeNeuralNet<TractionQuaternion>
            type QVec = Array<TractionQuaternion>
            type QMat = Array<QVec>

            -- Helpers: construct a single weight row and a bias vector.
            pure row1(TractionQuaternion) -> QVec = (w0) {
              QVec().push(w0)
            }
            pure mat1x1(TractionQuaternion) -> QMat = (w00) {
              QMat().push(row1(w00))
            }
            pure bias1(TractionQuaternion) -> QVec = (b0) {
              QVec().push(b0)
            }

            -- Cartesian L2 distance between an output-at-index-0 and target.
            -- Monotonically decreases as output approaches target in all four
            -- quaternion components, regardless of magnitude.
            pure cartDist(TractionQuaternion, TractionQuaternion) -> float = (a, b) {
              let (aw, ax, ay, az) = cartesianOf(a)
              let (bw, bx, by, bz) = cartesianOf(b)
              let dw = aw - bw
              let dx = ax - bx
              let dy = ay - by
              let dz = az - bz
              sqrt(dw*dw + dx*dx + dy*dy + dz*dz)
            }
            """;

    @Test void macroEmitsNetAndPushLayerWorks() {
        assertEquals(1L, run(SETUP + """
            let w = quatFromCart4(1.0, 0.0, 0.0, 0.0)
            let b = quatFromCart4(0.0, 0.0, 0.0, 0.0)
            let net = QNet().pushLayer(mat1x1(w), bias1(b))
            net.layers.length()
            """));
    }

    @Test void singleLayerForwardIdentityQuaternion() {
        // Test renormalize alone
        double renormMag = ((Number) run("""
            import factor.neural_traction
            let w = quatFromCart4(1.0, 0.0, 0.0, 0.0)
            magnitudeFloat(renormalize(w))
            """)).doubleValue();
        System.out.println("[DEBUG] renormalize w magnitude: " + renormMag);

        // Raw *, THEN renormalize
        double starRenormMag = ((Number) run("""
            import factor.neural_traction
            import numerics.traction
            let w = quatFromCart4(1.0, 0.0, 0.0, 0.0)
            let x = quatFromCart4(0.6, 0.8, 0.0, 0.0)
            let p = w * x
            let pr = renormalize(p)
            magnitudeFloat(pr)
            """)).doubleValue();
        System.out.println("[DEBUG] (w*x renormalized) magnitude: " + starRenormMag);

        // Use TC direct construction like in NeuralSignatureTest's i*j=k test
        double explicitMag = ((Number) run("""
            import factor.neural_traction
            import numerics.traction

            let tc0 = TractionComplex(Traction(0,1), Traction(0,1))
            let tc1 = TractionComplex(Traction(1,1), Traction(0,1))
            let w = TractionQuaternion(tc1, tc0)
            let x = quatFromCart4(0.6, 0.8, 0.0, 0.0)
            let p = w * x
            magnitudeFloat(renormalize(p))
            """)).doubleValue();
        System.out.println("[DEBUG] explicit w * quatFromCart4 x: " + explicitMag);

        // Simpler: does quatFromCart4 produce magnitude 1 identity?
        double idMag = ((Number) run("""
            import factor.neural_traction
            let w = quatFromCart4(1.0, 0.0, 0.0, 0.0)
            magnitudeFloat(w)
            """)).doubleValue();
        System.out.println("[DEBUG] identity w magnitude: " + idMag);

        double xMag = ((Number) run("""
            import factor.neural_traction
            let x = quatFromCart4(0.6, 0.8, 0.0, 0.0)
            magnitudeFloat(x)
            """)).doubleValue();
        System.out.println("[DEBUG] x magnitude: " + xMag);

        // Use * operator directly instead of .compose:
        double starMag = ((Number) run("""
            import factor.neural_traction
            import numerics.traction
            let w = quatFromCart4(1.0, 0.0, 0.0, 0.0)
            let x = quatFromCart4(0.6, 0.8, 0.0, 0.0)
            let p = w * x
            magnitudeFloat(p)
            """)).doubleValue();
        System.out.println("[DEBUG] raw * magnitude: " + starMag);

        // Does compose work WITHOUT the macro import?
        double composeWithoutMacro = ((Number) run("""
            import factor.neural_traction
            let w = quatFromCart4(1.0, 0.0, 0.0, 0.0)
            let x = quatFromCart4(0.6, 0.8, 0.0, 0.0)
            magnitudeFloat(w.compose(x))
            """)).doubleValue();
        System.out.println("[DEBUG] compose without macro: " + composeWithoutMacro);

        // Confirm pieces of the pipeline: raw compose, raw aggregate, activate.
        double composeMag = ((Number) run(SETUP + """
            let w = quatFromCart4(1.0, 0.0, 0.0, 0.0)
            let x = quatFromCart4(0.6, 0.8, 0.0, 0.0)
            magnitudeFloat(w.compose(x))
            """)).doubleValue();
        System.out.println("[DEBUG] compose magnitude: " + composeMag);

        double aggMag = ((Number) run(SETUP + """
            let b = quatFromCart4(0.0, 0.0, 0.0, 0.0)
            let c = quatFromCart4(0.6, 0.8, 0.0, 0.0)
            magnitudeFloat(b.aggregate(c))
            """)).doubleValue();
        System.out.println("[DEBUG] aggregate magnitude: " + aggMag);

        double actMag = ((Number) run(SETUP + """
            let x = quatFromCart4(0.6, 0.8, 0.0, 0.0)
            magnitudeFloat(x.activate())
            """)).doubleValue();
        System.out.println("[DEBUG] activate magnitude: " + actMag);

        double netMag = ((Number) run(SETUP + """
            let w = quatFromCart4(1.0, 0.0, 0.0, 0.0)
            let b = quatFromCart4(0.0, 0.0, 0.0, 0.0)
            let net = QNet().pushLayer(mat1x1(w), bias1(b))

            let x = quatFromCart4(0.6, 0.8, 0.0, 0.0)
            let inputs = QVec().push(x)
            let outputs = net.forward(inputs)
            magnitudeFloat(outputs[0])
            """)).doubleValue();
        System.out.println("[DEBUG] net forward magnitude: " + netMag);
        assertEquals(true, netMag > 0.99 && netMag < 1.01, "net mag was " + netMag);
    }

    @Test void minimalTrainStepCall() {
        // Just call trainStep with simplest args and see what happens.
        assertEquals(true, run(SETUP + """
            let w = quatFromCart4(1.0, 0.0, 0.0, 0.0)
            let b = quatFromCart4(0.0, 0.0, 0.0, 0.0)
            let net = QNet().pushLayer(mat1x1(w), bias1(b))

            let x = quatFromCart4(1.0, 0.0, 0.0, 0.0)
            let inputs = QVec().push(x)
            let targets = QVec().push(x)

            let net2 = net.trainStep(inputs, targets, 0.1)
            net2.layers.length() == 1
            """));
    }

    @Test void trainStepReducesSingleLayerError() {
        // 1→1 net. Start with identity weight, target the "rotate by π/2"
        // quaternion (0,1,0,0). After a few training steps loss should
        // strictly decrease.
        assertEquals(true, run(SETUP + """
            pure lossOne(QNet, QVec, TractionQuaternion) -> float = (n, inputs, target) {
              cartDist(n.forward(inputs)[0], target)
            }

            let w0 = quatFromCart4(1.0, 0.0, 0.0, 0.0)
            let b0 = quatFromCart4(0.0, 0.0, 0.0, 0.0)
            let net0 = QNet().pushLayer(mat1x1(w0), bias1(b0))

            let x = quatFromCart4(1.0, 0.0, 0.0, 0.0)
            let target = quatFromCart4(0.0, 1.0, 0.0, 0.0)
            let inputs = QVec().push(x)
            let targets = QVec().push(target)

            let lossBefore = lossOne(net0, inputs, target)
            let net1 = net0.trainStep(inputs, targets, 0.3)
            let net2 = net1.trainStep(inputs, targets, 0.3)
            let net3 = net2.trainStep(inputs, targets, 0.3)
            let lossAfter = lossOne(net3, inputs, target)
            lossAfter < lossBefore
            """));
    }

    @Test void stackedForwardRunsTwoLayers() {
        // 1→1→1 net with identity weights. Forward should still propagate a
        // unit-magnitude input to a unit-magnitude output across two layers.
        assertEquals(true, run(SETUP + """
            let w = quatFromCart4(1.0, 0.0, 0.0, 0.0)
            let b = quatFromCart4(0.0, 0.0, 0.0, 0.0)
            let net = QNet()
                       .pushLayer(mat1x1(w), bias1(b))
                       .pushLayer(mat1x1(w), bias1(b))

            let x = quatFromCart4(0.6, 0.8, 0.0, 0.0)
            let inputs = QVec().push(x)
            let outputs = net.forward(inputs)
            magnitudeFloat(outputs[0]) > 0.99
            """));
    }

    @Test void stackedTrainStepReducesError() {
        // Two-layer 1→1→1 net. Error at output has to propagate backward
        // through the second layer to reach the first. Loss should decrease.
        assertEquals(true, run(SETUP + """
            pure lossOne(QNet, QVec, TractionQuaternion) -> float = (n, inputs, target) {
              cartDist(n.forward(inputs)[0], target)
            }

            let w = quatFromCart4(1.0, 0.0, 0.0, 0.0)
            let b = quatFromCart4(0.0, 0.0, 0.0, 0.0)
            let net0 = QNet()
                       .pushLayer(mat1x1(w), bias1(b))
                       .pushLayer(mat1x1(w), bias1(b))

            let x = quatFromCart4(1.0, 0.0, 0.0, 0.0)
            let target = quatFromCart4(0.0, 1.0, 0.0, 0.0)
            let inputs = QVec().push(x)
            let targets = QVec().push(target)

            let lossBefore = lossOne(net0, inputs, target)
            let net1 = net0.trainStep(inputs, targets, 0.2)
            let net2 = net1.trainStep(inputs, targets, 0.2)
            let net3 = net2.trainStep(inputs, targets, 0.2)
            let net4 = net3.trainStep(inputs, targets, 0.2)
            let net5 = net4.trainStep(inputs, targets, 0.2)
            let lossAfter = lossOne(net5, inputs, target)
            lossAfter < lossBefore
            """));
    }
}
