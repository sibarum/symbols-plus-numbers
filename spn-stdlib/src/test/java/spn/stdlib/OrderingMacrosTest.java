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
 * Tests for the stdlib Ordering.spn macros (deriveOrderingFromInt,
 * deriveOrderingFromOrdering). Confirms macros load via classpath module
 * resolution and emit working comparison operators.
 */

class OrderingMacrosTest {

    private SpnSymbolTable symbolTable;
    private SpnModuleRegistry registry;

    @BeforeEach
    void setUp() {
        symbolTable = new SpnSymbolTable();
        registry = new SpnModuleRegistry();
        registry.addLoader(new ClasspathModuleLoader(null, symbolTable));
    }

    private Object run(String source) {
        SpnParser parser = new SpnParser(source, null, null, symbolTable, registry);
        SpnRootNode root = parser.parse();
        return root.getCallTarget().call();
    }

    @Test
    void deriveOrderingFromInt_emitsWorkingComparisons() {
        // Use a simple int-based comparator on a wrapper struct
        Object lt = run("""
            import Ordering
            type Box(int)
            pure cmpBox(Box, Box) -> int = (a, b) { a.0 - b.0 }
            deriveOrderingFromInt<Box, cmpBox>
            Box(3) < Box(5)
            """);
        assertEquals(true, lt);
    }

    @Test
    void deriveOrderingFromInt_greaterThan() {
        Object gt = run("""
            import Ordering
            type Box(int)
            pure cmpBox(Box, Box) -> int = (a, b) { a.0 - b.0 }
            deriveOrderingFromInt<Box, cmpBox>
            Box(7) > Box(2)
            """);
        assertEquals(true, gt);
    }

    @Test
    void deriveOrderingFromInt_lessOrEqual() {
        Object eq = run("""
            import Ordering
            type Box(int)
            pure cmpBox(Box, Box) -> int = (a, b) { a.0 - b.0 }
            deriveOrderingFromInt<Box, cmpBox>
            Box(4) <= Box(4)
            """);
        assertEquals(true, eq);
    }

    @Test
    void diagnostic_cmpBoxAndSymbolEquality() {
        // Sanity: cmpBox should return :lt for Box(1), Box(9)
        Object cmp = run("""
            type Box(int)
            pure cmpBox(Box, Box) -> Symbol = (a, b) {
              match (a.0 == b.0, a.0 < b.0)
              | (true, _)  -> :eq
              | (_, true)  -> :lt
              | _          -> :gt
            }
            cmpBox(Box(1), Box(9))
            """);
        assertEquals(":lt", cmp.toString());

        // Sanity: :lt == :lt should be true
        Object eq = run("""
            :lt == :lt
            """);
        assertEquals(true, eq);
    }

    @Test
    void deriveOrderingFromOrdering_emitsWorkingComparisons() {
        // Use a symbolic comparator
        Object lt = run("""
            import Ordering
            type Box(int)
            pure cmpBox(Box, Box) -> Symbol = (a, b) {
              match (a.0 == b.0, a.0 < b.0)
              | (true, _)  -> :eq
              | (_, true)  -> :lt
              | _          -> :gt
            }
            deriveOrderingFromOrdering<Box, cmpBox>
            Box(1) < Box(9)
            """);
        assertEquals(true, lt);
    }

    @Test
    void deriveOrderingFromOrdering_greaterOrEqual() {
        Object ge = run("""
            import Ordering
            type Box(int)
            pure cmpBox(Box, Box) -> Symbol = (a, b) {
              match (a.0 == b.0, a.0 < b.0)
              | (true, _)  -> :eq
              | (_, true)  -> :lt
              | _          -> :gt
            }
            deriveOrderingFromOrdering<Box, cmpBox>
            Box(8) >= Box(8)
            """);
        assertEquals(true, ge);
    }
}
