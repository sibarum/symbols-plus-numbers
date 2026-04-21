package spn.stdlib;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import spn.lang.ClasspathModuleLoader;
import spn.lang.SpnParser;
import spn.language.SpnModuleRegistry;
import spn.node.SpnRootNode;
import spn.type.SpnSymbolTable;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests that stdlib collection builtins are callable as instance methods on
 * their receiver type (Array / Dict / Set / Option). The flat-function forms
 * keep working alongside the method forms.
 */
class CollectionMethodsTest {

    private SpnSymbolTable symbolTable;
    private SpnModuleRegistry registry;

    @BeforeEach
    void setUp() {
        symbolTable = new SpnSymbolTable();
        registry = new SpnModuleRegistry();
        spn.stdlib.gen.StdlibModuleLoader.registerAll(registry);
        registry.addLoader(new ClasspathModuleLoader(null, symbolTable));
    }

    private Object run(String source) {
        SpnParser parser = new SpnParser(source, null, null, symbolTable, registry);
        SpnRootNode root = parser.parse();
        return root.getCallTarget().call();
    }

    // ── Array ──────────────────────────────────────────────────────────────

    @Test void arrayLengthAsMethod() {
        assertEquals(3L, run("""
            import Array
            let arr = append(append(append([], 1), 2), 3)
            arr.length()
            """));
    }

    @Test void arrayAppendAsMethod() {
        assertEquals(3L, run("""
            import Array
            let arr = [].append(1).append(2).append(3)
            arr.length()
            """));
    }

    @Test void arrayMapAsMethod() {
        assertEquals(6L, run("""
            import Array
            pure double(int) -> int = (x) { x * 2 }
            let arr = [].append(1).append(2).append(3)
            let doubled = arr.map(double)
            doubled[2]
            """));
    }

    @Test void arrayFilterAsMethod() {
        assertEquals(2L, run("""
            import Array
            pure isEven(int) -> Boolean = (x) { x % 2 == 0 }
            let arr = [].append(1).append(2).append(3).append(4)
            arr.filter(isEven).length()
            """));
    }

    @Test void arrayMethodChaining() {
        // filter([1..4], isEven) = [2,4]; map(_, double) = [4,8]; sum = 12.
        // Result type is Double because sum returns Double in the stdlib.
        assertEquals(12.0, run("""
            import Array
            pure double(int) -> int = (x) { x * 2 }
            pure isEven(int) -> Boolean = (x) { x % 2 == 0 }
            let arr = [].append(1).append(2).append(3).append(4)
            arr.filter(isEven).map(double).sum()
            """));
    }

    @Test void arrayFlatFormStillWorks() {
        // After the method refactor the flat form must keep working —
        // nothing in the stdlib or downstream .spn files should break.
        assertEquals(3L, run("""
            import Array (length, append)
            let arr = append(append(append([], 1), 2), 3)
            length(arr)
            """));
    }

    // ── Dict ───────────────────────────────────────────────────────────────

    @Test void dictPutAndGetAsMethod() {
        assertEquals(42L, run("""
            import Dict
            let d = emptyDict().put(:answer, 42)
            d.get(:answer)
            """));
    }

    @Test void dictSizeAsMethod() {
        assertEquals(2L, run("""
            import Dict
            let d = emptyDict().put(:a, 1).put(:b, 2)
            d.size()
            """));
    }

    @Test void dictHasKeyAsMethod() {
        assertEquals(true, run("""
            import Dict
            let d = emptyDict().put(:a, 1)
            d.hasKey(:a)
            """));
    }

    // ── Set ────────────────────────────────────────────────────────────────

    @Test void setAddAsMethod() {
        // Set dedups: adding 3 twice stays at size 3 (after 1, 2, 3).
        assertEquals(3L, run("""
            import Set
            import Array
            let arr = [].append(1).append(2)
            let s = fromArray(arr).add(3).add(3)
            s.size()
            """));
    }

    // ── Option ─────────────────────────────────────────────────────────────

    // Option methods (unwrap / unwrapOr / isSome / isNone / map / filter /
    // flatMap) are registered as stdlib methods, but method dispatch on them
    // currently fails because Option's return type isn't reflected as a
    // nameable type in the TypeResolver (stdlib returns it as UNTYPED). This
    // is a known limitation — a follow-up pass should track Option return
    // types properly. Until then Option calls must use flat function form.
}
