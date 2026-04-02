package spn.type;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConstraintTest {

    @Nested
    class GreaterThanOrEqualTest {
        private final Constraint c = new Constraint.GreaterThanOrEqual(0);

        @Test void acceptsEqualValue()    { assertTrue(c.check(0L)); }
        @Test void acceptsGreaterLong()   { assertTrue(c.check(42L)); }
        @Test void acceptsGreaterDouble() { assertTrue(c.check(3.14)); }
        @Test void rejectsLesserLong()    { assertFalse(c.check(-1L)); }
        @Test void rejectsLesserDouble()  { assertFalse(c.check(-0.001)); }
        @Test void rejectsNonNumeric()    { assertFalse(c.check("hello")); }
        @Test void describe()             { assertEquals("n >= 0", c.describe()); }
    }

    @Nested
    class GreaterThanTest {
        private final Constraint c = new Constraint.GreaterThan(0);

        @Test void rejectsEqualValue()  { assertFalse(c.check(0L)); }
        @Test void acceptsGreater()     { assertTrue(c.check(1L)); }
        @Test void rejectsLesser()      { assertFalse(c.check(-1L)); }
        @Test void acceptsGreaterDouble() { assertTrue(c.check(0.001)); }
    }

    @Nested
    class LessThanOrEqualTest {
        private final Constraint c = new Constraint.LessThanOrEqual(100);

        @Test void acceptsEqualValue()  { assertTrue(c.check(100L)); }
        @Test void acceptsLesser()      { assertTrue(c.check(50L)); }
        @Test void rejectsGreater()     { assertFalse(c.check(101L)); }
        @Test void acceptsLesserDouble() { assertTrue(c.check(99.9)); }
    }

    @Nested
    class LessThanTest {
        private final Constraint c = new Constraint.LessThan(100);

        @Test void rejectsEqualValue()  { assertFalse(c.check(100L)); }
        @Test void acceptsLesser()      { assertTrue(c.check(99L)); }
        @Test void rejectsGreater()     { assertFalse(c.check(100L)); }
    }

    @Nested
    class ModuloEqualsTest {
        @Test void integerConstraint() {
            var c = new Constraint.ModuloEquals(1, 0);
            assertTrue(c.check(42L));
            assertTrue(c.check(0L));
            assertTrue(c.check(-7L));
            assertTrue(c.check(5.0));  // 5.0 % 1 == 0
            assertFalse(c.check(3.5)); // 3.5 % 1 == 0.5
        }

        @Test void evenConstraint() {
            var c = new Constraint.ModuloEquals(2, 0);
            assertTrue(c.check(0L));
            assertTrue(c.check(4L));
            assertTrue(c.check(-2L));
            assertFalse(c.check(3L));
            assertFalse(c.check(7L));
        }

        @Test void oddConstraint() {
            var c = new Constraint.ModuloEquals(2, 1);
            assertTrue(c.check(1L));
            assertTrue(c.check(3L));
            assertFalse(c.check(0L));
            assertFalse(c.check(4L));
        }

        @Test void rejectsNonNumeric() {
            var c = new Constraint.ModuloEquals(2, 0);
            assertFalse(c.check("hello"));
            assertFalse(c.check(true));
        }

        @Test void describeWithZeroRemainder() {
            assertEquals("n % 2 == 0", new Constraint.ModuloEquals(2, 0).describe());
        }

        @Test void describeWithNonZeroRemainder() {
            assertEquals("n % 3 == 1", new Constraint.ModuloEquals(3, 1).describe());
        }
    }
}
