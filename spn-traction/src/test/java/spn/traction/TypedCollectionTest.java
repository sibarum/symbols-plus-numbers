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

        @Test void constructFromArrayLiteral() {
            // MyArray([1, 2, 3]) — the underlying TypedArray ctor takes an
            // UntypedArray, so an array literal should flow straight in.
            assertEquals(true, run("""
                import Collections

                type IntArray = Array<int>

                let a = IntArray([1, 2, 3])
                a.length() == 3 && a[0] == 1 && a[2] == 3
                """));
        }

        @Test void constructFromVariadicArgs() {
            // IntArray(1, 2, 3) — variadic ctor checks each element is int.
            assertEquals(true, run("""
                import Collections

                type IntArray = Array<int>

                let a = IntArray(1, 2, 3)
                a.length() == 3 && a[0] == 1 && a[2] == 3
                """));
        }

        @Test void variadicConstructRejectsWrongElementType() {
            // IntArray("a", "b") — strings don't promote to int, variadic
            // dispatch must fail at parse.
            assertThrows(spn.lang.SpnParseException.class, () -> run("""
                import Collections

                type IntArray = Array<int>

                let a = IntArray("a", "b")
                a.length()
                """));
        }

        @Test void variadicConstructThenMethodCall() {
            // Reproduces factor demo error: IntVec(2,3,5) then .length() on
            // the result. Demo was failing with "expects TypedArray$1, got
            // SpnStructValue" — runtime type stamp missing on the variadic
            // ctor's return value.
            assertEquals(3L, run("""
                import Collections

                type IntVec = Array<int>

                pure primes() -> IntVec = () { IntVec(2, 3, 5) }

                let ps = primes()
                ps.length()
                """));
        }

        @Test void variadicConstructCrossModule() {
            // factor.network declares `type IntVec = Array<int>`. Pull it
            // from there, build via variadic ctor, call .length(). This is
            // the exact shape the demo uses.
            assertEquals(3L, run("""
                import factor.network

                pure primes() -> IntVec = () { IntVec(2, 3, 5) }

                let ps = primes()
                ps.length()
                """));
        }

        @Test void variadicConstructInsideAction() {
            // factor/demo.spn invokes this pattern from `action drawFrame`.
            assertEquals(3L, run("""
                import factor.network

                pure primes() -> IntVec = () { IntVec(2, 3, 5) }

                action frame() -> int = () {
                    let ps = primes()
                    ps.length()
                }
                frame()
                """));
        }

        @Test void variadicConstructWithFullDemoImports() {
            // Mirror demo imports (minus Canvas which traction tests can't
            // load). If the module load order affects the macro expansion
            // counter or memoization, this is where it would show.
            assertEquals(3L, run("""
                import Math (cos, sin, toFloat, round)
                import Array (append, concat)
                import String (formatNum)
                import factor.network

                pure primes() -> IntVec = () { IntVec(2, 3, 5) }

                let ps = primes()
                ps.length()
                """));
        }

        @Test void crossFileMacroExpansionsHaveUniqueInternalNames() {
            // Regression test for the factor demo bug: two files each
            // expanding a macro used to restart `$N` at 1, producing
            // colliding internal names `TypedArray$1` with distinct
            // descriptors. Nominal dispatch then failed on values crossing
            // file boundaries. Since the counter moved onto
            // SpnModuleRegistry, every expansion across the build has a
            // globally-unique suffix.
            //
            // The test exercises both patterns: aliasing an imported type
            // AND re-expanding the same macro locally. Dispatch on each
            // receiver must reach the correct method based on the value's
            // actual descriptor.
            Object result = run("""
                import factor.network

                type LocalCV = Array<TComplex>

                let a = ComplexVec()
                let b = LocalCV()
                a.length() + b.length()
                """);
            assertEquals(0L, result);
        }

        @Test void variadicFactoryInMultiImportContext() {
            // Demo has many typed-collection aliases from factor.network:
            // IntVec, ComplexVec, ComplexMat, PLayerArray. Each is a
            // separate Array<T> macro expansion. Try to tease out whether
            // dispatch of IntVec(...).length() gets confused by the
            // sibling expansions.
            assertEquals(3L, run("""
                import factor.network
                import numerics.tcomplex
                import numerics.rational

                pure primes() -> IntVec = () { IntVec(2, 3, 5) }

                -- Exercise several sibling typed-array aliases to see if
                -- any collision in macro memoization or method dispatch
                -- trips up the variadic path.
                let ps = primes()
                let cv = ComplexVec()
                let cm = ComplexMat()
                ps.length()
                """));
        }

        @Test void variadicConstructPassedToMethodTakingTypedArg() {
            // network.spn has `pure encodeInput(int, IntVec) -> ComplexVec`.
            // Demo passes `primes()` result into `encodeInput(n, ps)`. This
            // exercises the full path: variadic ctor → typed function param.
            assertEquals(3L, run("""
                import factor.network

                pure primes() -> IntVec = () { IntVec(2, 3, 5) }

                let ps = primes()
                let features = encodeInput(12, ps)
                features.length()
                """));
        }

        @Test void variadicConstructPromotesCompatibleElements() {
            // RationalArray(Rational(1,2), 3) — 3 promotes to Rational
            // element-by-element, just like regular arg promotion.
            assertEquals(true, run("""
                import Collections
                import numerics.rational

                type RationalArray = Array<Rational>

                let a = RationalArray(Rational(1, 2), 3)
                a.length() == 2 && a[1] == Rational(3, 1)
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
