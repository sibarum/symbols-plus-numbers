package spn.node.match;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.SpnRootNode;
import spn.node.expr.*;
import spn.node.struct.SpnFieldAccessNodeGen;
import spn.node.struct.SpnStructConstructNode;
import spn.type.SpnStructDescriptor;
import spn.type.SpnStructValue;
import spn.type.SpnTypeDescriptor;
import spn.type.SpnProductValue;
import spn.node.type.SpnProductConstructNode;

import static org.junit.jupiter.api.Assertions.*;
import static spn.type.ComponentExpression.*;

class StructAndMatchTest {

    // ── Shape ADT: Circle(radius) | Rectangle(width, height) | Triangle(a, b, c)
    static final SpnStructDescriptor CIRCLE = new SpnStructDescriptor("Circle", "radius");
    static final SpnStructDescriptor RECTANGLE = new SpnStructDescriptor("Rectangle", "width", "height");
    static final SpnStructDescriptor TRIANGLE = new SpnStructDescriptor("Triangle", "a", "b", "c");

    private static Object execute(SpnExpressionNode node) {
        return new SpnRootNode(null, new FrameDescriptor(), node, "test")
                .getCallTarget().call();
    }

    private static Object executeWithFrame(FrameDescriptor desc, SpnExpressionNode node) {
        return new SpnRootNode(null, desc, node, "test")
                .getCallTarget().call();
    }

    // ════════════════════════════════════════════════════════════════════════
    // STRUCT CONSTRUCTION AND FIELD ACCESS
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class StructBasics {

        @Test
        void constructCircle() {
            var node = new SpnStructConstructNode(CIRCLE, new SpnDoubleLiteralNode(5.0));
            var result = (SpnStructValue) execute(node);
            assertSame(CIRCLE, result.getDescriptor());
            assertEquals(5.0, result.get(0));
        }

        @Test
        void constructRectangle() {
            var node = new SpnStructConstructNode(RECTANGLE,
                    new SpnDoubleLiteralNode(3.0),
                    new SpnDoubleLiteralNode(4.0));
            var result = (SpnStructValue) execute(node);
            assertSame(RECTANGLE, result.getDescriptor());
            assertEquals(3.0, result.get(0));
            assertEquals(4.0, result.get(1));
        }

        @Test
        void fieldAccess() {
            var construct = new SpnStructConstructNode(RECTANGLE,
                    new SpnDoubleLiteralNode(3.0),
                    new SpnDoubleLiteralNode(4.0));
            var accessWidth = SpnFieldAccessNodeGen.create(construct, 0);
            assertEquals(3.0, execute(accessWidth));
        }

        @Test
        void fieldAccessByName() {
            assertEquals(0, CIRCLE.fieldIndex("radius"));
            assertEquals(0, RECTANGLE.fieldIndex("width"));
            assertEquals(1, RECTANGLE.fieldIndex("height"));
            assertEquals(-1, RECTANGLE.fieldIndex("missing"));
        }

        @Test
        void structToString() {
            var v = new SpnStructValue(CIRCLE, 5.0);
            assertEquals("Circle(radius=5.0)", v.toString());
        }

        @Test
        void structEquality() {
            var a = new SpnStructValue(CIRCLE, 5.0);
            var b = new SpnStructValue(CIRCLE, 5.0);
            var c = new SpnStructValue(CIRCLE, 6.0);
            assertEquals(a, b);
            assertNotEquals(a, c);
        }

        @Test
        void differentDescriptorsAreDistinct() {
            var other = new SpnStructDescriptor("Circle", "radius");
            var a = new SpnStructValue(CIRCLE, 5.0);
            var b = new SpnStructValue(other, 5.0);
            assertNotEquals(a, b); // same name, different descriptor objects
        }

        @Test
        void nestedStructs() {
            // Pair(first: Circle, second: Rectangle)
            var pair = new SpnStructDescriptor("Pair", "first", "second");
            var node = new SpnStructConstructNode(pair,
                    new SpnStructConstructNode(CIRCLE, new SpnDoubleLiteralNode(1.0)),
                    new SpnStructConstructNode(RECTANGLE,
                            new SpnDoubleLiteralNode(2.0), new SpnDoubleLiteralNode(3.0)));
            var result = (SpnStructValue) execute(node);
            assertInstanceOf(SpnStructValue.class, result.get(0));
            assertInstanceOf(SpnStructValue.class, result.get(1));
            assertEquals(1.0, ((SpnStructValue) result.get(0)).get(0));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PATTERN MATCHING: match on struct type
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class PatternMatching {

        /**
         * Builds the Shape area computation as a match expression:
         *   match shape {
         *     Circle(r)       -> 3.14159 * r * r
         *     Rectangle(w, h) -> w * h
         *     Triangle(a,b,c) -> 0  (placeholder)
         *   }
         */
        private SpnExpressionNode buildAreaMatch(SpnExpressionNode subject,
                                                  FrameDescriptor.Builder fdBuilder) {
            int rSlot = fdBuilder.addSlot(FrameSlotKind.Object, "r", null);
            int wSlot = fdBuilder.addSlot(FrameSlotKind.Object, "w", null);
            int hSlot = fdBuilder.addSlot(FrameSlotKind.Object, "h", null);

            // Circle(r) -> 3.14159 * r * r
            var circleBranch = new SpnMatchBranchNode(
                    new MatchPattern.Struct(CIRCLE),
                    new int[]{rSlot},
                    SpnAddNodeGen.create(  // using MUL would be better, but we have ADD
                            new SpnDoubleLiteralNode(0.0),  // placeholder: just return r
                            spn.node.local.SpnReadLocalVariableNodeGen.create(rSlot)));

            // Rectangle(w, h) -> w * h (simulate with a node that reads both)
            var rectBody = new SpnExpressionNode() {
                @Child SpnExpressionNode readW = spn.node.local.SpnReadLocalVariableNodeGen.create(wSlot);
                @Child SpnExpressionNode readH = spn.node.local.SpnReadLocalVariableNodeGen.create(hSlot);
                @Override
                public Object executeGeneric(VirtualFrame frame) {
                    double w = (double) readW.executeGeneric(frame);
                    double h = (double) readH.executeGeneric(frame);
                    return w * h;
                }
            };
            var rectBranch = new SpnMatchBranchNode(
                    new MatchPattern.Struct(RECTANGLE),
                    new int[]{wSlot, hSlot},
                    rectBody);

            // Triangle -> 0 (ignoring fields)
            var triBranch = new SpnMatchBranchNode(
                    new MatchPattern.Struct(TRIANGLE),
                    new int[]{-1, -1, -1},  // skip all fields
                    new SpnDoubleLiteralNode(0.0));

            return new SpnMatchNode(subject, circleBranch, rectBranch, triBranch);
        }

        @Test
        void matchCircle() {
            var fdBuilder = FrameDescriptor.newBuilder();
            int shapeSlot = fdBuilder.addSlot(FrameSlotKind.Object, "shape", null);

            var circleVal = new SpnStructConstructNode(CIRCLE, new SpnDoubleLiteralNode(5.0));
            var subject = new InlineWriteRead(circleVal, shapeSlot);
            var match = buildAreaMatch(subject, fdBuilder);
            var desc = fdBuilder.build();

            // Circle(5.0) → 0.0 + 5.0 = 5.0 (our simplified area)
            Object result = executeWithFrame(desc, match);
            assertEquals(5.0, (double) result, 0.001);
        }

        @Test
        void matchRectangle() {
            var fdBuilder = FrameDescriptor.newBuilder();
            int shapeSlot = fdBuilder.addSlot(FrameSlotKind.Object, "shape", null);

            var rectVal = new SpnStructConstructNode(RECTANGLE,
                    new SpnDoubleLiteralNode(3.0), new SpnDoubleLiteralNode(4.0));
            var subject = new InlineWriteRead(rectVal, shapeSlot);
            var match = buildAreaMatch(subject, fdBuilder);
            var desc = fdBuilder.build();

            // Rectangle(3, 4) → 3 * 4 = 12
            Object result = executeWithFrame(desc, match);
            assertEquals(12.0, result);
        }

        @Test
        void matchTriangle() {
            var fdBuilder = FrameDescriptor.newBuilder();
            int shapeSlot = fdBuilder.addSlot(FrameSlotKind.Object, "shape", null);

            var triVal = new SpnStructConstructNode(TRIANGLE,
                    new SpnDoubleLiteralNode(3.0),
                    new SpnDoubleLiteralNode(4.0),
                    new SpnDoubleLiteralNode(5.0));
            var subject = new InlineWriteRead(triVal, shapeSlot);
            var match = buildAreaMatch(subject, fdBuilder);
            var desc = fdBuilder.build();

            // Triangle → 0.0
            assertEquals(0.0, executeWithFrame(desc, match));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // WILDCARD PATTERN
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class WildcardPatterns {

        @Test
        void wildcardMatchesAnything() {
            var branch = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnLongLiteralNode(42));

            var match = new SpnMatchNode(new SpnLongLiteralNode(99), branch);
            assertEquals(42L, execute(match));
        }

        @Test
        void wildcardWithBinding() {
            var fdBuilder = FrameDescriptor.newBuilder();
            int slot = fdBuilder.addSlot(FrameSlotKind.Object, "x", null);
            var desc = fdBuilder.build();

            // match 99 { x -> x }  (wildcard that binds the value)
            var read = spn.node.local.SpnReadLocalVariableNodeGen.create(slot);
            var branch = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{slot}, read);
            var match = new SpnMatchNode(new SpnLongLiteralNode(99), branch);

            assertEquals(99L, executeWithFrame(desc, match));
        }

        @Test
        void wildcardAsDefault() {
            // match "hello" { 42 -> "number", _ -> "other" }
            var litBranch = new SpnMatchBranchNode(
                    new MatchPattern.Literal(42L), new int[]{},
                    new SpnStringLiteralNode("number"));
            var defaultBranch = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("other"));

            var match = new SpnMatchNode(
                    new SpnStringLiteralNode("hello"), litBranch, defaultBranch);
            assertEquals("other", execute(match));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // LITERAL PATTERNS
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class LiteralPatterns {

        @Test
        void matchLong() {
            var branch1 = new SpnMatchBranchNode(
                    new MatchPattern.Literal(1L), new int[]{},
                    new SpnStringLiteralNode("one"));
            var branch2 = new SpnMatchBranchNode(
                    new MatchPattern.Literal(2L), new int[]{},
                    new SpnStringLiteralNode("two"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("other"));

            assertEquals("one", execute(new SpnMatchNode(new SpnLongLiteralNode(1), branch1, branch2, fallback)));
            assertEquals("two", execute(new SpnMatchNode(new SpnLongLiteralNode(2), branch1, branch2, fallback)));
            assertEquals("other", execute(new SpnMatchNode(new SpnLongLiteralNode(3), branch1, branch2, fallback)));
        }

        @Test
        void matchString() {
            var branch = new SpnMatchBranchNode(
                    new MatchPattern.Literal("hello"), new int[]{},
                    new SpnLongLiteralNode(1));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnLongLiteralNode(0));

            assertEquals(1L, execute(new SpnMatchNode(new SpnStringLiteralNode("hello"), branch, fallback)));
            assertEquals(0L, execute(new SpnMatchNode(new SpnStringLiteralNode("world"), branch, fallback)));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // GUARD CLAUSES
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class Guards {

        @Test
        void guardFilters() {
            // match n { x | x < 0 -> "negative", x | x > 0 -> "positive", _ -> "zero" }
            var fdBuilder = FrameDescriptor.newBuilder();
            int xSlot = fdBuilder.addSlot(FrameSlotKind.Object, "x", null);
            var desc = fdBuilder.build();

            var readX = spn.node.local.SpnReadLocalVariableNodeGen.create(xSlot);

            var negativeBranch = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{xSlot},
                    SpnLessThanNodeGen.create(
                            spn.node.local.SpnReadLocalVariableNodeGen.create(xSlot),
                            new SpnLongLiteralNode(0)),
                    new SpnStringLiteralNode("negative"));

            var positiveBranch = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{xSlot},
                    SpnLessThanNodeGen.create(
                            new SpnLongLiteralNode(0),
                            spn.node.local.SpnReadLocalVariableNodeGen.create(xSlot)),
                    new SpnStringLiteralNode("positive"));

            var zeroBranch = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("zero"));

            var matchNeg = new SpnMatchNode(new SpnLongLiteralNode(-5),
                    negativeBranch, positiveBranch, zeroBranch);
            assertEquals("negative", executeWithFrame(desc, matchNeg));

            // Need fresh node trees for new execution (Truffle nodes can't be reused)
            var positiveBranch2 = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{xSlot},
                    SpnLessThanNodeGen.create(
                            new SpnLongLiteralNode(0),
                            spn.node.local.SpnReadLocalVariableNodeGen.create(xSlot)),
                    new SpnStringLiteralNode("positive"));
            var zeroBranch2 = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("zero"));
            var matchPos = new SpnMatchNode(new SpnLongLiteralNode(5),
                    positiveBranch2, zeroBranch2);
            assertEquals("positive", executeWithFrame(desc, matchPos));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PRODUCT TYPE MATCHING
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class ProductPatternMatching {

        static final SpnTypeDescriptor COMPLEX = SpnTypeDescriptor.builder("Complex")
                .component("real").component("imag").build();

        @Test
        void matchOnProductType() {
            var fdBuilder = FrameDescriptor.newBuilder();
            int realSlot = fdBuilder.addSlot(FrameSlotKind.Object, "real", null);
            int imagSlot = fdBuilder.addSlot(FrameSlotKind.Object, "imag", null);
            var desc = fdBuilder.build();

            // match z { Complex(real, imag) -> real }
            var branch = new SpnMatchBranchNode(
                    new MatchPattern.Product(COMPLEX),
                    new int[]{realSlot, imagSlot},
                    spn.node.local.SpnReadLocalVariableNodeGen.create(realSlot));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnDoubleLiteralNode(-1.0));

            var construct = new SpnProductConstructNode(COMPLEX,
                    new SpnDoubleLiteralNode(3.0), new SpnDoubleLiteralNode(4.0));
            var match = new SpnMatchNode(construct, branch, fallback);

            assertEquals(3.0, executeWithFrame(desc, match));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // NON-EXHAUSTIVE MATCH
    // ════════════════════════════════════════════════════════════════════════

    @Test
    void nonExhaustiveMatchThrows() {
        // match 42 { "hello" -> 1 }  -- no branch matches 42
        var branch = new SpnMatchBranchNode(
                new MatchPattern.Literal("hello"), new int[]{},
                new SpnLongLiteralNode(1));
        var match = new SpnMatchNode(new SpnLongLiteralNode(42), branch);

        var ex = assertThrows(SpnException.class, () -> execute(match));
        assertTrue(ex.getMessage().contains("Non-exhaustive"));
    }

    // ── Helper: inline write + pass-through (avoids needing a block node in tests)
    static class InlineWriteRead extends SpnExpressionNode {
        @Child SpnExpressionNode inner;
        final int slot;

        InlineWriteRead(SpnExpressionNode inner, int slot) {
            this.inner = inner;
            this.slot = slot;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object value = inner.executeGeneric(frame);
            frame.setObject(slot, value);
            return value;
        }
    }
}
