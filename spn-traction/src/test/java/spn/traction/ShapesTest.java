package spn.traction;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShapesTest extends TractionTestBase {

    @Test
    void matchOnBoolWorks() {
        // Sanity check: does match on a boolean work AT ALL?
        assertEquals("no", run("""
            import geometry.shapes
            match false | true -> "yes" | _ -> "no"
            """));
    }

    @Test
    void matchOnComparisonResult() {
        // Does match on a comparison result work?
        assertEquals("no", run("""
            import geometry.shapes
            let x = Rational(12, 1)
            let cmp = x < Rational.zero
            match cmp | true -> "yes" | _ -> "no"
            """));
    }

    @Test
    void compareAndLessThanThroughModule() {
        // Verify both compare and < work through the module
        Object cmp = run("""
            import geometry.shapes
            compare(Rational(12, 1), Rational(0, 1))
            """);
        assertTrue((long) cmp > 0, "compare(12, 0) should be positive, got: " + cmp);

        Object lt = run("""
            import geometry.shapes
            Rational(12, 1) < Rational.zero
            """);
        assertEquals(false, lt, "12 < 0 should be false");

        // Now test the EXACT area pattern: match ... | true -> ... | _ -> ...
        Object area = run("""
            import geometry.shapes
            let det = Rational(12, 1)
            match det < Rational.zero
              | true -> det.neg()
              | _ -> det
            """);
        assertEquals("Rational(12, 1)", area.toString(), "absDet should be 12, got: " + area);
    }

    @Test
    void detIsPositiveForCCW() {
        // Direct check: the determinant for our test triangle should be positive
        Object result = run("""
            import geometry.shapes
            let a = Point(Rational(0,1), Rational(0,1))
            let b = Point(Rational(4,1), Rational(0,1))
            let c = Point(Rational(0,1), Rational(3,1))
            let (ax, ay) = a
            let (bx, by) = b
            let (cx, cy) = c
            let det = (bx - ax) * (cy - ay) - (cx - ax) * (by - ay)
            det > Rational.zero
            """);
        assertEquals(true, result, "det should be positive (12 > 0)");
    }

    @Test
    void lessThanOnRational() {
        // Specifically test < on Rational (not >, which rationalOrderingWorks uses)
        assertEquals(false, run("""
            import geometry.shapes
            Rational(12, 1) < Rational.zero
            """), "12 < 0 should be false");
    }

    @Test
    void areaReturnsRawValue() {
        // Get the raw area value to see what we're dealing with
        Object result = run("""
            import geometry.shapes
            let t = Triangle(
                Point(Rational(0,1), Rational(0,1)),
                Point(Rational(4,1), Rational(0,1)),
                Point(Rational(0,1), Rational(3,1)))
            area(t)
            """);
        // If -6: compare is inverted in module. If 6: fixed.
        assertEquals("Rational(6, 1)", result.toString(), "area returned: " + result);
    }

    @Test void absDetWorks() {
        // Check the absolute value logic matches
        Object result = run("""
            import geometry.shapes
            let det = Rational(12, 1)
            let absDet = match det < Rational.zero | true -> det.neg() | _ -> det
            absDet == Rational(12, 1)
            """);
        assertEquals(true, result, "absDet of 12 should be 12");
    }

    @Test
    void rationalOrderingWorks() {
        // Smoke test: verify < on Rational works correctly within the shapes module context
        assertEquals(true, run("""
            import geometry.shapes
            Rational(12, 1) > Rational.zero
            """));
    }

    @Nested
    class Area {
        @Test
        void rectangleArea() {
            // Rect at origin, 3x4 → area 12
            assertEquals(true, run("""
                import geometry.shapes
                let r = Rect(Point(Rational(0,1), Rational(0,1)), Rational(3,1), Rational(4,1))
                area(r) == Rational(12, 1)
                """));
        }

        @Test
        void triangleArea() {
            // Triangle (0,0), (4,0), (0,3) → area = |4*3|/2 = 6
            assertEquals(true, run("""
                import geometry.shapes
                let t = Triangle(
                    Point(Rational(0,1), Rational(0,1)),
                    Point(Rational(4,1), Rational(0,1)),
                    Point(Rational(0,1), Rational(3,1)))
                area(t) == Rational(6, 1)
                """));
        }
    }

    @Nested
    class Containment {
        @Test
        void rectContainsInteriorPoint() {
            assertEquals(true, run("""
                import geometry.shapes
                let r = Rect(Point(Rational(0,1), Rational(0,1)), Rational(10,1), Rational(10,1))
                contains(r, Point(Rational(5,1), Rational(5,1)))
                """));
        }

        @Test
        void rectDoesNotContainExteriorPoint() {
            assertEquals(false, run("""
                import geometry.shapes
                let r = Rect(Point(Rational(0,1), Rational(0,1)), Rational(10,1), Rational(10,1))
                contains(r, Point(Rational(15,1), Rational(5,1)))
                """));
        }
    }

    @Nested
    class Transform {
        @Test
        void translateRect() {
            assertEquals(true, run("""
                import geometry.shapes
                let r = Rect(Point(Rational(1,1), Rational(2,1)), Rational(3,1), Rational(4,1))
                let offset = Point(Rational(10,1), Rational(20,1))
                let moved = translate(r, offset)
                match moved
                  | Rect(o, w, h) -> o == Point(Rational(11,1), Rational(22,1)) && w == Rational(3,1)
                  | _ -> false
                """));
        }
    }

    @Nested
    class Bounds {
        @Test
        void triangleBounds() {
            assertEquals(true, run("""
                import geometry.shapes
                let t = Triangle(
                    Point(Rational(1,1), Rational(2,1)),
                    Point(Rational(5,1), Rational(1,1)),
                    Point(Rational(3,1), Rational(7,1)))
                let b = bounds(t)
                match b
                  | Rect(o, w, h) -> o == Point(Rational(1,1), Rational(1,1))
                  | _ -> false
                """));
        }
    }
}
