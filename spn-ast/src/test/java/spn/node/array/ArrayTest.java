package spn.node.array;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.SpnRootNode;
import spn.node.expr.*;
import spn.node.match.MatchPattern;
import spn.node.match.SpnMatchBranchNode;
import spn.node.match.SpnMatchNode;
import spn.node.struct.SpnStructConstructNode;
import spn.type.*;

import static org.junit.jupiter.api.Assertions.*;

class ArrayTest {

    private static Object execute(SpnExpressionNode node) {
        return new SpnRootNode(null, new FrameDescriptor(), node, "test")
                .getCallTarget().call();
    }

    private static Object executeWithFrame(FrameDescriptor desc, SpnExpressionNode node) {
        return new SpnRootNode(null, desc, node, "test")
                .getCallTarget().call();
    }

    // ════════════════════════════════════════════════════════════════════════
    // CONSTRUCTION
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class Construction {

        @Test
        void typedArrayOfLongs() {
            var node = new SpnArrayLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(1),
                    new SpnLongLiteralNode(2),
                    new SpnLongLiteralNode(3));
            var arr = (SpnArrayValue) execute(node);
            assertEquals(3, arr.length());
            assertEquals(1L, arr.get(0));
            assertEquals(2L, arr.get(1));
            assertEquals(3L, arr.get(2));
        }

        @Test
        void untypedArray() {
            var node = new SpnArrayLiteralNode(FieldType.UNTYPED,
                    new SpnLongLiteralNode(1),
                    new SpnStringLiteralNode("hello"),
                    new SpnBooleanLiteralNode(true));
            var arr = (SpnArrayValue) execute(node);
            assertEquals(3, arr.length());
            assertEquals(1L, arr.get(0));
            assertEquals("hello", arr.get(1));
            assertEquals(true, arr.get(2));
        }

        @Test
        void emptyArray() {
            var node = new SpnArrayLiteralNode(FieldType.LONG);
            var arr = (SpnArrayValue) execute(node);
            assertEquals(0, arr.length());
            assertTrue(arr.isEmpty());
        }

        @Test
        void rejectsWrongElementType() {
            var node = new SpnArrayLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(1),
                    new SpnStringLiteralNode("oops"),  // not a Long!
                    new SpnLongLiteralNode(3));
            var ex = assertThrows(SpnException.class, () -> execute(node));
            assertTrue(ex.getMessage().contains("index 1"));
            assertTrue(ex.getMessage().contains("Long"));
        }

        @Test
        void arrayToString() {
            var arr = new SpnArrayValue(FieldType.LONG, 1L, 2L, 3L);
            assertEquals("[1, 2, 3]", arr.toString());
        }

        @Test
        void emptyArrayToString() {
            assertEquals("[]", new SpnArrayValue(FieldType.LONG).toString());
        }

        @Test
        void arrayEquality() {
            var a = new SpnArrayValue(FieldType.LONG, 1L, 2L, 3L);
            var b = new SpnArrayValue(FieldType.LONG, 1L, 2L, 3L);
            var c = new SpnArrayValue(FieldType.LONG, 1L, 2L, 4L);
            assertEquals(a, b);
            assertNotEquals(a, c);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // ACCESS AND LENGTH
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class AccessAndLength {

        @Test
        void indexAccess() {
            var arr = new SpnArrayLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(10),
                    new SpnLongLiteralNode(20),
                    new SpnLongLiteralNode(30));
            var access = SpnArrayAccessNodeGen.create(arr, new SpnLongLiteralNode(1));
            assertEquals(20L, execute(access));
        }

        @Test
        void indexOutOfBounds() {
            var arr = new SpnArrayLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(10));
            var access = SpnArrayAccessNodeGen.create(arr, new SpnLongLiteralNode(5));
            var ex = assertThrows(SpnException.class, () -> execute(access));
            assertTrue(ex.getMessage().contains("out of bounds"));
        }

        @Test
        void negativeIndex() {
            var arr = new SpnArrayLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(10));
            var access = SpnArrayAccessNodeGen.create(arr, new SpnLongLiteralNode(-1));
            assertThrows(SpnException.class, () -> execute(access));
        }

        @Test
        void length() {
            var arr = new SpnArrayLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(10),
                    new SpnLongLiteralNode(20),
                    new SpnLongLiteralNode(30));
            var len = SpnArrayLengthNodeGen.create(arr);
            assertEquals(3L, execute(len));
        }

        @Test
        void emptyLength() {
            var arr = new SpnArrayLiteralNode(FieldType.LONG);
            var len = SpnArrayLengthNodeGen.create(arr);
            assertEquals(0L, execute(len));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // HEAD / TAIL / SLICE
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class HeadTailSlice {

        @Test
        void head() {
            var arr = new SpnArrayValue(FieldType.LONG, 10L, 20L, 30L);
            assertEquals(10L, arr.head());
        }

        @Test
        void tail() {
            var arr = new SpnArrayValue(FieldType.LONG, 10L, 20L, 30L);
            var t = arr.tail();
            assertEquals(2, t.length());
            assertEquals(20L, t.get(0));
            assertEquals(30L, t.get(1));
        }

        @Test
        void tailOfSingleElement() {
            var arr = new SpnArrayValue(FieldType.LONG, 42L);
            var t = arr.tail();
            assertTrue(t.isEmpty());
        }

        @Test
        void slice() {
            var arr = new SpnArrayValue(FieldType.LONG, 10L, 20L, 30L, 40L, 50L);
            var s = arr.slice(1, 4);
            assertEquals(3, s.length());
            assertEquals(20L, s.get(0));
            assertEquals(30L, s.get(1));
            assertEquals(40L, s.get(2));
        }

        @Test
        void tailPreservesElementType() {
            var arr = new SpnArrayValue(FieldType.LONG, 10L, 20L);
            assertEquals(FieldType.LONG, arr.tail().getElementType());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PATTERN MATCHING
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class PatternMatching {

        @Test
        void matchEmptyArray() {
            var emptyBranch = new SpnMatchBranchNode(
                    new MatchPattern.EmptyArray(), new int[]{},
                    new SpnStringLiteralNode("empty"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("non-empty"));

            // Empty array
            var emptyArr = new SpnArrayLiteralNode(FieldType.LONG);
            assertEquals("empty", execute(new SpnMatchNode(emptyArr, emptyBranch, fallback)));
        }

        @Test
        void emptyDoesNotMatchNonEmpty() {
            var emptyBranch = new SpnMatchBranchNode(
                    new MatchPattern.EmptyArray(), new int[]{},
                    new SpnStringLiteralNode("empty"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("non-empty"));

            var nonEmpty = new SpnArrayLiteralNode(FieldType.LONG, new SpnLongLiteralNode(1));
            assertEquals("non-empty", execute(new SpnMatchNode(nonEmpty, emptyBranch, fallback)));
        }

        @Test
        void headTailDecomposition() {
            var fdBuilder = FrameDescriptor.newBuilder();
            int hSlot = fdBuilder.addSlot(FrameSlotKind.Object, "h", null);
            int tSlot = fdBuilder.addSlot(FrameSlotKind.Object, "t", null);
            var desc = fdBuilder.build();

            // match arr { [h | t] -> h }
            var htBranch = new SpnMatchBranchNode(
                    new MatchPattern.ArrayHeadTail(), new int[]{hSlot, tSlot},
                    spn.node.local.SpnReadLocalVariableNodeGen.create(hSlot));
            var emptyBranch = new SpnMatchBranchNode(
                    new MatchPattern.EmptyArray(), new int[]{},
                    new SpnLongLiteralNode(-1));

            var arr = new SpnArrayLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(10),
                    new SpnLongLiteralNode(20),
                    new SpnLongLiteralNode(30));

            assertEquals(10L, executeWithFrame(desc, new SpnMatchNode(arr, htBranch, emptyBranch)));
        }

        @Test
        void headTailBindsTail() {
            var fdBuilder = FrameDescriptor.newBuilder();
            int hSlot = fdBuilder.addSlot(FrameSlotKind.Object, "h", null);
            int tSlot = fdBuilder.addSlot(FrameSlotKind.Object, "t", null);
            var desc = fdBuilder.build();

            // match arr { [h | t] -> length(t) }
            var tailLenBody = SpnArrayLengthNodeGen.create(
                    spn.node.local.SpnReadLocalVariableNodeGen.create(tSlot));
            var htBranch = new SpnMatchBranchNode(
                    new MatchPattern.ArrayHeadTail(), new int[]{hSlot, tSlot},
                    tailLenBody);

            var arr = new SpnArrayLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(10),
                    new SpnLongLiteralNode(20),
                    new SpnLongLiteralNode(30));

            assertEquals(2L, executeWithFrame(desc, new SpnMatchNode(arr, htBranch)));
        }

        @Test
        void exactLengthDestructuring() {
            var fdBuilder = FrameDescriptor.newBuilder();
            int aSlot = fdBuilder.addSlot(FrameSlotKind.Object, "a", null);
            int bSlot = fdBuilder.addSlot(FrameSlotKind.Object, "b", null);
            var desc = fdBuilder.build();

            // match arr { [a, b] -> a + b }
            var pairBranch = new SpnMatchBranchNode(
                    new MatchPattern.ArrayExactLength(2), new int[]{aSlot, bSlot},
                    SpnAddNodeGen.create(
                            spn.node.local.SpnReadLocalVariableNodeGen.create(aSlot),
                            spn.node.local.SpnReadLocalVariableNodeGen.create(bSlot)));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnLongLiteralNode(-1));

            var arr = new SpnArrayLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(3),
                    new SpnLongLiteralNode(4));

            assertEquals(7L, executeWithFrame(desc, new SpnMatchNode(arr, pairBranch, fallback)));
        }

        @Test
        void exactLengthRejectsWrongLength() {
            var pairBranch = new SpnMatchBranchNode(
                    new MatchPattern.ArrayExactLength(2), new int[]{},
                    new SpnStringLiteralNode("pair"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("other"));

            // 3-element array doesn't match [_, _]
            var arr = new SpnArrayLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(1), new SpnLongLiteralNode(2), new SpnLongLiteralNode(3));
            assertEquals("other", execute(new SpnMatchNode(arr, pairBranch, fallback)));
        }

        @Test
        void combinedEmptyAndHeadTail() {
            // Classic functional: match arr { [] -> 0, [h|t] -> h }
            var fdBuilder = FrameDescriptor.newBuilder();
            int hSlot = fdBuilder.addSlot(FrameSlotKind.Object, "h", null);
            int tSlot = fdBuilder.addSlot(FrameSlotKind.Object, "t", null);
            var desc = fdBuilder.build();

            var emptyBranch = new SpnMatchBranchNode(
                    new MatchPattern.EmptyArray(), new int[]{},
                    new SpnLongLiteralNode(0));
            var htBranch = new SpnMatchBranchNode(
                    new MatchPattern.ArrayHeadTail(), new int[]{hSlot, tSlot},
                    spn.node.local.SpnReadLocalVariableNodeGen.create(hSlot));

            // Non-empty → head
            var arr = new SpnArrayLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(42), new SpnLongLiteralNode(99));
            assertEquals(42L, executeWithFrame(desc,
                    new SpnMatchNode(arr, emptyBranch, htBranch)));

            // Empty → 0
            var emptyArr = new SpnArrayLiteralNode(FieldType.LONG);
            var emptyBranch2 = new SpnMatchBranchNode(
                    new MatchPattern.EmptyArray(), new int[]{},
                    new SpnLongLiteralNode(0));
            var htBranch2 = new SpnMatchBranchNode(
                    new MatchPattern.ArrayHeadTail(), new int[]{hSlot, tSlot},
                    spn.node.local.SpnReadLocalVariableNodeGen.create(hSlot));
            assertEquals(0L, executeWithFrame(desc,
                    new SpnMatchNode(emptyArr, emptyBranch2, htBranch2)));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // FIELDTYPE
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class ArrayFieldType {

        @Test
        void ofArrayAcceptsMatchingType() {
            var ft = FieldType.ofArray(FieldType.LONG);
            assertTrue(ft.accepts(new SpnArrayValue(FieldType.LONG, 1L, 2L)));
            assertFalse(ft.accepts(new SpnArrayValue(FieldType.DOUBLE, 1.0)));
            assertFalse(ft.accepts(42L));
        }

        @Test
        void untypedArrayAcceptsAny() {
            var ft = FieldType.ofArray(FieldType.UNTYPED);
            assertTrue(ft.accepts(new SpnArrayValue(FieldType.LONG, 1L)));
            assertTrue(ft.accepts(new SpnArrayValue(FieldType.STRING, "hi")));
            assertTrue(ft.accepts(new SpnArrayValue(FieldType.UNTYPED, 1L, "hi")));
        }

        @Test
        void describe() {
            assertEquals("Array<Long>", FieldType.ofArray(FieldType.LONG).describe());
            assertEquals("Array", FieldType.ofArray(FieldType.UNTYPED).describe());
        }

        @Test
        void asStructField() {
            var desc = SpnStructDescriptor.builder("Container")
                    .field("items", FieldType.ofArray(FieldType.LONG))
                    .build();

            var arrNode = new SpnArrayLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(1), new SpnLongLiteralNode(2));
            var node = new SpnStructConstructNode(desc, arrNode);
            var result = (SpnStructValue) execute(node);
            assertInstanceOf(SpnArrayValue.class, result.get(0));
        }
    }
}
