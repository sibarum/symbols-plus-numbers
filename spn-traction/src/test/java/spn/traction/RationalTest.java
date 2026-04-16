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

/**
 * Canonical-form contract for {@code Rational}.
 *
 * Every value has a unique representation:
 *   (0, 0)      → (1, 1)   -- 0/0 = 1 under wheel-theoretic same-direction limit
 *   (0, d≠0)    → (0, 1)   -- canonical zero
 *   (n≠0, 0)    → (1, 0)   -- canonical omega (single point at infinity)
 *   (n, d) else → reduced by gcd, sign carried in the numerator
 *
 * Tests pin down that consumers depending on structural equality — Dict keys,
 * prove.spn:structEq, Array.contains, hashing — agree with {@code ==}.
 */
class RationalTest {

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
    class CanonicalForm {
        @Test void zeroCanonicalizes() {
            // Any (0, d≠0) → (0, 1)
            assertEquals(true, run("""
                import numerics.rational
                Rational(0, 5).0 == 0 && Rational(0, 5).1 == 1
                """));
        }

        @Test void omegaCanonicalizes() {
            // Any (n≠0, 0) → (1, 0) — wheel-theoretic: no signed infinity
            assertEquals(true, run("""
                import numerics.rational
                Rational(7, 0).0 == 1 && Rational(7, 0).1 == 0
                """));
        }

        @Test void negativeOmegaCanonicalizes() {
            // (-3, 0) → (1, 0): ∞ has no sign in wheel algebra
            assertEquals(true, run("""
                import numerics.rational
                Rational(-3, 0).0 == 1 && Rational(-3, 0).1 == 0
                """));
        }

        @Test void nanMapsToOne() {
            // 0/0 = 1 per user's same-direction limit choice
            assertEquals(true, run("""
                import numerics.rational
                Rational(0, 0).0 == 1 && Rational(0, 0).1 == 1
                """));
        }

        @Test void gcdReduces() {
            // (6, 8) → (3, 4)
            assertEquals(true, run("""
                import numerics.rational
                Rational(6, 8).0 == 3 && Rational(6, 8).1 == 4
                """));
        }

        @Test void signMigratesToNumerator() {
            // (3, -4) → (-3, 4)
            assertEquals(true, run("""
                import numerics.rational
                Rational(3, -4).0 == -3 && Rational(3, -4).1 == 4
                """));
        }

        @Test void doubleNegativeCanonicalizes() {
            // (-3, -4) → (3, 4)
            assertEquals(true, run("""
                import numerics.rational
                Rational(-3, -4).0 == 3 && Rational(-3, -4).1 == 4
                """));
        }
    }

    @Nested
    class StructuralEquality {
        @Test void equalFormsEqual() {
            // (6, 8) and (3, 4) canonicalize identically
            assertEquals(true, run("""
                import numerics.rational
                Rational(6, 8) == Rational(3, 4)
                """));
        }

        @Test void allZerosEqual() {
            assertEquals(true, run("""
                import numerics.rational
                Rational(0, 5) == Rational(0, 99) && Rational(0, 5) == Rational.zero
                """));
        }

        @Test void allOmegasEqual() {
            // (3, 0) == (-7, 0) — single point at infinity
            assertEquals(true, run("""
                import numerics.rational
                Rational(3, 0) == Rational(-7, 0) && Rational(3, 0) == Rational.omega
                """));
        }

        @Test void zeroAndOmegaDiffer() {
            // Leading paren would make SPN treat the line as a continuation
            // (same-line rule). Use a let-binding to keep the boolean on
            // its own line.
            assertEquals(false, run("""
                import numerics.rational
                let same = Rational.zero == Rational.omega
                same
                """));
        }

        @Test void nanEqualsOne() {
            // 0/0 canonicalizes to 1 so Rational(0,0) == Rational.one
            assertEquals(true, run("""
                import numerics.rational
                Rational(0, 0) == Rational.one
                """));
        }
    }

    @Nested
    class StructuralEqualityMatchesConsumers {
        // Dict keys in SPN are restricted to SpnSymbol, so we can't key dicts
        // on Rationals directly. But Array.contains and any user-defined
        // structEq still depend on == matching structural form.

        @Test void arrayContainsAgreesWithEq() {
            // contains([Rational(6,8)], Rational(3,4)) should be true because
            // both canonicalize to the same tuple.
            assertEquals(true, run("""
                import numerics.rational
                import Array (contains)
                contains([Rational(6, 8)], Rational(3, 4))
                """));
        }

        @Test void arrayContainsOmegaFromAnyForm() {
            assertEquals(true, run("""
                import numerics.rational
                import Array (contains)
                contains([Rational(7, 0)], Rational(-3, 0))
                """));
        }

        @Test
        @org.junit.jupiter.api.Disabled("Awaiting inference pass — arithmetic dispatch stripped in Phase 4.4")
        void structuralEqAcrossArithmetic() {
            // Arithmetic that should produce zero must produce canonical (0, 1).
            // Rational(3,4) - Rational(3,4) → (0, 1) structurally.
            assertEquals(true, run("""
                import numerics.rational
                let diff = Rational(3, 4) - Rational(3, 4)
                diff.0 == 0 && diff.1 == 1
                """));
        }
    }

    @Nested
    class WheelArithmetic {
        @Test void invOfZero() {
            // 1/0 = ω
            assertEquals(true, run("""
                import numerics.rational
                Rational.zero.inv() == Rational.omega
                """));
        }

        @Test void invOfOmega() {
            // 1/ω = 0
            assertEquals(true, run("""
                import numerics.rational
                Rational.omega.inv() == Rational.zero
                """));
        }

        @Test
        @org.junit.jupiter.api.Disabled("Awaiting inference pass — unary dispatch stripped in Phase 4.4")
        void negOfOmega() {
            // -ω = ω (unsigned infinity)
            assertEquals(true, run("""
                import numerics.rational
                -Rational.omega == Rational.omega
                """));
        }
    }
}
