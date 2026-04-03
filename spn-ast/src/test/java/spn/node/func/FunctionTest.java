package spn.node.func;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.SpnRootNode;
import spn.node.expr.*;
import spn.node.local.SpnReadLocalVariableNodeGen;
import spn.node.match.MatchPattern;
import spn.node.match.SpnMatchBranchNode;
import spn.node.match.SpnMatchNode;
import spn.node.struct.SpnFieldAccessNodeGen;
import spn.node.struct.SpnStructConstructNode;
import spn.type.*;
import spn.type.check.Diagnostic;
import spn.type.check.TotalityChecker;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FunctionTest {

    // ── Shape ADT ───────────────────────────────────────────────────────────

    static final SpnStructDescriptor CIRCLE = new SpnStructDescriptor("Circle", "radius");
    static final SpnStructDescriptor RECTANGLE = new SpnStructDescriptor("Rectangle", "width", "height");
    static final SpnStructDescriptor TRIANGLE = new SpnStructDescriptor("Triangle", "a", "b", "c");
    static final SpnVariantSet SHAPE = new SpnVariantSet("Shape", CIRCLE, RECTANGLE, TRIANGLE);

    // ════════════════════════════════════════════════════════════════════════
    // BASIC FUNCTION: add(a, b) = a + b
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class BasicFunction {

        @Test
        void defineAndInvoke() {
            var desc = SpnFunctionDescriptor.pure("add")
                    .param("a", FieldType.LONG)
                    .param("b", FieldType.LONG)
                    .returns(FieldType.LONG)
                    .build();

            var fdBuilder = FrameDescriptor.newBuilder();
            int aSlot = fdBuilder.addSlot(FrameSlotKind.Object, "a", null);
            int bSlot = fdBuilder.addSlot(FrameSlotKind.Object, "b", null);

            var body = SpnAddNodeGen.create(
                    SpnReadLocalVariableNodeGen.create(aSlot),
                    SpnReadLocalVariableNodeGen.create(bSlot));

            var root = new SpnFunctionRootNode(null, fdBuilder.build(), desc,
                    new int[]{aSlot, bSlot}, body);

            assertEquals(7L, root.getCallTarget().call(3L, 4L));
            assertEquals(0L, root.getCallTarget().call(-5L, 5L));
        }

        @Test
        void invokeViaNode() {
            var desc = SpnFunctionDescriptor.pure("add")
                    .param("a", FieldType.LONG)
                    .param("b", FieldType.LONG)
                    .returns(FieldType.LONG)
                    .build();

            var fdBuilder = FrameDescriptor.newBuilder();
            int aSlot = fdBuilder.addSlot(FrameSlotKind.Object, "a", null);
            int bSlot = fdBuilder.addSlot(FrameSlotKind.Object, "b", null);

            var body = SpnAddNodeGen.create(
                    SpnReadLocalVariableNodeGen.create(aSlot),
                    SpnReadLocalVariableNodeGen.create(bSlot));

            var funcRoot = new SpnFunctionRootNode(null, fdBuilder.build(), desc,
                    new int[]{aSlot, bSlot}, body);

            // Invoke from another AST via SpnInvokeNode
            var invoke = new SpnInvokeNode(funcRoot.getCallTarget(),
                    new SpnLongLiteralNode(10),
                    new SpnLongLiteralNode(20));
            var callerRoot = new SpnRootNode(null, new FrameDescriptor(), invoke, "caller");
            assertEquals(30L, callerRoot.getCallTarget().call());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // ARG TYPE VALIDATION
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class ArgValidation {

        @Test
        void rejectsWrongArgType() {
            var desc = SpnFunctionDescriptor.pure("negate")
                    .param("n", FieldType.LONG)
                    .returns(FieldType.LONG)
                    .build();

            var fdBuilder = FrameDescriptor.newBuilder();
            int nSlot = fdBuilder.addSlot(FrameSlotKind.Object, "n", null);
            var body = SpnNegateNodeGen.create(SpnReadLocalVariableNodeGen.create(nSlot));

            var root = new SpnFunctionRootNode(null, fdBuilder.build(), desc,
                    new int[]{nSlot}, body);

            // Long → OK
            assertEquals(-5L, root.getCallTarget().call(5L));

            // String → rejected
            var ex = assertThrows(SpnException.class, () -> root.getCallTarget().call("hello"));
            assertTrue(ex.getMessage().contains("n"));
            assertTrue(ex.getMessage().contains("Long"));
        }

        @Test
        void rejectsWrongArity() {
            var desc = SpnFunctionDescriptor.pure("f")
                    .param("a", FieldType.LONG)
                    .param("b", FieldType.LONG)
                    .build();

            var fdBuilder = FrameDescriptor.newBuilder();
            int aSlot = fdBuilder.addSlot(FrameSlotKind.Object, "a", null);
            int bSlot = fdBuilder.addSlot(FrameSlotKind.Object, "b", null);
            var body = SpnReadLocalVariableNodeGen.create(aSlot);

            var root = new SpnFunctionRootNode(null, fdBuilder.build(), desc,
                    new int[]{aSlot, bSlot}, body);

            assertThrows(SpnException.class, () -> root.getCallTarget().call(1L));
        }

        @Test
        void untypedParamsAcceptAnything() {
            var desc = SpnFunctionDescriptor.pure("identity")
                    .param("x")
                    .build();

            var fdBuilder = FrameDescriptor.newBuilder();
            int xSlot = fdBuilder.addSlot(FrameSlotKind.Object, "x", null);
            var body = SpnReadLocalVariableNodeGen.create(xSlot);

            var root = new SpnFunctionRootNode(null, fdBuilder.build(), desc,
                    new int[]{xSlot}, body);

            assertEquals(42L, root.getCallTarget().call(42L));
            assertEquals("hello", root.getCallTarget().call("hello"));
            assertEquals(true, root.getCallTarget().call(true));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // RETURN TYPE VALIDATION
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class ReturnValidation {

        @Test
        void rejectsWrongReturnType() {
            // Function declared as returning Long, but body returns a String
            var desc = SpnFunctionDescriptor.pure("bad")
                    .param("x")
                    .returns(FieldType.LONG)
                    .build();

            var fdBuilder = FrameDescriptor.newBuilder();
            int xSlot = fdBuilder.addSlot(FrameSlotKind.Object, "x", null);
            var body = SpnReadLocalVariableNodeGen.create(xSlot); // returns whatever x is

            var root = new SpnFunctionRootNode(null, fdBuilder.build(), desc,
                    new int[]{xSlot}, body);

            // Long input → Long output → OK
            assertEquals(42L, root.getCallTarget().call(42L));

            // String input → String output → violates Long return type
            assertThrows(SpnException.class, () -> root.getCallTarget().call("oops"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // FUNCTION WITH PATTERN MATCHING BODY
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class PatternMatchingFunction {

        @Test
        void areaFunction() {
            // area(shape) = match shape {
            //   Circle(r) -> r (simplified)
            //   Rectangle(w, h) -> w * h (via inline node)
            //   Triangle(_, _, _) -> 0.0
            // }

            var desc = SpnFunctionDescriptor.pure("area")
                    .param("shape")
                    .returns(FieldType.DOUBLE)
                    .build();

            var fdBuilder = FrameDescriptor.newBuilder();
            int shapeSlot = fdBuilder.addSlot(FrameSlotKind.Object, "shape", null);
            int rSlot = fdBuilder.addSlot(FrameSlotKind.Object, "r", null);
            int wSlot = fdBuilder.addSlot(FrameSlotKind.Object, "w", null);
            int hSlot = fdBuilder.addSlot(FrameSlotKind.Object, "h", null);

            var circleBranch = new SpnMatchBranchNode(
                    new MatchPattern.Struct(CIRCLE), new int[]{rSlot},
                    SpnReadLocalVariableNodeGen.create(rSlot));

            var rectBody = new SpnExpressionNode() {
                @Child SpnExpressionNode rw = SpnReadLocalVariableNodeGen.create(wSlot);
                @Child SpnExpressionNode rh = SpnReadLocalVariableNodeGen.create(hSlot);
                @Override
                public Object executeGeneric(VirtualFrame frame) {
                    return (double) rw.executeGeneric(frame) * (double) rh.executeGeneric(frame);
                }
            };
            var rectBranch = new SpnMatchBranchNode(
                    new MatchPattern.Struct(RECTANGLE), new int[]{wSlot, hSlot}, rectBody);

            var triBranch = new SpnMatchBranchNode(
                    new MatchPattern.Struct(TRIANGLE), new int[]{-1, -1, -1},
                    new SpnDoubleLiteralNode(0.0));

            var matchBody = new SpnMatchNode(
                    SpnReadLocalVariableNodeGen.create(shapeSlot),
                    circleBranch, rectBranch, triBranch);

            var root = new SpnFunctionRootNode(null, fdBuilder.build(), desc,
                    new int[]{shapeSlot}, matchBody);

            var circle = new SpnStructValue(CIRCLE, 5.0);
            var rect = new SpnStructValue(RECTANGLE, 3.0, 4.0);
            var tri = new SpnStructValue(TRIANGLE, 3.0, 4.0, 5.0);

            assertEquals(5.0, root.getCallTarget().call(circle));
            assertEquals(12.0, root.getCallTarget().call(rect));
            assertEquals(0.0, root.getCallTarget().call(tri));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // TOTALITY CHECKER
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class Totality {

        private SpnMatchNode buildMatch(MatchPattern... patterns) {
            var branches = new SpnMatchBranchNode[patterns.length];
            for (int i = 0; i < patterns.length; i++) {
                branches[i] = new SpnMatchBranchNode(patterns[i], new int[]{},
                        new SpnLongLiteralNode(i));
            }
            return new SpnMatchNode(new SpnLongLiteralNode(0), branches);
        }

        @Test
        void exhaustiveWithWildcard() {
            var match = buildMatch(
                    new MatchPattern.Struct(CIRCLE),
                    new MatchPattern.Wildcard());

            var diagnostics = TotalityChecker.check(match, SHAPE);
            assertFalse(TotalityChecker.hasErrors(diagnostics));
        }

        @Test
        void exhaustiveWithAllVariants() {
            var match = buildMatch(
                    new MatchPattern.Struct(CIRCLE),
                    new MatchPattern.Struct(RECTANGLE),
                    new MatchPattern.Struct(TRIANGLE));

            var diagnostics = TotalityChecker.check(match, SHAPE);
            assertFalse(TotalityChecker.hasErrors(diagnostics));
        }

        @Test
        void nonExhaustiveMissingVariant() {
            var match = buildMatch(
                    new MatchPattern.Struct(CIRCLE),
                    new MatchPattern.Struct(RECTANGLE));
            // Missing: TRIANGLE

            var diagnostics = TotalityChecker.check(match, SHAPE);
            assertTrue(TotalityChecker.hasErrors(diagnostics));
            assertTrue(diagnostics.stream()
                    .anyMatch(d -> d.message().contains("Triangle")));
        }

        @Test
        void nonExhaustiveSingleVariant() {
            var match = buildMatch(new MatchPattern.Struct(CIRCLE));

            var diagnostics = TotalityChecker.check(match, SHAPE);
            assertTrue(TotalityChecker.hasErrors(diagnostics));
            assertTrue(diagnostics.stream()
                    .anyMatch(d -> d.message().contains("Rectangle")));
            assertTrue(diagnostics.stream()
                    .anyMatch(d -> d.message().contains("Triangle")));
        }

        @Test
        void noVariantSetWarns() {
            // Match on literals with no known variant set
            var match = buildMatch(
                    new MatchPattern.Literal(1L),
                    new MatchPattern.Literal(2L));

            var diagnostics = TotalityChecker.check(match); // no variant sets
            // Should warn, not error
            assertFalse(TotalityChecker.hasErrors(diagnostics));
            assertTrue(diagnostics.stream()
                    .anyMatch(d -> d.severity() == Diagnostic.Severity.WARNING));
        }

        @Test
        void nestedMatchChecked() {
            // A match inside another match -- both should be checked
            var innerMatch = buildMatch(new MatchPattern.Struct(CIRCLE)); // non-exhaustive

            var outerBranch = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{}, innerMatch);
            var outerMatch = new SpnMatchNode(new SpnLongLiteralNode(0), outerBranch);

            var diagnostics = TotalityChecker.check(outerMatch, SHAPE);
            // Outer is exhaustive (wildcard), inner is not (missing Rectangle, Triangle)
            assertTrue(TotalityChecker.hasErrors(diagnostics));
        }

        @Test
        void totalityOnFunctionBody() {
            // Build a function with a non-exhaustive match
            var desc = SpnFunctionDescriptor.pure("bad")
                    .param("shape")
                    .build();

            var fdBuilder = FrameDescriptor.newBuilder();
            int shapeSlot = fdBuilder.addSlot(FrameSlotKind.Object, "shape", null);

            // Only handles Circle, missing Rectangle and Triangle
            var matchBody = buildMatch(new MatchPattern.Struct(CIRCLE));

            var root = new SpnFunctionRootNode(null, fdBuilder.build(), desc,
                    new int[]{shapeSlot}, matchBody);

            var diagnostics = TotalityChecker.check(root, SHAPE);
            assertTrue(TotalityChecker.hasErrors(diagnostics));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // DESCRIPTOR METADATA
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class DescriptorMetadata {

        @Test
        void pureFlag() {
            var f = SpnFunctionDescriptor.pure("f").param("x").build();
            assertTrue(f.isPure());
        }

        @Test
        void arity() {
            var f = SpnFunctionDescriptor.pure("f")
                    .param("a").param("b").param("c").build();
            assertEquals(3, f.arity());
        }

        @Test
        void toStringTyped() {
            var f = SpnFunctionDescriptor.pure("add")
                    .param("a", FieldType.LONG)
                    .param("b", FieldType.LONG)
                    .returns(FieldType.LONG)
                    .build();
            String s = f.toString();
            assertTrue(s.contains("pure"));
            assertTrue(s.contains("add"));
            assertTrue(s.contains("Long"));
        }

        @Test
        void variantSetToString() {
            assertEquals("Shape = Circle | Rectangle | Triangle", SHAPE.toString());
        }
    }
}
