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


class Vec3Test {

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
    class Arithmetic {
        @Test void addition() {
            assertEquals(true, run("""
                import numerics.vec3
                let a = Vec3(Rational(1,1), Rational(2,1), Rational(3,1))
                let b = Vec3(Rational(4,1), Rational(5,1), Rational(6,1))
                (a + b) == Vec3(Rational(5,1), Rational(7,1), Rational(9,1))
                """));
        }

        @Test void subtraction() {
            assertEquals(true, run("""
                import numerics.vec3
                let a = Vec3(Rational(5,1), Rational(7,1), Rational(9,1))
                let b = Vec3(Rational(1,1), Rational(2,1), Rational(3,1))
                (a - b) == Vec3(Rational(4,1), Rational(5,1), Rational(6,1))
                """));
        }

        @Test
        
        void unaryNegation() {
            assertEquals(true, run("""
                import numerics.vec3
                let v = Vec3(Rational(1,2), Rational(-3,4), Rational(5,6))
                (-v) == Vec3(Rational(-1,2), Rational(3,4), Rational(-5,6))
                """));
        }

        @Test void scalarMultiplication() {
            assertEquals(true, run("""
                import numerics.vec3
                let v = Vec3(Rational(1,1), Rational(2,1), Rational(3,1))
                (Rational(2,1) * v) == Vec3(Rational(2,1), Rational(4,1), Rational(6,1))
                """));
        }
    }

    @Nested
    class Products {
        @Test void dotProduct() {
            // (1,2,3) · (4,5,6) = 4+10+18 = 32
            assertEquals(true, run("""
                import numerics.vec3
                let a = Vec3(Rational(1,1), Rational(2,1), Rational(3,1))
                let b = Vec3(Rational(4,1), Rational(5,1), Rational(6,1))
                (a *_dot b) == Rational(32,1)
                """));
        }

        @Test void crossProduct() {
            // (1,0,0) × (0,1,0) = (0,0,1)
            assertEquals(true, run("""
                import numerics.vec3
                let x = Vec3.ex
                let y = Vec3.ey
                (x *_cross y) == Vec3.ez
                """));
        }

        @Test
        
        void crossProductAnticommutative() {
            // a × b == -(b × a)
            assertEquals(true, run("""
                import numerics.vec3
                let a = Vec3(Rational(1,1), Rational(2,1), Rational(3,1))
                let b = Vec3(Rational(4,1), Rational(5,1), Rational(6,1))
                (a *_cross b) == -(b *_cross a)
                """));
        }

        @Test void crossProductSelfIsZero() {
            assertEquals(true, run("""
                import numerics.vec3
                let v = Vec3(Rational(3,1), Rational(7,2), Rational(1,5))
                (v *_cross v) == Vec3.zero
                """));
        }
    }

    @Nested
    class Methods {
        @Test void squaredMagnitude() {
            // |(3,4,0)|² = 9+16+0 = 25
            assertEquals(true, run("""
                import numerics.vec3
                let v = Vec3(Rational(3,1), Rational(4,1), Rational(0,1))
                v.sqMag() == Rational(25,1)
                """));
        }
    }

    @Nested
    class Constants {
        @Test void zeroVector() {
            assertEquals(true, run("""
                import numerics.vec3
                Vec3.zero == Vec3(Rational(0,1), Rational(0,1), Rational(0,1))
                """));
        }

        @Test void basisVectors() {
            // ex · ey = 0 (orthogonal)
            assertEquals(true, run("""
                import numerics.vec3
                let x = Vec3.ex
                let y = Vec3.ey
                (x *_dot y) == Rational(0,1)
                """));
        }
    }
}
