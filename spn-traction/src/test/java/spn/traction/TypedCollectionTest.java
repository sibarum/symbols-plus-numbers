package spn.traction;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TypedCollectionTest extends TractionTestBase {

    @Nested
    class TypedArray {
        @Test void createAndPush() {
            assertEquals(true, run("""
                import Collections
                import numerics.rational

                type RationalArray = Array<Rational>

                let arr = RationalArray()
                let arr2 = arr.push(Rational(3, 4))
                arr2.length() == 1
                """));
        }

        @Test void getReturnsCorrectType() {
            assertEquals(true, run("""
                import Collections
                import numerics.rational

                type RationalArray = Array<Rational>

                let arr = RationalArray().push(Rational(1, 2)).push(Rational(3, 4))
                arr[0] == Rational(1, 2) && arr[1] == Rational(3, 4)
                """));
        }

        @Test void promotionOnPush() {
            // Pushing an int should auto-promote to Rational via promote int -> Rational
            assertEquals(true, run("""
                import Collections
                import numerics.rational

                type RationalArray = Array<Rational>

                let arr = RationalArray().push(5)
                arr[0] == Rational(5, 1)
                """));
        }

        @Test void multipleTypedArraysDontConflict() {
            // Two different typed arrays from the same macro
            assertEquals(true, run("""
                import Collections

                type Box(int)
                type Pair(int, int)

                type BoxArray = Array<Box>
                type PairArray = Array<Pair>

                let ba = BoxArray().push(Box(1)).push(Box(2))
                let pa = PairArray().push(Pair(3, 4))
                ba.length() == 2 && pa.length() == 1
                """));
        }
    }

    @Nested
    class TypedSet {
        @Test void createAndAdd() {
            assertEquals(2L, run("""
                import Collections
                import numerics.rational

                type RationalSet = Set<Rational>

                let s = RationalSet()
                let s2 = s.add(Rational(1, 2)).add(Rational(3, 4))
                s2.size()
                """));
        }

        @Test void addIsIdempotent() {
            // Adding the same element twice should yield a set of size 1
            assertEquals(1L, run("""
                import Collections
                import numerics.rational

                type RationalSet = Set<Rational>

                let s = RationalSet().add(Rational(1, 2)).add(Rational(1, 2))
                s.size()
                """));
        }

        @Test void removeElement() {
            assertEquals(1L, run("""
                import Collections
                import numerics.rational

                type RationalSet = Set<Rational>

                let s = RationalSet().add(Rational(1, 2)).add(Rational(3, 4))
                let s2 = s.remove(Rational(1, 2))
                s2.size()
                """));
        }

        @Test void promotionOnAdd() {
            // Pushing an int should auto-promote to Rational via promote int -> Rational
            assertEquals(true, run("""
                import Collections
                import numerics.rational

                type RationalSet = Set<Rational>

                let s = RationalSet().add(5)
                s.size() == 1
                """));
        }

        @Test void multipleTypedSetsDontConflict() {
            assertEquals(true, run("""
                import Collections

                type Box(int)
                type Pair(int, int)

                type BoxSet = Set<Box>
                type PairSet = Set<Pair>

                let bs = BoxSet().add(Box(1)).add(Box(2))
                let ps = PairSet().add(Pair(3, 4))
                bs.size() == 2 && ps.size() == 1
                """));
        }
    }

    @Nested
    class TypedDict {
        @Test void putAndGet() {
            assertEquals(true, run("""
                import Collections
                import numerics.rational

                type ColorMap = Dict<Symbol, Rational>

                let m = ColorMap().put(:red, Rational(1, 2))
                m[:red] == Rational(1, 2)
                """));
        }

        @Test void sizeAndHas() {
            assertEquals(true, run("""
                import Collections
                import numerics.rational

                type ColorMap = Dict<Symbol, Rational>

                let m = ColorMap().put(:red, Rational(1, 2)).put(:blue, Rational(3, 4))
                m.size() == 2 && m.has(:red) && m.has(:green) == false
                """));
        }

        @Test void putOverwrites() {
            assertEquals(true, run("""
                import Collections
                import numerics.rational

                type ColorMap = Dict<Symbol, Rational>

                let m = ColorMap().put(:red, Rational(1, 2)).put(:red, Rational(9, 1))
                m.size() == 1 && m[:red] == Rational(9, 1)
                """));
        }

        @Test void removeKey() {
            assertEquals(true, run("""
                import Collections
                import numerics.rational

                type ColorMap = Dict<Symbol, Rational>

                let m = ColorMap().put(:red, Rational(1, 2)).put(:blue, Rational(3, 4))
                let m2 = m.remove(:red)
                m2.size() == 1 && m2.has(:red) == false && m2.has(:blue)
                """));
        }

        @Test void promotionOnPut() {
            // The value is auto-promoted to V via the promote rule
            assertEquals(true, run("""
                import Collections
                import numerics.rational

                type ColorMap = Dict<Symbol, Rational>

                let m = ColorMap().put(:red, 5)
                m[:red] == Rational(5, 1)
                """));
        }

        @Test void multipleTypedDictsDontConflict() {
            assertEquals(true, run("""
                import Collections

                type Score(int)

                type Scorecard = Dict<Symbol, Score>
                type Roster = Dict<Symbol, Score>

                let sc = Scorecard().put(:red, Score(5))
                let r = Roster().put(:alice, Score(10)).put(:bob, Score(20))
                sc.size() == 1 && r.size() == 2
                """));
        }
    }
}
