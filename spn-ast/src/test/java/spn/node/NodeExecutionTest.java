package spn.node;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import spn.language.SpnException;
import spn.node.expr.*;
import spn.node.type.SpnCheckConstraintNode;
import spn.node.type.SpnConstrainedBinaryNode;
import spn.node.type.SpnUnwrapConstrainedNodeGen;
import spn.type.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests that build Truffle ASTs by hand, execute them, and verify results.
 *
 * Each test creates an AST, wraps it in a SpnRootNode, gets a CallTarget, and calls it.
 * No parser needed -- we're testing the AST layer directly.
 */
class NodeExecutionTest {

    /** Executes an expression node and returns its result. */
    private static Object execute(SpnExpressionNode node) {
        var root = new SpnRootNode(null, new FrameDescriptor(), node, "test");
        return root.getCallTarget().call();
    }

    // ════════════════════════════════════════════════════════════════════════
    // BASIC ARITHMETIC (Layer 0 -- the original Truffle nodes)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class BasicArithmetic {

        @Test
        void longLiteral() {
            assertEquals(42L, execute(new SpnLongLiteralNode(42)));
        }

        @Test
        void doubleLiteral() {
            assertEquals(3.14, execute(new SpnDoubleLiteralNode(3.14)));
        }

        @Test
        void booleanLiteral() {
            assertEquals(true, execute(new SpnBooleanLiteralNode(true)));
        }

        @Test
        void stringLiteral() {
            assertEquals("hello", execute(new SpnStringLiteralNode("hello")));
        }

        @Test
        void addTwoLongs() {
            var add = SpnAddNodeGen.create(
                    new SpnLongLiteralNode(3),
                    new SpnLongLiteralNode(4));
            assertEquals(7L, execute(add));
        }

        @Test
        void addTwoDoubles() {
            var add = SpnAddNodeGen.create(
                    new SpnDoubleLiteralNode(1.5),
                    new SpnDoubleLiteralNode(2.5));
            assertEquals(4.0, execute(add));
        }

        @Test
        void addLongAndDouble() {
            // Implicit cast: long → double via SpnTypes @ImplicitCast
            var add = SpnAddNodeGen.create(
                    new SpnLongLiteralNode(3),
                    new SpnDoubleLiteralNode(0.14));
            assertEquals(3.14, (double) execute(add), 0.001);
        }

        @Test
        void concatStrings() {
            var concat = SpnStringConcatNodeGen.create(
                    new SpnStringLiteralNode("hello "),
                    new SpnStringLiteralNode("world"));
            assertEquals("hello world", execute(concat));
        }

        @Test
        void negateLong() {
            var neg = SpnNegateNodeGen.create(new SpnLongLiteralNode(42));
            assertEquals(-42L, execute(neg));
        }

        @Test
        void negateDouble() {
            var neg = SpnNegateNodeGen.create(new SpnDoubleLiteralNode(3.14));
            assertEquals(-3.14, execute(neg));
        }

        @Test
        void lessThanTrue() {
            var lt = SpnLessThanNodeGen.create(
                    new SpnLongLiteralNode(3),
                    new SpnLongLiteralNode(5));
            assertEquals(true, execute(lt));
        }

        @Test
        void lessThanFalse() {
            var lt = SpnLessThanNodeGen.create(
                    new SpnLongLiteralNode(5),
                    new SpnLongLiteralNode(3));
            assertEquals(false, execute(lt));
        }

        @Test
        void addTypeErrorThrows() {
            var add = SpnAddNodeGen.create(
                    new SpnLongLiteralNode(1),
                    new SpnBooleanLiteralNode(true));
            assertThrows(SpnException.class, () -> execute(add));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // CONSTRAINT CHECKING (Layer 1)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class ConstraintChecking {

        private final SpnTypeDescriptor natural = new SpnTypeDescriptor("Natural",
                new Constraint.GreaterThanOrEqual(0),
                new Constraint.ModuloEquals(1, 0));

        @Test
        void validValuePassesConstraints() {
            var node = new SpnCheckConstraintNode(new SpnLongLiteralNode(42), natural);
            Object result = execute(node);

            assertInstanceOf(SpnConstrainedValue.class, result);
            var cv = (SpnConstrainedValue) result;
            assertEquals(42L, cv.getValue());
            assertSame(natural, cv.getType());
        }

        @Test
        void negativeValueFails() {
            var node = new SpnCheckConstraintNode(new SpnLongLiteralNode(-1), natural);
            var ex = assertThrows(SpnException.class, () -> execute(node));
            assertTrue(ex.getMessage().contains("n >= 0"));
        }

        @Test
        void nonIntegerFails() {
            var node = new SpnCheckConstraintNode(new SpnDoubleLiteralNode(3.5), natural);
            var ex = assertThrows(SpnException.class, () -> execute(node));
            assertTrue(ex.getMessage().contains("n % 1 == 0"));
        }

        @Test
        void zeroIsValid() {
            var node = new SpnCheckConstraintNode(new SpnLongLiteralNode(0), natural);
            var cv = (SpnConstrainedValue) execute(node);
            assertEquals(0L, cv.getValue());
        }

        @Test
        void unwrapRecoversRawValue() {
            // Check → produces SpnConstrainedValue, then unwrap → raw long
            var check = new SpnCheckConstraintNode(new SpnLongLiteralNode(42), natural);
            var unwrap = SpnUnwrapConstrainedNodeGen.create(check);
            assertEquals(42L, execute(unwrap));
        }

        @Test
        void layer1CompositionAddTwoNaturals() {
            // The layer-1 pattern: unwrap → add → check
            var checkX = new SpnCheckConstraintNode(new SpnLongLiteralNode(10), natural);
            var checkY = new SpnCheckConstraintNode(new SpnLongLiteralNode(20), natural);
            var unwrapX = SpnUnwrapConstrainedNodeGen.create(checkX);
            var unwrapY = SpnUnwrapConstrainedNodeGen.create(checkY);
            var add = SpnAddNodeGen.create(unwrapX, unwrapY);
            var checkResult = new SpnCheckConstraintNode(add, natural);

            var cv = (SpnConstrainedValue) execute(checkResult);
            assertEquals(30L, cv.getValue());
        }

        @Test
        void layer1SubtractionViolatesConstraint() {
            // 3 + (-10) = -7 which violates n >= 0
            var checkX = new SpnCheckConstraintNode(new SpnLongLiteralNode(3), natural);
            var checkY = new SpnCheckConstraintNode(new SpnLongLiteralNode(-10), natural);

            // -10 itself fails the Natural constraint
            assertThrows(SpnException.class, () -> execute(checkY));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // DISTINGUISHED ELEMENTS (Layer 2)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class DistinguishedElements {

        private final SpnDistinguishedElement omega = new SpnDistinguishedElement("Omega");

        private final SpnTypeDescriptor extNat = SpnTypeDescriptor.builder("ExtNat")
                .constraint(new Constraint.GreaterThanOrEqual(0))
                .constraint(new Constraint.ModuloEquals(1, 0))
                .element(omega)
                .rule(new AlgebraicRule(Operation.DIV,
                        new OperandPattern.Any(), new OperandPattern.ExactLong(0), omega))
                .rule(new AlgebraicRule(Operation.ADD,
                        new OperandPattern.IsElement(omega), new OperandPattern.Any(), omega))
                .rule(new AlgebraicRule(Operation.ADD,
                        new OperandPattern.Any(), new OperandPattern.IsElement(omega), omega))
                .build();

        @Test
        void elementBypassesConstraintCheck() {
            // A node that produces Omega directly
            var omegaLiteral = new SpnExpressionNode() {
                @Override
                public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                    return omega;
                }
            };
            var check = new SpnCheckConstraintNode(omegaLiteral, extNat);

            var cv = (SpnConstrainedValue) execute(check);
            assertSame(omega, cv.getValue());
        }

        @Test
        void foreignElementRejected() {
            var foreign = new SpnDistinguishedElement("Foreign");
            var foreignLiteral = new SpnExpressionNode() {
                @Override
                public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                    return foreign;
                }
            };
            var check = new SpnCheckConstraintNode(foreignLiteral, extNat);

            assertThrows(SpnException.class, () -> execute(check));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // ALGEBRAIC RULES via SpnConstrainedBinaryNode (Layer 2)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class AlgebraicRules {

        private final SpnDistinguishedElement omega = new SpnDistinguishedElement("Omega");

        private final SpnTypeDescriptor extNat = SpnTypeDescriptor.builder("ExtNat")
                .constraint(new Constraint.GreaterThanOrEqual(0))
                .constraint(new Constraint.ModuloEquals(1, 0))
                .element(omega)
                // n / 0 = Omega
                .rule(new AlgebraicRule(Operation.DIV,
                        new OperandPattern.Any(), new OperandPattern.ExactLong(0), omega))
                // Omega + n = Omega, n + Omega = Omega
                .rule(new AlgebraicRule(Operation.ADD,
                        new OperandPattern.IsElement(omega), new OperandPattern.Any(), omega))
                .rule(new AlgebraicRule(Operation.ADD,
                        new OperandPattern.Any(), new OperandPattern.IsElement(omega), omega))
                .build();

        /** Wraps a long literal in a SpnConstrainedValue for this type. */
        private SpnExpressionNode constrained(long value) {
            return new SpnCheckConstraintNode(new SpnLongLiteralNode(value), extNat);
        }

        @Test
        void divisionByZeroProducesOmega() {
            var div = new SpnConstrainedBinaryNode(
                    constrained(10), constrained(0),
                    extNat, Operation.DIV);

            var cv = (SpnConstrainedValue) execute(div);
            assertSame(omega, cv.getValue());
        }

        @Test
        void normalDivisionWorks() {
            var div = new SpnConstrainedBinaryNode(
                    constrained(10), constrained(2),
                    extNat, Operation.DIV);

            var cv = (SpnConstrainedValue) execute(div);
            assertEquals(5L, cv.getValue());
        }

        @Test
        void normalAdditionWorks() {
            var add = new SpnConstrainedBinaryNode(
                    constrained(7), constrained(3),
                    extNat, Operation.ADD);

            var cv = (SpnConstrainedValue) execute(add);
            assertEquals(10L, cv.getValue());
        }

        @Test
        void constraintViolationOnResult() {
            // 3 - 5 = -2, which violates n >= 0 and there's no rule to save it
            var sub = new SpnConstrainedBinaryNode(
                    constrained(3), constrained(5),
                    extNat, Operation.SUB);

            var ex = assertThrows(SpnException.class, () -> execute(sub));
            assertTrue(ex.getMessage().contains("n >= 0"));
        }

        @Test
        void chainedOperationsWithOmega() {
            // (10 / 0) + 5 → Omega + 5 → Omega (via rule)
            var div = new SpnConstrainedBinaryNode(
                    constrained(10), constrained(0),
                    extNat, Operation.DIV);

            var add = new SpnConstrainedBinaryNode(
                    div, constrained(5),
                    extNat, Operation.ADD);

            var cv = (SpnConstrainedValue) execute(add);
            assertSame(omega, cv.getValue());
        }

        @Test
        void multiplicationWithoutOmegaRule() {
            // Omega has no MUL rule in this type — should error
            var div = new SpnConstrainedBinaryNode(
                    constrained(10), constrained(0),
                    extNat, Operation.DIV);

            var mul = new SpnConstrainedBinaryNode(
                    div, constrained(5),
                    extNat, Operation.MUL);

            var ex = assertThrows(SpnException.class, () -> execute(mul));
            assertTrue(ex.getMessage().contains("No rule defined"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // LOCAL VARIABLES (frame slot read/write)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class LocalVariables {

        @Test
        void writeAndReadLong() {
            // Build a frame with one slot, write 42, then read it
            var builder = FrameDescriptor.newBuilder();
            int slot = builder.addSlot(FrameSlotKind.Long, "x", null);
            var descriptor = builder.build();

            var write = spn.node.local.SpnWriteLocalVariableNodeGen.create(
                    new SpnLongLiteralNode(42), slot);
            var read = spn.node.local.SpnReadLocalVariableNodeGen.create(slot);

            // Block: write x = 42, then the root returns read(x)
            // We need a root that executes write as statement, then returns read.
            // Simplest: wrap in a root that writes and reads in sequence.
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
            assertEquals(42L, root.getCallTarget().call());
        }

        @Test
        void writeAndReadDouble() {
            var builder = FrameDescriptor.newBuilder();
            int slot = builder.addSlot(FrameSlotKind.Double, "x", null);
            var descriptor = builder.build();

            var write = spn.node.local.SpnWriteLocalVariableNodeGen.create(
                    new SpnDoubleLiteralNode(3.14), slot);
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
            assertEquals(3.14, root.getCallTarget().call());
        }

        @Test
        void writeConstrainedValueAndReadBack() {
            var natural = new SpnTypeDescriptor("Natural",
                    new Constraint.GreaterThanOrEqual(0));

            var builder = FrameDescriptor.newBuilder();
            int slot = builder.addSlot(FrameSlotKind.Object, "x", null);
            var descriptor = builder.build();

            // Write a constrained value to x, then read it back
            var checkedLiteral = new SpnCheckConstraintNode(
                    new SpnLongLiteralNode(42), natural);
            var write = spn.node.local.SpnWriteLocalVariableNodeGen.create(checkedLiteral, slot);
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
            Object result = root.getCallTarget().call();
            assertInstanceOf(SpnConstrainedValue.class, result);
            assertEquals(42L, ((SpnConstrainedValue) result).getValue());
        }
    }
}
