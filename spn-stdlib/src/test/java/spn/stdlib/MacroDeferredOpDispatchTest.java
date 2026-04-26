package spn.stdlib;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import spn.lang.ClasspathModuleLoader;
import spn.lang.SpnParseException;
import spn.lang.SpnParser;
import spn.language.SpnModuleRegistry;
import spn.node.SpnRootNode;
import spn.type.SpnSymbolTable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for macro-body operator dispatch deferred until end-of-parse.
 * Verifies that an operator used on a macro-parameter type inside the
 * macro body resolves correctly regardless of whether the user-defined
 * overload is registered before or after the macro is expanded.
 */
class MacroDeferredOpDispatchTest {

    private SpnSymbolTable symbolTable;
    private SpnModuleRegistry registry;

    @BeforeEach void setUp() {
        symbolTable = new SpnSymbolTable();
        registry = new SpnModuleRegistry();
        registry.addLoader(new ClasspathModuleLoader(null, symbolTable));
    }

    private Object run(String src) {
        SpnParser parser = new SpnParser(src, null, null, symbolTable, registry);
        SpnRootNode root = parser.parse();
        return root.getCallTarget().call();
    }

    @Test void operatorOverloadRegisteredBeforeMacroExpansion() {
        // Baseline: deriveOrdering runs first, then Compare<Box> expands.
        // This already worked before the fix; included to guard against regressions.
        Object res = run("""
            import Ordering
            type Box(v: int)
            pure cmpBox(Box, Box) -> int = (a, b) { a.v - b.v }
            deriveOrderingFromInt<Box, cmpBox>

            macro Compare<T> = {
              pure lt(T, T) -> bool = (a, b) { a < b }
              emit lt
            }
            type X = Compare<Box>
            lt(Box(3), Box(5))
            """);
        assertEquals(true, res);
    }

    @Test void operatorOverloadRegisteredAfterMacroExpansion() {
        // The actual fix: Compare<Box> expands FIRST, then deriveOrderingFromInt
        // registers <(Box, Box). The deferred op resolves at end-of-parse.
        Object res = run("""
            import Ordering
            type Box(v: int)
            pure cmpBox(Box, Box) -> int = (a, b) { a.v - b.v }

            macro Compare<T> = {
              pure lt(T, T) -> bool = (a, b) { a < b }
              emit lt
            }
            type X = Compare<Box>
            deriveOrderingFromInt<Box, cmpBox>
            lt(Box(3), Box(5))
            """);
        assertEquals(true, res);
    }

    @Test void operatorRegisteredManuallyAfterMacroExpansion() {
        // Same as above but the overload is declared via plain `pure <(...)`
        // rather than via a macro. Confirms the fixup works regardless of
        // how the overload arrives.
        Object res = run("""
            type Box(v: int)
            macro Compare<T> = {
              pure lt(T, T) -> bool = (a, b) { a < b }
              emit lt
            }
            type X = Compare<Box>
            pure <(Box, Box) -> bool = (a, b) { a.v < b.v }
            lt(Box(2), Box(7))
            """);
        assertEquals(true, res);
    }

    @Test void operatorNeverRegisteredErrorsWithMacroFrame() {
        // No overload anywhere in the source — the deferred op should fail
        // at end-of-parse with the macro invocation in the trace.
        SpnParseException ex = assertThrows(SpnParseException.class, () -> run("""
            type Box(v: int)
            macro Compare<T> = {
              pure lt(T, T) -> bool = (a, b) { a < b }
              emit lt
            }
            type X = Compare<Box>
            42
            """));
        String formatted = ex.formatMessage();
        assertTrue(formatted.contains("No overload of '<' for Box"),
                "should mention missing overload: " + formatted);
        assertTrue(formatted.contains("in macro Compare"),
                "should attribute the failure to the macro frame: " + formatted);
    }

    @Test void arithmeticOpAlsoDeferred() {
        // ARITHMETIC fallback (+ - * / %) shares the same defer path.
        Object res = run("""
            type Box(v: int)
            macro Sum<T> = {
              pure adder(T, T) -> T = (a, b) { a + b }
              emit adder
            }
            type X = Sum<Box>
            pure +(Box, Box) -> Box = (a, b) { Box(a.v + b.v) }
            adder(Box(2), Box(3)).v
            """);
        assertEquals(5L, res);
    }

    @Test void mixedDeferredAndImmediateInSameMacro() {
        // One op defers (overload comes later), one resolves immediately
        // (overload already present). Both must end up correct.
        Object res = run("""
            type Box(v: int)
            pure +(Box, Box) -> Box = (a, b) { Box(a.v + b.v) }

            macro Both<T> = {
              pure go(T, T) -> bool = (a, b) {
                let s = a + b
                s < b
              }
              emit go
            }
            type X = Both<Box>
            pure <(Box, Box) -> bool = (a, b) { a.v < b.v }
            go(Box(1), Box(10))
            """);
        // Box(1) + Box(10) = Box(11); 11 < 10 → false
        assertEquals(false, res);
    }

    @Test void primitiveOpsInsideMacroStillFailWhenOperandsNonPrimitive() {
        // A macro that applies < to TYPED primitives shouldn't defer; behavior
        // unchanged. Sanity test that the defer path doesn't over-apply.
        Object res = run("""
            macro IntCmp<T> = {
              pure ltInt(int, int) -> bool = (a, b) { a < b }
              emit ltInt
            }
            type X = IntCmp<int>
            ltInt(3, 5)
            """);
        assertEquals(true, res);
    }
}
