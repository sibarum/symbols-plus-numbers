package spn.type;

import com.oracle.truffle.api.frame.FrameDescriptor;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.SpnRootNode;
import spn.node.expr.*;
import spn.node.struct.SpnStructConstructNode;
import spn.node.type.SpnProductBinaryNode;
import spn.node.type.SpnProductConstructNode;

import static org.junit.jupiter.api.Assertions.*;
import static spn.type.ComponentExpression.*;

class ComponentConstraintTest {

    static final SpnSymbolTable TABLE = new SpnSymbolTable();
    static final SpnSymbol RED = TABLE.intern("red");
    static final SpnSymbol GREEN = TABLE.intern("green");
    static final SpnSymbol BLUE = TABLE.intern("blue");
    static final SpnSymbol YELLOW = TABLE.intern("yellow");

    private static Object execute(SpnExpressionNode node) {
        return new SpnRootNode(null, new FrameDescriptor(), node, "test")
                .getCallTarget().call();
    }

    // ════════════════════════════════════════════════════════════════════════
    // TYPED COMPONENTS
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class TypedComponents {

        static final SpnTypeDescriptor TYPED_VEC2 = SpnTypeDescriptor.builder("Vec2")
                .component("x", FieldType.DOUBLE)
                .component("y", FieldType.DOUBLE)
                .productRule(Operation.ADD,
                        add(left(0), right(0)),
                        add(left(1), right(1)))
                .build();

        @Test
        void constructWithCorrectTypes() {
            var node = new SpnProductConstructNode(TYPED_VEC2,
                    new SpnDoubleLiteralNode(1.0),
                    new SpnDoubleLiteralNode(2.0));
            var result = (SpnProductValue) execute(node);
            assertEquals(1.0, result.get(0));
            assertEquals(2.0, result.get(1));
        }

        @Test
        void constructRejectsWrongType() {
            var node = new SpnProductConstructNode(TYPED_VEC2,
                    new SpnLongLiteralNode(1),         // Long, not Double!
                    new SpnDoubleLiteralNode(2.0));
            var ex = assertThrows(SpnException.class, () -> execute(node));
            assertTrue(ex.getMessage().contains("x"));
            assertTrue(ex.getMessage().contains("Double"));
        }

        @Test
        void operationPreservesTypes() {
            // Vec2(1.0, 2.0) + Vec2(3.0, 4.0) → Vec2(4.0, 6.0)
            var add = new SpnProductBinaryNode(
                    new SpnProductConstructNode(TYPED_VEC2,
                            new SpnDoubleLiteralNode(1.0), new SpnDoubleLiteralNode(2.0)),
                    new SpnProductConstructNode(TYPED_VEC2,
                            new SpnDoubleLiteralNode(3.0), new SpnDoubleLiteralNode(4.0)),
                    TYPED_VEC2, Operation.ADD);
            var result = (SpnProductValue) execute(add);
            assertEquals(4.0, result.get(0));
            assertEquals(6.0, result.get(1));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SYMBOL COMPONENTS WITH CONSTRAINTS
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class SymbolComponents {

        // ColoredNumber(n: Double, color: Symbol where oneOf(:red, :green, :blue))
        static final SpnTypeDescriptor COLORED_NUMBER = SpnTypeDescriptor.builder("ColoredNumber")
                .component("n", FieldType.DOUBLE)
                .component("color", FieldType.SYMBOL,
                        Constraint.SymbolOneOf.of(RED, GREEN, BLUE))
                .build();

        @Test
        void constructWithValidSymbol() {
            var node = new SpnProductConstructNode(COLORED_NUMBER,
                    new SpnDoubleLiteralNode(42.0),
                    new SpnSymbolLiteralNode(RED));
            var result = (SpnProductValue) execute(node);
            assertEquals(42.0, result.get(0));
            assertSame(RED, result.get(1));
        }

        @Test
        void rejectsInvalidSymbol() {
            var node = new SpnProductConstructNode(COLORED_NUMBER,
                    new SpnDoubleLiteralNode(42.0),
                    new SpnSymbolLiteralNode(YELLOW));  // not in oneOf(:red, :green, :blue)
            var ex = assertThrows(SpnException.class, () -> execute(node));
            assertTrue(ex.getMessage().contains("color"));
            assertTrue(ex.getMessage().contains("oneOf"));
        }

        @Test
        void rejectsNonSymbolInSymbolSlot() {
            var node = new SpnProductConstructNode(COLORED_NUMBER,
                    new SpnDoubleLiteralNode(42.0),
                    new SpnStringLiteralNode("red"));  // String, not Symbol!
            var ex = assertThrows(SpnException.class, () -> execute(node));
            assertTrue(ex.getMessage().contains("color"));
            assertTrue(ex.getMessage().contains("Symbol"));
        }

        @Test
        void rejectsNonDoubleInNumberSlot() {
            var node = new SpnProductConstructNode(COLORED_NUMBER,
                    new SpnLongLiteralNode(42),        // Long, not Double!
                    new SpnSymbolLiteralNode(RED));
            var ex = assertThrows(SpnException.class, () -> execute(node));
            assertTrue(ex.getMessage().contains("n"));
            assertTrue(ex.getMessage().contains("Double"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // MIXED NUMERIC + SYMBOL WITH OPERATIONS
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class MixedOperations {

        // A type where addition adds the numbers but keeps the left color
        // (number, symbol) + (number, symbol) → (left.n + right.n, left.color)
        static final SpnTypeDescriptor COLORED = SpnTypeDescriptor.builder("Colored")
                .component("n", FieldType.DOUBLE)
                .component("color", FieldType.SYMBOL,
                        Constraint.SymbolOneOf.of(RED, GREEN, BLUE))
                .productRule(Operation.ADD,
                        add(left(0), right(0)),    // add the numbers
                        left(1))                   // keep left color
                .build();

        @Test
        void addKeepsLeftColor() {
            var a = new SpnProductConstructNode(COLORED,
                    new SpnDoubleLiteralNode(10.0), new SpnSymbolLiteralNode(RED));
            var b = new SpnProductConstructNode(COLORED,
                    new SpnDoubleLiteralNode(20.0), new SpnSymbolLiteralNode(BLUE));

            var add = new SpnProductBinaryNode(a, b, COLORED, Operation.ADD);
            var result = (SpnProductValue) execute(add);
            assertEquals(30.0, result.get(0));
            assertSame(RED, result.get(1));  // left color preserved
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // USE AS STRUCT FIELD TYPE
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class AsStructField {

        static final SpnTypeDescriptor COLOR_TYPE = SpnTypeDescriptor.builder("Color")
                .component("value", FieldType.SYMBOL,
                        Constraint.SymbolOneOf.of(RED, GREEN, BLUE))
                .build();

        @Test
        void structFieldReferencesProductType() {
            // A struct with a Color field (which is a numeric/algebraic type)
            var pixel = SpnStructDescriptor.builder("Pixel")
                    .field("color", FieldType.ofProduct(COLOR_TYPE))
                    .field("x", FieldType.LONG)
                    .field("y", FieldType.LONG)
                    .build();

            var colorValue = new SpnProductConstructNode(COLOR_TYPE,
                    new SpnSymbolLiteralNode(RED));
            var node = new SpnStructConstructNode(pixel,
                    colorValue,
                    new SpnLongLiteralNode(10),
                    new SpnLongLiteralNode(20));

            var result = (SpnStructValue) execute(node);
            assertInstanceOf(SpnProductValue.class, result.get(0));
            assertEquals(10L, result.get(1));
            assertEquals(20L, result.get(2));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // COMPONENT DESCRIPTOR METADATA
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class Metadata {

        @Test
        void untypedComponentHasNoValidation() {
            var cd = new ComponentDescriptor("x", 0);
            assertFalse(cd.hasValidation());
            assertNull(cd.validate(42L));
            assertNull(cd.validate("anything"));
        }

        @Test
        void typedComponentValidates() {
            var cd = new ComponentDescriptor("x", 0, FieldType.DOUBLE);
            assertTrue(cd.hasValidation());
            assertNull(cd.validate(3.14));
            assertNotNull(cd.validate(42L));
        }

        @Test
        void constrainedComponentValidates() {
            var cd = new ComponentDescriptor("color", 0, FieldType.SYMBOL,
                    Constraint.SymbolOneOf.of(RED, GREEN));
            assertNull(cd.validate(RED));
            assertNull(cd.validate(GREEN));
            assertNotNull(cd.validate(BLUE));     // not in oneOf
            assertNotNull(cd.validate("string")); // not a symbol
        }

        @Test
        void toStringShowsTypeAndConstraints() {
            var cd = new ComponentDescriptor("color", 0, FieldType.SYMBOL,
                    Constraint.SymbolOneOf.of(RED, GREEN));
            String s = cd.toString();
            assertTrue(s.contains("color"));
            assertTrue(s.contains("Symbol"));
            assertTrue(s.contains("oneOf"));
        }

        @Test
        void typeDescriptorToStringShowsComponents() {
            var type = SpnTypeDescriptor.builder("T")
                    .component("n", FieldType.DOUBLE)
                    .component("s", FieldType.SYMBOL, Constraint.SymbolOneOf.of(RED))
                    .build();
            String s = type.toString();
            assertTrue(s.contains("n: Double"));
            assertTrue(s.contains("s: Symbol"));
            assertTrue(s.contains("oneOf"));
        }

        @Test
        void hasComponentValidationFlag() {
            var typed = SpnTypeDescriptor.builder("T")
                    .component("x", FieldType.DOUBLE)
                    .build();
            assertTrue(typed.hasComponentValidation());

            var untyped = SpnTypeDescriptor.builder("U")
                    .component("x")
                    .build();
            assertFalse(untyped.hasComponentValidation());
        }
    }
}
