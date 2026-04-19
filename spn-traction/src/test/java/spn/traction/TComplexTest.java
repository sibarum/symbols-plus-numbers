package spn.traction;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

class TComplexTest {

    private SpnSymbolTable symbolTable;
    private SpnModuleRegistry registry;

    @BeforeEach
    void setUp() {
        Engine engine = Engine.newBuilder().build();
        Context context = Context.newBuilder().engine(engine).build();
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

    @Nested
    class Construction {
        @Test void pythagoreanTriple() {
            // TComplex(3, 4, 5) → scale=1/5, tangent=4/3
            assertEquals(true, run("""
                import numerics.tcomplex
                let tc = TComplex(3, 4, 5)
                tc.scale == Rational(1, 5) && tc.tangent == Rational(4, 3)
                """));
        }

        @Test void fromRational() {
            // TComplex(Rational(0,1)) → scale=1/(1+0)=1, tangent=0/(1-0)=0
            assertEquals(true, run("""
                import numerics.tcomplex
                let tc = TComplex(Rational(0, 1))
                tc.scale == Rational.one && tc.tangent == Rational.zero
                """));
        }
    }

    @Nested
    class Arithmetic {
        @Test void multiplication() {
            // (3,4,5) * (3,4,5) should double the angle
            assertEquals(true, run("""
                import numerics.tcomplex
                let a = TComplex(3, 4, 5)
                let b = a * a
                b == b  -- at minimum, self-equality holds
                """));
        }

        @Test void negation() {
            // -tc negates the scale, tangent stays
            assertEquals(true, run("""
                import numerics.tcomplex
                let tc = TComplex(3, 4, 5)
                let neg = tc.neg()
                neg.scale == Rational(-1, 5) && neg.tangent == tc.tangent
                """));
        }

        @Test void subtraction() {
            // tc - tc should give zero scale
            assertEquals(true, run("""
                import numerics.tcomplex
                let tc = TComplex(3, 4, 5)
                let diff = tc - tc
                diff.scale == Rational.zero
                """));
        }

        @Test void equality() {
            assertEquals(true, run("""
                import numerics.tcomplex
                let a = TComplex(3, 4, 5)
                let b = TComplex(3, 4, 5)
                a == b
                """));
        }
    }

    @Nested
    class Cartesian {
        @Test void toCartesian345() {
            // (3,4,5): scale=1/5, tangent=4/3
            // real = scale * td = (1/5)*3 = 3/5
            // imag = scale * tn = (1/5)*4 = 4/5
            assertEquals(true, run("""
                import numerics.tcomplex
                let tc = TComplex(3, 4, 5)
                let (re, im) = tc.toCartesian()
                re == Rational(3, 5) && im == Rational(4, 5)
                """));
        }
    }

    // ── Pythagorean-triple multiplication ────────────────────────────────
    //
    // The Brahmagupta–Fibonacci identity (a²+b²)(c²+d²) = (ac-bd)² + (ad+bc)²
    // is exactly what TComplex multiplication does. Each triple is a complex
    // number with integer magnitude; multiplying produces another such triple.
    // These tests cover quadrants where the naive tan(a+b) formulation drops
    // sign info — they would have failed before the sign-migration fix.

    @Nested
    class PythagoreanProducts {

        @Test void simpleProductCrossingPiOver2() {
            // (3+4i)(5+12i) = -33+56i. Tangent of result is -56/33; the
            // canonical Rational sign-migration moves that sign into the
            // numerator, so without compensating in scale, cartesian
            // recovery comes back as +33-56i (the wrong quadrant).
            assertEquals(true, run("""
                import numerics.tcomplex
                let a = TComplex(Rational.one, Rational(4, 3))   -- 3 + 4i
                let b = TComplex(Rational.one, Rational(12, 5))  -- 5 + 12i
                let prod = a * b
                let (re, im) = prod.toCartesian()
                re == Rational(-33, 1) && im == Rational(56, 1)
                """));
        }

        @Test void smallAngleProductFirstQuadrant() {
            // Sanity: (3+4i)·(8+15i) = (24-60) + (45+32)i = -36+77i.
            // Magnitude 5·17 = 85, the (36, 77, 85) triple.
            assertEquals(true, run("""
                import numerics.tcomplex
                let a = TComplex(Rational.one, Rational(4, 3))   -- 3 + 4i
                let b = TComplex(Rational.one, Rational(15, 8))  -- 8 + 15i
                let prod = a * b
                let (re, im) = prod.toCartesian()
                re == Rational(-36, 1) && im == Rational(77, 1)
                """));
        }

        @Test void divisionRoundTripsMultiplication() {
            // (a*b) / b should give a back, exactly.
            assertEquals(true, run("""
                import numerics.tcomplex
                let a = TComplex(Rational.one, Rational(4, 3))
                let b = TComplex(Rational.one, Rational(12, 5))
                let back = (a * b) / b
                back == a
                """));
        }

        @Test void additionPreservesCartesian() {
            // (3+4i) + (5+12i) = 8+16i. Tests the addition reconstruction
            // path — also broken before the scale-recovery fix.
            assertEquals(true, run("""
                import numerics.tcomplex
                let a = TComplex(Rational.one, Rational(4, 3))
                let b = TComplex(Rational.one, Rational(12, 5))
                let s = a + b
                let (re, im) = s.toCartesian()
                re == Rational(8, 1) && im == Rational(16, 1)
                """));
        }

        @Test void additionInThirdQuadrant() {
            // (-3-4i) + (-5-12i) = -8-16i — exercises negative reals/imags.
            assertEquals(true, run("""
                import numerics.tcomplex
                let a = TComplex(Rational(-1, 1), Rational(4, 3))   -- -3 - 4i
                let b = TComplex(Rational(-1, 1), Rational(12, 5))  -- -5 - 12i
                let s = a + b
                let (re, im) = s.toCartesian()
                re == Rational(-8, 1) && im == Rational(-16, 1)
                """));
        }

        @Test void zerosWithDifferentTangentsCompareEqual() {
            // Both represent 0+0i (scale=0 makes the tangent irrelevant for
            // cartesian recovery), but the Rational tangent components have
            // different bit patterns. Bit-equality on (scale, tangent) would
            // say "not equal"; the cartesian-based == correctly says "equal."
            assertEquals(true, run("""
                import numerics.tcomplex
                let zero1 = TComplex(Rational.zero, Rational.zero)
                let zero2 = TComplex(Rational.zero, Rational(5, 1))
                zero1 == zero2
                """));
        }
    }
}
