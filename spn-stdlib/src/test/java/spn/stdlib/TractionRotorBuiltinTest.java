package spn.stdlib;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import spn.clifford.TractionRotor;
import spn.lang.ClasspathModuleLoader;
import spn.lang.SpnParser;
import spn.language.SpnModuleRegistry;
import spn.node.SpnRootNode;
import spn.type.SpnArrayValue;
import spn.type.SpnSymbolTable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the TractionRotor builtin type. Exercises factories,
 * tower-component accessors, sphere projection, 3D action, the Tier-1
 * 4-vector ops, parameter-space interpolation, and display.
 */
class TractionRotorBuiltinTest {

    private SpnSymbolTable symbolTable;
    private SpnModuleRegistry registry;

    @BeforeEach
    void setUp() {
        symbolTable = new SpnSymbolTable();
        registry = new SpnModuleRegistry();
        spn.stdlib.gen.StdlibModuleLoader.registerAll(registry);
        registry.addLoader(new ClasspathModuleLoader(null, symbolTable));
    }

    private Object run(String source) {
        SpnParser parser = new SpnParser(source, null, null, symbolTable, registry);
        SpnRootNode root = parser.parse();
        return root.getCallTarget().call();
    }

    @Test
    void factoryReturnsTractionRotor() {
        Object result = run("""
            import Clifford
            tractionRotor(0.0, 0.0)
            """);
        var rotor = assertInstanceOf(TractionRotor.class, result);
        assertEquals(1.0, rotor.a(), 1e-9);
        assertEquals(0.0, rotor.b(), 1e-9);
    }

    @Test
    void toSpherePointAsMethod() {
        // (theta_w, theta_u) = (pi/2, 0) -> (0, 1, 0)
        Object result = run("""
            import Clifford
            let r = tractionRotor(1.5707963267948966, 0.0)
            r.toSpherePoint()
            """);
        var arr = assertInstanceOf(SpnArrayValue.class, result);
        assertEquals(3, arr.length());
        assertTrue(Math.abs((Double) arr.get(0)) < 1e-9);
        assertTrue(Math.abs(((Double) arr.get(1)) - 1.0) < 1e-9);
        assertTrue(Math.abs((Double) arr.get(2)) < 1e-9);
    }

    @Test
    void applyAsMethodOnPlusX() {
        // apply(+x) must equal toSpherePoint() — verified by the Java main()
        Object result = run("""
            import Clifford
            let r = tractionRotor(0.7853981633974483, 4.71238898038469)
            r.apply([1.0, 0.0, 0.0])
            """);
        var arr = assertInstanceOf(SpnArrayValue.class, result);
        assertEquals(3, arr.length());
        // Reference row: (pi/4, 3*pi/2) -> (0.7071, 0, -0.7071)
        assertTrue(Math.abs(((Double) arr.get(0)) - 0.7071) < 1e-3, "x=" + arr.get(0));
        assertTrue(Math.abs((Double) arr.get(1)) < 1e-3,            "y=" + arr.get(1));
        assertTrue(Math.abs(((Double) arr.get(2)) + 0.7071) < 1e-3, "z=" + arr.get(2));
    }

    // ── Factories ──────────────────────────────────────────────────────

    @Test
    void identityFactory() {
        Object result = run("""
            import Clifford
            tractionRotorIdentity()
            """);
        var rotor = assertInstanceOf(TractionRotor.class, result);
        assertEquals(1.0, rotor.a(), 1e-9);
        assertEquals(0.0, rotor.b(), 1e-9);
        assertEquals(0.0, rotor.c(), 1e-9);
        assertEquals(0.0, rotor.d(), 1e-9);
    }

    @Test
    void fromTowerFactory() {
        Object result = run("""
            import Clifford
            tractionRotorFromTower(0.5, 0.6, 0.7, 0.8)
            """);
        var rotor = assertInstanceOf(TractionRotor.class, result);
        assertEquals(0.5, rotor.a(), 1e-9);
        assertEquals(0.6, rotor.b(), 1e-9);
        assertEquals(0.7, rotor.c(), 1e-9);
        assertEquals(0.8, rotor.d(), 1e-9);
        // fromTower-built rotors have no angles
        assertEquals(false, rotor.hasAngles());
    }

    // ── Component and Traction-basis accessors ─────────────────────────

    @Test
    void towerComponentA() {
        Object result = run("""
            import Clifford
            let r = tractionRotor(1.5707963267948966, 0.0)
            r.a()
            """);
        // (pi/2, 0) -> a = cos(pi/4) ≈ 0.7071
        assertEquals(0.7071, (Double) result, 1e-3);
    }

    @Test
    void thetaWAndThetaU() {
        double tw = (Double) run("""
            import Clifford
            tractionRotor(1.0, 2.0).thetaW()
            """);
        double tu = (Double) run("""
            import Clifford
            tractionRotor(1.0, 2.0).thetaU()
            """);
        assertEquals(1.0, tw, 1e-9);
        assertEquals(2.0, tu, 1e-9);
    }

    @Test
    void hasAngles() {
        boolean withAngles = (Boolean) run("""
            import Clifford
            tractionRotor(0.5, 0.5).hasAngles()
            """);
        boolean withoutAngles = (Boolean) run("""
            import Clifford
            tractionRotorFromTower(1.0, 0.0, 0.0, 0.0).hasAngles()
            """);
        assertTrue(withAngles);
        assertTrue(!withoutAngles);
    }

    @Test
    void tractionBasisCoefficients() {
        // (pi/4, 3*pi/2): traction expr = -0.3827 + 0.3827·g + 0.6533·g⁻¹ + 0.2706·g²
        // a + d = -0.6533 + 0.2706 = -0.3827 (scalar)
        // b + c = 0.6533 + (-0.2706) = 0.3827 (quarter)
        // b     = 0.6533 (negQuarter)
        // d     = 0.2706 (half)
        double scalar = (Double) run("""
            import Clifford
            tractionRotor(0.7853981633974483, 4.71238898038469).scalarCoeff()
            """);
        double quarter = (Double) run("""
            import Clifford
            tractionRotor(0.7853981633974483, 4.71238898038469).quarterCoeff()
            """);
        double negQuarter = (Double) run("""
            import Clifford
            tractionRotor(0.7853981633974483, 4.71238898038469).negQuarterCoeff()
            """);
        double half = (Double) run("""
            import Clifford
            tractionRotor(0.7853981633974483, 4.71238898038469).halfCoeff()
            """);
        assertEquals(-0.3827, scalar, 1e-3);
        assertEquals(0.3827, quarter, 1e-3);
        assertEquals(0.6533, negQuarter, 1e-3);
        assertEquals(0.2706, half, 1e-3);
    }

    // ── 4-vector ops ───────────────────────────────────────────────────

    @Test
    void normOfIdentityIsOne() {
        double n = (Double) run("""
            import Clifford
            tractionRotorIdentity().norm()
            """);
        assertEquals(1.0, n, 1e-9);
    }

    @Test
    void normSquaredOfUnitRotor() {
        // Any fromAngles rotor has |R|² = 1
        double nsq = (Double) run("""
            import Clifford
            tractionRotor(0.7, 1.3).normSquared()
            """);
        assertEquals(1.0, nsq, 1e-9);
    }

    @Test
    void scaleAndNorm() {
        double n = (Double) run("""
            import Clifford
            tractionRotor(0.5, 1.0).scale(2.0).norm()
            """);
        assertEquals(2.0, n, 1e-9);
    }

    @Test
    void negateThenAddIsZero() {
        double n = (Double) run("""
            import Clifford
            let r = tractionRotor(0.5, 1.0)
            r.add(r.negate()).norm()
            """);
        assertEquals(0.0, n, 1e-12);
    }

    @Test
    void subtractIsAddNegate() {
        double n = (Double) run("""
            import Clifford
            let r = tractionRotor(0.5, 1.0)
            r.subtract(r).norm()
            """);
        assertEquals(0.0, n, 1e-12);
    }

    @Test
    void dotOfRotorWithItselfIsNormSquared() {
        double dot = (Double) run("""
            import Clifford
            let r = tractionRotor(0.5, 1.0)
            r.dot(r)
            """);
        assertEquals(1.0, dot, 1e-9);
    }

    @Test
    void normalizeUnitRotorIsSelf() {
        // normalize() on an already-unit rotor returns one with the same components
        double n = (Double) run("""
            import Clifford
            tractionRotor(0.7, 1.3).normalize().norm()
            """);
        assertEquals(1.0, n, 1e-9);
    }

    @Test
    void approxEqualsSelf() {
        boolean eq = (Boolean) run("""
            import Clifford
            let r = tractionRotor(0.5, 1.0)
            r.approxEquals(r, 0.000001)
            """);
        assertTrue(eq);
    }

    @Test
    void approxNotEqualDifferent() {
        boolean eq = (Boolean) run("""
            import Clifford
            tractionRotor(0.5, 1.0).approxEquals(tractionRotor(1.0, 0.5), 0.0001)
            """);
        assertTrue(!eq);
    }

    // ── interpAngles ───────────────────────────────────────────────────

    @Test
    void interpAnglesAtZeroIsThis() {
        Object r = run("""
            import Clifford
            let a = tractionRotor(0.5, 1.0)
            let b = tractionRotor(2.0, 3.0)
            a.interpAngles(b, 0.0)
            """);
        var rotor = assertInstanceOf(TractionRotor.class, r);
        assertEquals(0.5, rotor.thetaW(), 1e-9);
        assertEquals(1.0, rotor.thetaU(), 1e-9);
    }

    @Test
    void interpAnglesAtOneIsOther() {
        Object r = run("""
            import Clifford
            let a = tractionRotor(0.5, 1.0)
            let b = tractionRotor(1.5, 2.0)
            a.interpAngles(b, 1.0)
            """);
        var rotor = assertInstanceOf(TractionRotor.class, r);
        assertEquals(1.5, rotor.thetaW(), 1e-9);
        assertEquals(2.0, rotor.thetaU(), 1e-9);
    }

    // ── Display ────────────────────────────────────────────────────────

    @Test
    void toTractionExpressionForIdentity() {
        // identity has tower (1, 0, 0, 0); traction expr is just "1.0000"
        String expr = (String) run("""
            import Clifford
            tractionRotorIdentity().toTractionExpression()
            """);
        assertTrue(expr.startsWith("1.0000"), "got: " + expr);
    }
}
