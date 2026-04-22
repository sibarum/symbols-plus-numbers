package spn.traction;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TRot3Test extends TractionTestBase {

    @Nested
    class Identity {
        @Test void idTimesId() {
            // id * id = id
            assertEquals(true, run("""
                import numerics.trot3
                let id = TRot3(Rational.zero, Rational.zero, Rational.zero)
                let r = id * id
                r.xy == Rational.zero && r.yz == Rational.zero && r.zx == Rational.zero
                """));
        }

        @Test void rotTimesInverse() {
            // r * r.inv() should give identity
            assertEquals(true, run("""
                import numerics.trot3
                let r = TRot3(Rational(1,2), Rational(1,3), Rational(1,4))
                let result = r * r.inv()
                result.xy == Rational.zero && result.yz == Rational.zero && result.zx == Rational.zero
                """));
        }
    }

    @Nested
    class Inverse {
        @Test void inverseNegates() {
            // inv negates all components
            assertEquals(true, run("""
                import numerics.trot3
                let r = TRot3(Rational(1,2), Rational(1,3), Rational(1,4))
                let inv = r.inv()
                inv.xy == Rational(-1,2) && inv.yz == Rational(-1,3) && inv.zx == Rational(-1,4)
                """));
        }
    }
}
