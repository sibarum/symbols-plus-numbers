package spn.traction;

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

class TypedCollectionTest {

    private SpnSymbolTable symbolTable;
    private SpnModuleRegistry registry;

    @BeforeEach
    void setUp() {
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
    class TypedArray {
        @Test void createAndPush() {
            assertEquals(true, run("""
                import Collections
                import numerics.rational

                type RationalArray = constructTypedArray(Rational)

                let arr = RationalArray([])
                let arr2 = arr.push(Rational(3, 4))
                arr2.length() == 1
                """));
        }

        @Test void getReturnsCorrectType() {
            assertEquals(true, run("""
                import Collections
                import numerics.rational

                type RationalArray = constructTypedArray(Rational)

                let arr = RationalArray([]).push(Rational(1, 2)).push(Rational(3, 4))
                arr.get(0) == Rational(1, 2) && arr.get(1) == Rational(3, 4)
                """));
        }

        @Test void promotionOnPush() {
            // Pushing an int should auto-promote to Rational via promote int -> Rational
            assertEquals(true, run("""
                import Collections
                import numerics.rational

                type RationalArray = constructTypedArray(Rational)

                let arr = RationalArray([]).push(5)
                arr.get(0) == Rational(5, 1)
                """));
        }

        @Test void multipleTypedArraysDontConflict() {
            // Two different typed arrays from the same macro
            assertEquals(true, run("""
                import Collections

                type Box(int)
                type Pair(int, int)

                type BoxArray = constructTypedArray(Box)
                type PairArray = constructTypedArray(Pair)

                let ba = BoxArray([]).push(Box(1)).push(Box(2))
                let pa = PairArray([]).push(Pair(3, 4))
                ba.length() == 2 && pa.length() == 1
                """));
        }
    }
}
