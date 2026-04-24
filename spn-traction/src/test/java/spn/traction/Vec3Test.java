package spn.traction;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Vec3Test extends TractionTestBase {

    @Nested
    class Arithmetic {
        @Test void addition() {
            assertEquals(true, run("""
                import numerics.vec3
                let a = Vec3(1.0, 2.0, 3.0)
                let b = Vec3(4.0, 5.0, 6.0)
                (a + b) == Vec3(5.0, 7.0, 9.0)
                """));
        }

        @Test void subtraction() {
            assertEquals(true, run("""
                import numerics.vec3
                let a = Vec3(5.0, 7.0, 9.0)
                let b = Vec3(1.0, 2.0, 3.0)
                (a - b) == Vec3(4.0, 5.0, 6.0)
                """));
        }

        @Test

        void unaryNegation() {
            assertEquals(true, run("""
                import numerics.vec3
                let v = Vec3(0.5, -0.75, 0.8333333333333334)
                (-v) == Vec3(-0.5, 0.75, -0.8333333333333334)
                """));
        }

        @Test void scalarMultiplication() {
            assertEquals(true, run("""
                import numerics.vec3
                let v = Vec3(1.0, 2.0, 3.0)
                (2.0 * v) == Vec3(2.0, 4.0, 6.0)
                """));
        }
    }

    @Nested
    class Products {
        @Test void dotProduct() {
            // (1,2,3) · (4,5,6) = 4+10+18 = 32
            assertEquals(true, run("""
                import numerics.vec3
                let a = Vec3(1.0, 2.0, 3.0)
                let b = Vec3(4.0, 5.0, 6.0)
                (a *_dot b) == 32.0
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
                let a = Vec3(1.0, 2.0, 3.0)
                let b = Vec3(4.0, 5.0, 6.0)
                (a *_cross b) == -(b *_cross a)
                """));
        }

        @Test void crossProductSelfIsZero() {
            assertEquals(true, run("""
                import numerics.vec3
                let v = Vec3(3.0, 3.5, 0.2)
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
                let v = Vec3(3.0, 4.0, 0.0)
                v.sqMag() == 25.0
                """));
        }
    }

    @Nested
    class Constants {
        @Test void zeroVector() {
            assertEquals(true, run("""
                import numerics.vec3
                Vec3.zero == Vec3(0.0, 0.0, 0.0)
                """));
        }

        @Test void basisVectors() {
            // ex · ey = 0 (orthogonal)
            assertEquals(true, run("""
                import numerics.vec3
                let x = Vec3.ex
                let y = Vec3.ey
                (x *_dot y) == 0.0
                """));
        }
    }
}
