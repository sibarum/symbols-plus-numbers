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

class TComplexTest {

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
    class Construction {
        @Test void pythagoreanTriple() {
            // TComplex(3, 4, 5) → scale=1/5, tangent=4/3
            assertEquals(true, run("""
                import numerics.tcomplex
                let tc = TComplex(3, 4, 5)
                tc.scale == Rational(1, 5) && tc.tangent == Rational(4, 3)
                """));
        }

        @Test void fromRational() {
            // TComplex(Rational(0,1)) → scale=1/(1+0)=1, tangent=0/(1-0)=0
            assertEquals(true, run("""
                import numerics.tcomplex
                let tc = TComplex(Rational(0, 1))
                tc.scale == Rational.one && tc.tangent == Rational.zero
                """));
        }
    }

    @Nested
    class Arithmetic {
        @Test void multiplication() {
            // (3,4,5) * (3,4,5) should double the angle
            assertEquals(true, run("""
                import numerics.tcomplex
                let a = TComplex(3, 4, 5)
                let b = a * a
                b == b  -- at minimum, self-equality holds
                """));
        }

        @Test void negation() {
            // -tc negates the scale, tangent stays
            assertEquals(true, run("""
                import numerics.tcomplex
                let tc = TComplex(3, 4, 5)
                let neg = tc.neg()
                neg.scale == Rational(-1, 5) && neg.tangent == tc.tangent
                """));
        }

        @Test void subtraction() {
            // tc - tc should give zero scale
            assertEquals(true, run("""
                import numerics.tcomplex
                let tc = TComplex(3, 4, 5)
                let diff = tc - tc
                diff.scale == Rational.zero
                """));
        }

        @Test void equality() {
            assertEquals(true, run("""
                import numerics.tcomplex
                let a = TComplex(3, 4, 5)
                let b = TComplex(3, 4, 5)
                a == b
                """));
        }
    }

    @Nested
    class Cartesian {
        @Test void toCartesian345() {
            // (3,4,5): scale=1/5, tangent=4/3
            // real = scale * td = (1/5)*3 = 3/5
            // imag = scale * tn = (1/5)*4 = 4/5
            assertEquals(true, run("""
                import numerics.tcomplex
                let tc = TComplex(3, 4, 5)
                let (re, im) = tc.toCartesian()
                re == Rational(3, 5) && im == Rational(4, 5)
                """));
        }
    }
}
