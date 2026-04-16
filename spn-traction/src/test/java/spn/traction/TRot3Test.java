package spn.traction;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import spn.lang.ClasspathModuleLoader;
import spn.lang.FilesystemModuleLoader;
import spn.lang.SpnParser;
import spn.language.SpnModuleRegistry;
import spn.node.SpnRootNode;
import spn.type.SpnSymbolTable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TRot3Test {

    private SpnSymbolTable symbolTable;
    private SpnModuleRegistry registry;

    @BeforeEach
    void setUp() {
        Engine engine = Engine.newBuilder().build();
        Context context = Context.newBuilder().engine(engine).build();
        symbolTable = new SpnSymbolTable();
        registry = new SpnModuleRegistry();
        spn.stdlib.gen.StdlibModuleLoader.registerAll(registry);
        registry.addLoader(new ClasspathModuleLoader(null, symbolTable));
        Path root = Path.of(System.getProperty("user.dir"));
        if (!Files.isRegularFile(root.resolve("module.spn")))
            root = root.resolve("spn-traction");
        registry.addLoader(new FilesystemModuleLoader(
                root, "sibarum.spn.traction", null, symbolTable));
    }

    private Object run(String source) {
        SpnParser parser = new SpnParser(source, null, null, symbolTable, registry);
        SpnRootNode root = parser.parse();
        return root.getCallTarget().call();
    }

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
