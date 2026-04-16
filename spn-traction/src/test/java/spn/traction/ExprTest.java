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

class ExprTest {

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
    class Evaluation {
        @Test void litEval() {
            assertEquals(true, run("""
                import algebra.expr
                let e = lit(Rational(3, 4))
                eval(e, [:_x 0]) == Rational(3, 4)
                """));
        }

        @Test void addEval() {
            // lit(1/2) + lit(1/3) = 5/6
            assertEquals(true, run("""
                import algebra.expr
                let e = add(lit(Rational(1, 2)), lit(Rational(1, 3)))
                eval(e, [:_x 0]) == Rational(5, 6)
                """));
        }

        @Test
        @org.junit.jupiter.api.Disabled("env[name] uses array-access node on Dict — needs dict-indexing support")
        void varEval() {
            assertEquals(true, run("""
                import algebra.expr
                let e = var(:x)
                eval(e, [:x Rational(7,1)]) == Rational(7, 1)
                """));
        }

        @Test void negEval() {
            assertEquals(true, run("""
                import algebra.expr
                let e = neg(lit(Rational(3, 1)))
                eval(e, [:_x 0]) == Rational(-3, 1)
                """));
        }
    }

    @Nested
    class Simplification {
        @Test void addZeroLeft() {
            // 0 + x → x
            assertEquals(true, run("""
                import algebra.expr
                let e = add(lit(Rational.zero), var(:x))
                let s = simplify(e)
                match s | Var(_) -> true | _ -> false
                """));
        }

        @Test void mulOneLeft() {
            // 1 * x → x
            assertEquals(true, run("""
                import algebra.expr
                let e = mul(lit(Rational.one), var(:x))
                let s = simplify(e)
                match s | Var(_) -> true | _ -> false
                """));
        }

        @Test void doubleNegation() {
            // --x → x
            assertEquals(true, run("""
                import algebra.expr
                let e = neg(neg(var(:x)))
                let s = simplify(e)
                match s | Var(_) -> true | _ -> false
                """));
        }

        @Test void foldLiterals() {
            // lit(2) + lit(3) → lit(5)
            assertEquals(true, run("""
                import algebra.expr
                let e = add(lit(Rational(2, 1)), lit(Rational(3, 1)))
                let s = simplify(e)
                match s | Lit(v) -> v == Rational(5, 1) | _ -> false
                """));
        }
    }

    @Nested
    class Proving {
        @Test
        @org.junit.jupiter.api.Disabled("structEq normalization returns false — prove.spn normalization logic bug")
        void proveIdentity() {
            // x + 0 == x (after normalization)
            assertEquals(true, run("""
                import algebra.prove
                let x = var(:x)
                prove(add(x, lit(Rational.zero)), x)
                """));
        }

        @Test
        @org.junit.jupiter.api.Disabled("[:] empty dict literal not supported — needs syntax or stdlib workaround")
        void proveNumerically() {
            // check 2*3 == 3*2 at x=5
            assertEquals(true, run("""
                import algebra.prove
                let a = mul(lit(Rational(2, 1)), lit(Rational(3, 1)))
                let b = mul(lit(Rational(3, 1)), lit(Rational(2, 1)))
                check(a, b, [:])
                """));
        }
    }
}
