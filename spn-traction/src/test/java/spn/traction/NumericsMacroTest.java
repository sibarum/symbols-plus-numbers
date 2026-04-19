package spn.traction;

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

class NumericsMacroTest {

    private SpnSymbolTable symbolTable;
    private SpnModuleRegistry registry;

    @BeforeEach
    void setUp() {
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
    class RationalArithmetic {

        @Test void basicConstructAndRead() {
            assertEquals(true, run("""
                import Numerics

                let num = constructRatComplex(31)
                type Rat = num.rational

                let q = Rat(3, 4)
                q.0 == 3 && q.1 == 4
                """));
        }

        @Test void additionWithinBudget() {
            // Small operands — no shift expected, canonical form preserved
            assertEquals(true, run("""
                import Numerics

                let num = constructRatComplex(31)
                type Rat = num.rational

                let a = Rat(1, 2)
                let b = Rat(1, 3)
                let c = a + b
                -- 1/2 + 1/3 = 5/6
                c.0 == 5 && c.1 == 6
                """));
        }

        @Test void multiplicationWithinBudget() {
            assertEquals(true, run("""
                import Numerics

                let num = constructRatComplex(31)
                type Rat = num.rational

                let a = Rat(2, 3)
                let b = Rat(3, 4)
                let c = a * b
                -- 2/3 * 3/4 = 6/12; canonical with no gcd reduction = (6, 12)
                c.0 == 6 && c.1 == 12
                """));
        }

        @Test void intPromotionIntoRational() {
            // promote int -> Rat should fire when int meets a Rat-typed op
            assertEquals(true, run("""
                import Numerics

                let num = constructRatComplex(31)
                type Rat = num.rational

                let a = Rat(3, 4)
                let b = a + 1      -- 1 promotes to Rat(1, 1) -> 3/4 + 1/1 = 7/4
                b.0 == 7 && b.1 == 4
                """));
        }

        @Test void projectiveZeroEquality() {
            // Two residual-zero values with different denominators are equal via ==
            assertEquals(true, run("""
                import Numerics

                let num = constructRatComplex(31)
                type Rat = num.rational

                let z1 = Rat(0, 3)
                let z2 = Rat(0, 7)
                z1 == z2
                """));
        }

        @Test void projectiveOmegaEquality() {
            assertEquals(true, run("""
                import Numerics

                let num = constructRatComplex(31)
                type Rat = num.rational

                let w1 = Rat(3, 0)
                let w2 = Rat(7, 0)
                w1 == w2
                """));
        }
    }

    @Nested
    class BitBudgetShift {

        @Test void tightBudgetShiftsLargeValues() {
            // At a 10-bit budget, anything larger than 1023 gets shifted.
            // 2048/4 with a 10-bit budget should shift by 1: (1024, 2).
            assertEquals(true, run("""
                import Numerics

                let num = constructRatComplex(10)
                type Rat = num.rational

                let q = Rat(2048, 4)
                -- maxBit = bitWidth(2048) = 12; k = 12 - 10 = 2
                -- (2048+2)>>2 = 512, (4+2)>>2 = 1
                q.0 == 512 && q.1 == 1
                """));
        }

        @Test void separateBudgetsDoNotConflict() {
            // Two macro invocations with different budgets must produce
            // distinct, non-conflicting types.
            assertEquals(true, run("""
                import Numerics

                let n31 = constructRatComplex(31)
                let n10 = constructRatComplex(10)

                type Rat31 = n31.rational
                type Rat10 = n10.rational

                let a = Rat31(100, 200)
                let b = Rat10(3, 4)
                a.1 == 200 && b.1 == 4
                """));
        }
    }

    @Nested
    class ComplexArithmetic {

        @Test void constructAndCartesian() {
            // z = scale 2 * (1 + 0i) / sqrt(1) — but we use (scale, tan) directly.
            // (scale=1, tan=0) means angle 0, so z = 1 * (1, 0) = 1.
            assertEquals(true, run("""
                import Numerics

                let num = constructRatComplex(31)
                type Rat = num.rational
                type Cpx = num.complex

                let z = Cpx(Rat(1, 1), Rat(0, 1))
                let (re, im) = z.toCartesian()
                re == Rat(1, 1) && im == Rat(0, 1)
                """));
        }

        @Test void multiplicationAddsAngles() {
            // tan addition formula: if t1 = 0 and t2 = 0, then tan(θ1+θ2) = 0
            assertEquals(true, run("""
                import Numerics

                let num = constructRatComplex(31)
                type Rat = num.rational
                type Cpx = num.complex

                let z1 = Cpx(Rat(2, 1), Rat(0, 1))
                let z2 = Cpx(Rat(3, 1), Rat(0, 1))
                let z = z1 * z2
                -- scales multiply, tangents combine: scale=6, tan=0
                z.scale == Rat(6, 1) && z.tangent == Rat(0, 1)
                """));
        }

        @Test void pythagoreanProductCrossingPiOver2() {
            // (3+4i) · (5+12i) = -33 + 56i. Mirrors the tcomplex.spn test —
            // exposes the quadrant bug if the Rational sign migration drops
            // the denominator sign without compensating in scale.
            assertEquals(true, run("""
                import Numerics

                let num = constructRatComplex(31)
                type Rat = num.rational
                type Cpx = num.complex

                let a = Cpx(Rat(1, 1), Rat(4, 3))    -- 3 + 4i
                let b = Cpx(Rat(1, 1), Rat(12, 5))   -- 5 + 12i
                let prod = a * b
                let (re, im) = prod.toCartesian()
                re == Rat(-33, 1) && im == Rat(56, 1)
                """));
        }

        @Test void divisionRoundTripsMultiplication() {
            assertEquals(true, run("""
                import Numerics

                let num = constructRatComplex(31)
                type Rat = num.rational
                type Cpx = num.complex

                let a = Cpx(Rat(1, 1), Rat(4, 3))
                let b = Cpx(Rat(1, 1), Rat(12, 5))
                let back = (a * b) / b
                back == a
                """));
        }
    }
}
