package spn.node;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import spn.language.SpnException;
import spn.node.expr.SpnDoubleLiteralNode;
import spn.node.expr.SpnLongLiteralNode;
import spn.node.type.SpnComponentAccessNodeGen;
import spn.node.type.SpnProductBinaryNode;
import spn.node.type.SpnProductConstructNode;
import spn.type.Operation;
import spn.type.SpnProductValue;
import spn.type.SpnTypeDescriptor;

import static org.junit.jupiter.api.Assertions.*;
import static spn.type.ComponentExpression.*;

class ProductTypeTest {

    private static Object execute(SpnExpressionNode node) {
        var root = new SpnRootNode(null, new FrameDescriptor(), node, "test");
        return root.getCallTarget().call();
    }

    // ════════════════════════════════════════════════════════════════════════
    // COMPLEX NUMBERS: Complex(real, imag)
    // ════════════════════════════════════════════════════════════════════════

    static final SpnTypeDescriptor COMPLEX = SpnTypeDescriptor.builder("Complex")
            .component("real")
            .component("imag")
            .productRule(Operation.ADD,
                    add(left(0), right(0)),     // real + real
                    add(left(1), right(1)))     // imag + imag
            .productRule(Operation.SUB,
                    sub(left(0), right(0)),
                    sub(left(1), right(1)))
            .productRule(Operation.MUL,
                    sub(mul(left(0), right(0)), mul(left(1), right(1))),  // ac - bd
                    add(mul(left(0), right(1)), mul(left(1), right(0)))) // ad + bc
            .build();

    @Nested
    class ComplexNumbers {

        private SpnExpressionNode complex(double real, double imag) {
            return new SpnProductConstructNode(COMPLEX,
                    new SpnDoubleLiteralNode(real),
                    new SpnDoubleLiteralNode(imag));
        }

        @Test
        void construct() {
            var node = complex(3.0, 4.0);
            var result = (SpnProductValue) execute(node);
            assertEquals(3.0, result.get(0));
            assertEquals(4.0, result.get(1));
            assertSame(COMPLEX, result.getType());
        }

        @Test
        void addition() {
            // (3 + 4i) + (1 + 2i) = (4 + 6i)
            var add = new SpnProductBinaryNode(
                    complex(3.0, 4.0), complex(1.0, 2.0),
                    COMPLEX, Operation.ADD);
            var result = (SpnProductValue) execute(add);
            assertEquals(4.0, result.get(0));
            assertEquals(6.0, result.get(1));
        }

        @Test
        void subtraction() {
            // (5 + 3i) - (2 + 1i) = (3 + 2i)
            var sub = new SpnProductBinaryNode(
                    complex(5.0, 3.0), complex(2.0, 1.0),
                    COMPLEX, Operation.SUB);
            var result = (SpnProductValue) execute(sub);
            assertEquals(3.0, result.get(0));
            assertEquals(2.0, result.get(1));
        }

        @Test
        void multiplication() {
            // (3 + 2i)(1 + 4i) = (3*1 - 2*4) + (3*4 + 2*1)i = -5 + 14i
            var mul = new SpnProductBinaryNode(
                    complex(3.0, 2.0), complex(1.0, 4.0),
                    COMPLEX, Operation.MUL);
            var result = (SpnProductValue) execute(mul);
            assertEquals(-5.0, result.get(0));
            assertEquals(14.0, result.get(1));
        }

        @Test
        void multiplicationIdentity() {
            // (a + bi)(1 + 0i) = (a + bi)
            var mul = new SpnProductBinaryNode(
                    complex(7.0, 3.0), complex(1.0, 0.0),
                    COMPLEX, Operation.MUL);
            var result = (SpnProductValue) execute(mul);
            assertEquals(7.0, result.get(0));
            assertEquals(3.0, result.get(1));
        }

        @Test
        void multiplicationByI() {
            // (a + bi)(0 + 1i) = (-b + ai)
            var mul = new SpnProductBinaryNode(
                    complex(3.0, 4.0), complex(0.0, 1.0),
                    COMPLEX, Operation.MUL);
            var result = (SpnProductValue) execute(mul);
            assertEquals(-4.0, result.get(0));
            assertEquals(3.0, result.get(1));
        }

        @Test
        void iSquaredIsNegativeOne() {
            // i * i = -1 + 0i
            var mul = new SpnProductBinaryNode(
                    complex(0.0, 1.0), complex(0.0, 1.0),
                    COMPLEX, Operation.MUL);
            var result = (SpnProductValue) execute(mul);
            assertEquals(-1.0, result.get(0));
            assertEquals(0.0, result.get(1));
        }

        @Test
        void chainedOperations() {
            // (1 + 2i) + (3 + 4i) = (4 + 6i), then * (1 + 0i) = (4 + 6i)
            var add = new SpnProductBinaryNode(
                    complex(1.0, 2.0), complex(3.0, 4.0),
                    COMPLEX, Operation.ADD);
            var mul = new SpnProductBinaryNode(
                    add, complex(1.0, 0.0),
                    COMPLEX, Operation.MUL);
            var result = (SpnProductValue) execute(mul);
            assertEquals(4.0, result.get(0));
            assertEquals(6.0, result.get(1));
        }

        @Test
        void undefinedOperationThrows() {
            // DIV is not defined for Complex
            var div = new SpnProductBinaryNode(
                    complex(1.0, 2.0), complex(3.0, 4.0),
                    COMPLEX, Operation.DIV);
            assertThrows(SpnException.class, () -> execute(div));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 2D VECTORS: Vec2(x, y)
    // ════════════════════════════════════════════════════════════════════════

    static final SpnTypeDescriptor VEC2 = SpnTypeDescriptor.builder("Vec2")
            .component("x")
            .component("y")
            .productRule(Operation.ADD,
                    add(left(0), right(0)),
                    add(left(1), right(1)))
            .productRule(Operation.SUB,
                    sub(left(0), right(0)),
                    sub(left(1), right(1)))
            // Component-wise multiplication (Hadamard product)
            .productRule(Operation.MUL,
                    mul(left(0), right(0)),
                    mul(left(1), right(1)))
            .build();

    @Nested
    class Vectors {

        private SpnExpressionNode vec2(double x, double y) {
            return new SpnProductConstructNode(VEC2,
                    new SpnDoubleLiteralNode(x),
                    new SpnDoubleLiteralNode(y));
        }

        @Test
        void vectorAddition() {
            var add = new SpnProductBinaryNode(
                    vec2(1.0, 2.0), vec2(3.0, 4.0),
                    VEC2, Operation.ADD);
            var result = (SpnProductValue) execute(add);
            assertEquals(4.0, result.get(0));
            assertEquals(6.0, result.get(1));
        }

        @Test
        void vectorSubtraction() {
            var sub = new SpnProductBinaryNode(
                    vec2(5.0, 3.0), vec2(2.0, 1.0),
                    VEC2, Operation.SUB);
            var result = (SpnProductValue) execute(sub);
            assertEquals(3.0, result.get(0));
            assertEquals(2.0, result.get(1));
        }

        @Test
        void hadamardProduct() {
            var mul = new SpnProductBinaryNode(
                    vec2(2.0, 3.0), vec2(4.0, 5.0),
                    VEC2, Operation.MUL);
            var result = (SpnProductValue) execute(mul);
            assertEquals(8.0, result.get(0));
            assertEquals(15.0, result.get(1));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 3-COMPONENT PRODUCT: Vec3(x, y, z) with cross product
    // ════════════════════════════════════════════════════════════════════════

    static final SpnTypeDescriptor VEC3 = SpnTypeDescriptor.builder("Vec3")
            .component("x")
            .component("y")
            .component("z")
            .productRule(Operation.ADD,
                    add(left(0), right(0)),
                    add(left(1), right(1)),
                    add(left(2), right(2)))
            // Cross product: a × b = (a.y*b.z - a.z*b.y, a.z*b.x - a.x*b.z, a.x*b.y - a.y*b.x)
            .productRule(Operation.MUL,
                    sub(mul(left(1), right(2)), mul(left(2), right(1))),  // y*z' - z*y'
                    sub(mul(left(2), right(0)), mul(left(0), right(2))),  // z*x' - x*z'
                    sub(mul(left(0), right(1)), mul(left(1), right(0)))) // x*y' - y*x'
            .build();

    @Nested
    class Vec3CrossProduct {

        private SpnExpressionNode vec3(double x, double y, double z) {
            return new SpnProductConstructNode(VEC3,
                    new SpnDoubleLiteralNode(x),
                    new SpnDoubleLiteralNode(y),
                    new SpnDoubleLiteralNode(z));
        }

        @Test
        void crossProductOfBasisVectors() {
            // i × j = k  →  (1,0,0) × (0,1,0) = (0,0,1)
            var cross = new SpnProductBinaryNode(
                    vec3(1.0, 0.0, 0.0), vec3(0.0, 1.0, 0.0),
                    VEC3, Operation.MUL);
            var result = (SpnProductValue) execute(cross);
            assertEquals(0.0, result.get(0));
            assertEquals(0.0, result.get(1));
            assertEquals(1.0, result.get(2));
        }

        @Test
        void crossProductAnticommutativity() {
            // a × b = -(b × a)
            var a = vec3(1.0, 2.0, 3.0);
            var b = vec3(4.0, 5.0, 6.0);

            // Need separate tree instances (Truffle nodes can't be shared)
            var ab = new SpnProductBinaryNode(
                    vec3(1.0, 2.0, 3.0), vec3(4.0, 5.0, 6.0),
                    VEC3, Operation.MUL);
            var ba = new SpnProductBinaryNode(
                    vec3(4.0, 5.0, 6.0), vec3(1.0, 2.0, 3.0),
                    VEC3, Operation.MUL);

            var abResult = (SpnProductValue) execute(ab);
            var baResult = (SpnProductValue) execute(ba);

            // Each component should be negated
            assertEquals(-(double) baResult.get(0), (double) abResult.get(0), 1e-10);
            assertEquals(-(double) baResult.get(1), (double) abResult.get(1), 1e-10);
            assertEquals(-(double) baResult.get(2), (double) abResult.get(2), 1e-10);
        }

        @Test
        void selfCrossProductIsZero() {
            // a × a = (0, 0, 0)
            var cross = new SpnProductBinaryNode(
                    vec3(3.0, 7.0, 2.0), vec3(3.0, 7.0, 2.0),
                    VEC3, Operation.MUL);
            var result = (SpnProductValue) execute(cross);
            assertEquals(0.0, result.get(0));
            assertEquals(0.0, result.get(1));
            assertEquals(0.0, result.get(2));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // COMPONENT ACCESS NODE
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class ComponentAccess {

        @Test
        void accessRealPart() {
            var construct = new SpnProductConstructNode(COMPLEX,
                    new SpnDoubleLiteralNode(3.0),
                    new SpnDoubleLiteralNode(4.0));
            var access = SpnComponentAccessNodeGen.create(construct, 0);
            assertEquals(3.0, execute(access));
        }

        @Test
        void accessImagPart() {
            var construct = new SpnProductConstructNode(COMPLEX,
                    new SpnDoubleLiteralNode(3.0),
                    new SpnDoubleLiteralNode(4.0));
            var access = SpnComponentAccessNodeGen.create(construct, 1);
            assertEquals(4.0, execute(access));
        }

        @Test
        void accessLongComponent() {
            // Product with long components
            var intVec = SpnTypeDescriptor.builder("IntVec2")
                    .component("x").component("y").build();
            var construct = new SpnProductConstructNode(intVec,
                    new SpnLongLiteralNode(10),
                    new SpnLongLiteralNode(20));
            var accessX = SpnComponentAccessNodeGen.create(construct, 0);
            assertEquals(10L, execute(accessX));
        }

        @Test
        void accessAfterOperation() {
            // (3+4i) + (1+2i) = (4+6i), then access real part → 4.0
            var add = new SpnProductBinaryNode(
                    new SpnProductConstructNode(COMPLEX,
                            new SpnDoubleLiteralNode(3.0), new SpnDoubleLiteralNode(4.0)),
                    new SpnProductConstructNode(COMPLEX,
                            new SpnDoubleLiteralNode(1.0), new SpnDoubleLiteralNode(2.0)),
                    COMPLEX, Operation.ADD);
            var accessReal = SpnComponentAccessNodeGen.create(add, 0);
            assertEquals(4.0, execute(accessReal));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // TYPE DESCRIPTOR: product type metadata
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class TypeMetadata {

        @Test
        void isProduct() {
            assertTrue(COMPLEX.isProduct());
            assertFalse(new SpnTypeDescriptor("Scalar").isProduct());
        }

        @Test
        void componentCount() {
            assertEquals(2, COMPLEX.componentCount());
            assertEquals(3, VEC3.componentCount());
        }

        @Test
        void componentIndex() {
            assertEquals(0, COMPLEX.componentIndex("real"));
            assertEquals(1, COMPLEX.componentIndex("imag"));
            assertEquals(-1, COMPLEX.componentIndex("missing"));
        }

        @Test
        void findProductOperation() {
            assertNotNull(COMPLEX.findProductOperation(Operation.MUL));
            assertNull(COMPLEX.findProductOperation(Operation.DIV));
        }

        @Test
        void toStringIncludesComponents() {
            String s = COMPLEX.toString();
            assertTrue(s.contains("Complex"));
            assertTrue(s.contains("real"));
            assertTrue(s.contains("imag"));
        }

        @Test
        void productValueToString() {
            var v = new SpnProductValue(COMPLEX, 3.0, 4.0);
            String s = v.toString();
            assertTrue(s.contains("Complex"));
            assertTrue(s.contains("real=3.0"));
            assertTrue(s.contains("imag=4.0"));
        }

        @Test
        void productValueEquality() {
            var a = new SpnProductValue(COMPLEX, 3.0, 4.0);
            var b = new SpnProductValue(COMPLEX, 3.0, 4.0);
            var c = new SpnProductValue(COMPLEX, 3.0, 5.0);
            assertEquals(a, b);
            assertNotEquals(a, c);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // LOCAL VARIABLES with product values
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void writeAndReadProductValue() {
        var builder = FrameDescriptor.newBuilder();
        int slot = builder.addSlot(FrameSlotKind.Object, "z", null);
        var descriptor = builder.build();

        var construct = new SpnProductConstructNode(COMPLEX,
                new SpnDoubleLiteralNode(3.0),
                new SpnDoubleLiteralNode(4.0));
        var write = spn.node.local.SpnWriteLocalVariableNodeGen.create(construct, slot);
        var read = spn.node.local.SpnReadLocalVariableNodeGen.create(slot);

        var body = new SpnExpressionNode() {
            @Child SpnExpressionNode writeNode = write;
            @Child SpnExpressionNode readNode = read;

            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                writeNode.executeGeneric(frame);
                return readNode.executeGeneric(frame);
            }
        };

        var root = new SpnRootNode(null, descriptor, body, "test");
        var result = (SpnProductValue) root.getCallTarget().call();
        assertEquals(3.0, result.get(0));
        assertEquals(4.0, result.get(1));
    }
}
