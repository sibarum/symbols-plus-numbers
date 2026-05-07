package spn.stdlib;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import spn.lang.ClasspathModuleLoader;
import spn.lang.SpnParser;
import spn.language.SpnModuleRegistry;
import spn.node.SpnRootNode;
import spn.type.SpnSymbolTable;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test for the IO.println builtin — verifies it dispatches, writes
 * to stdout, and returns 0L.
 */
class PrintlnBuiltinTest {

    private SpnSymbolTable symbolTable;
    private SpnModuleRegistry registry;
    private PrintStream originalOut;
    private ByteArrayOutputStream captured;

    @BeforeEach
    void setUp() {
        symbolTable = new SpnSymbolTable();
        registry = new SpnModuleRegistry();
        spn.stdlib.gen.StdlibModuleLoader.registerAll(registry);
        registry.addLoader(new ClasspathModuleLoader(null, symbolTable));
        originalOut = System.out;
        captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    private Object run(String source) {
        SpnParser parser = new SpnParser(source, null, null, symbolTable, registry);
        SpnRootNode root = parser.parse();
        return root.getCallTarget().call();
    }

    @Test
    void printsStringAndReturnsZero() {
        Object result = run("""
                import IO
                println("hello, spn")
                """);
        assertEquals(0L, result);
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("hello, spn"));
    }

    @Test
    void printsLong() {
        Object result = run("""
                import IO
                println(42)
                """);
        assertEquals(0L, result);
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("42"));
    }

    @Test
    void printsDouble() {
        Object result = run("""
                import IO
                println(3.14)
                """);
        assertEquals(0L, result);
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("3.14"));
    }

    @Test
    void customSinkOverridesStdout() {
        // Host installs a sink (e.g. GUI logBuffer) — println should go there,
        // not stdout.
        java.util.List<String> sink = new java.util.ArrayList<>();
        spn.stdlib.io.IoState ioState = new spn.stdlib.io.IoState();
        ioState.setPrintlnSink(sink::add);
        spn.stdlib.io.IoState.set(ioState);
        try {
            Object result = run("""
                    import IO
                    println("routed")
                    """);
            assertEquals(0L, result);
            assertEquals(1, sink.size());
            assertEquals("routed", sink.getFirst());
            assertTrue(captured.toString(StandardCharsets.UTF_8).isEmpty(),
                    "stdout should be empty when sink is installed");
        } finally {
            spn.stdlib.io.IoState.clear();
        }
    }
}
