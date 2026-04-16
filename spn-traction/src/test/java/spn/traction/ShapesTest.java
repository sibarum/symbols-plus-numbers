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

class ShapesTest {

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
    class Area {
        @Test
        void rectangleArea() {
            // Rect at origin, 3x4 → area 12
            assertEquals(true, run("""
                import geometry.shapes
                let r = Rect(Point(Rational(0,1), Rational(0,1)), Rational(3,1), Rational(4,1))
                area(r) == Rational(12, 1)
                """));
        }

        @Test
        @org.junit.jupiter.api.Disabled("Triangle area uses -det (unary neg on intermediate Rational) — needs deeper inference chain")
        void triangleArea() {
            // Triangle (0,0), (4,0), (0,3) → area = |4*3|/2 = 6
            assertEquals(true, run("""
                import geometry.shapes
                let t = Triangle(
                    Point(Rational(0,1), Rational(0,1)),
                    Point(Rational(4,1), Rational(0,1)),
                    Point(Rational(0,1), Rational(3,1)))
                area(t) == Rational(6, 1)
                """));
        }
    }

    @Nested
    class Containment {
        @Test
        @org.junit.jupiter.api.Disabled("Containment comparison on Rationals — needs investigation")
        void rectContainsInteriorPoint() {
            assertEquals(true, run("""
                import geometry.shapes
                let r = Rect(Point(Rational(0,1), Rational(0,1)), Rational(10,1), Rational(10,1))
                contains(r, Point(Rational(5,1), Rational(5,1)))
                """));
        }

        @Test
        @org.junit.jupiter.api.Disabled("Same as rectContainsInteriorPoint")
        void rectDoesNotContainExteriorPoint() {
            assertEquals(false, run("""
                import geometry.shapes
                let r = Rect(Point(Rational(0,1), Rational(0,1)), Rational(10,1), Rational(10,1))
                contains(r, Point(Rational(15,1), Rational(5,1)))
                """));
        }
    }

    @Nested
    class Transform {
        @Test void translateRect() {
            assertEquals(true, run("""
                import geometry.shapes
                let r = Rect(Point(Rational(1,1), Rational(2,1)), Rational(3,1), Rational(4,1))
                let offset = Point(Rational(10,1), Rational(20,1))
                let moved = translate(r, offset)
                match moved
                  | Rect(o, w, h) -> o == Point(Rational(11,1), Rational(22,1)) && w == Rational(3,1)
                  | _ -> false
                """));
        }
    }

    @Nested
    class Bounds {
        @Test
        @org.junit.jupiter.api.Disabled("Bounds uses rmin/rmax which use < on Rational — needs ordering dispatch")
        void triangleBounds() {
            assertEquals(true, run("""
                import geometry.shapes
                let t = Triangle(
                    Point(Rational(1,1), Rational(2,1)),
                    Point(Rational(5,1), Rational(1,1)),
                    Point(Rational(3,1), Rational(7,1)))
                let b = bounds(t)
                match b
                  | Rect(o, w, h) -> o == Point(Rational(1,1), Rational(1,1))
                  | _ -> false
                """));
        }
    }
}
