package spn.node.set;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
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

class SetTest {

    static final SpnSymbolTable TABLE = new SpnSymbolTable();
    static final SpnSymbol RED = TABLE.intern("red");
    static final SpnSymbol GREEN = TABLE.intern("green");
    static final SpnSymbol BLUE = TABLE.intern("blue");

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
        void typedSetOfLongs() {
            var node = new SpnSetLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(1),
                    new SpnLongLiteralNode(2),
                    new SpnLongLiteralNode(3));
            var set = (SpnSetValue) execute(node);
            assertEquals(3, set.size());
            assertTrue(set.contains(1L));
            assertTrue(set.contains(2L));
            assertTrue(set.contains(3L));
        }

        @Test
        void deduplicates() {
            var node = new SpnSetLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(1),
                    new SpnLongLiteralNode(2),
                    new SpnLongLiteralNode(1));
            var set = (SpnSetValue) execute(node);
            assertEquals(2, set.size());
        }

        @Test
        void emptySet() {
            var node = new SpnSetLiteralNode(FieldType.LONG);
            var set = (SpnSetValue) execute(node);
            assertEquals(0, set.size());
            assertTrue(set.isEmpty());
        }

        @Test
        void untypedSet() {
            var node = new SpnSetLiteralNode(FieldType.UNTYPED,
                    new SpnLongLiteralNode(1),
                    new SpnStringLiteralNode("hello"));
            var set = (SpnSetValue) execute(node);
            assertEquals(2, set.size());
            assertTrue(set.contains(1L));
            assertTrue(set.contains("hello"));
        }

        @Test
        void rejectsWrongElementType() {
            var node = new SpnSetLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(1),
                    new SpnStringLiteralNode("oops"));
            var ex = assertThrows(SpnException.class, () -> execute(node));
            assertTrue(ex.getMessage().contains("Long"));
        }

        @Test
        void symbolSet() {
            var node = new SpnSetLiteralNode(FieldType.SYMBOL,
                    new SpnSymbolLiteralNode(RED),
                    new SpnSymbolLiteralNode(GREEN),
                    new SpnSymbolLiteralNode(BLUE));
            var set = (SpnSetValue) execute(node);
            assertEquals(3, set.size());
            assertTrue(set.contains(RED));
        }

        @Test
        void setToString() {
            var set = SpnSetValue.of(FieldType.LONG, 1L, 2L, 3L);
            assertEquals("{1, 2, 3}", set.toString());
        }

        @Test
        void setEquality() {
            var a = SpnSetValue.of(FieldType.LONG, 1L, 2L);
            var b = SpnSetValue.of(FieldType.LONG, 1L, 2L);
            var c = SpnSetValue.of(FieldType.LONG, 1L, 3L);
            assertEquals(a, b);
            assertNotEquals(a, c);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // CONTAINS AND SIZE
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class ContainsAndSize {

        @Test
        void containsTrue() {
            var set = new SpnSetLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(10), new SpnLongLiteralNode(20));
            var contains = SpnSetContainsNodeGen.create(set, new SpnLongLiteralNode(10));
            assertEquals(true, execute(contains));
        }

        @Test
        void containsFalse() {
            var set = new SpnSetLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(10), new SpnLongLiteralNode(20));
            var contains = SpnSetContainsNodeGen.create(set, new SpnLongLiteralNode(99));
            assertEquals(false, execute(contains));
        }

        @Test
        void size() {
            var set = new SpnSetLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(1), new SpnLongLiteralNode(2), new SpnLongLiteralNode(3));
            var size = SpnSetSizeNodeGen.create(set);
            assertEquals(3L, execute(size));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SET OPERATIONS (on SpnSetValue directly)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class SetOperations {

        final SpnSetValue a = SpnSetValue.of(FieldType.LONG, 1L, 2L, 3L);
        final SpnSetValue b = SpnSetValue.of(FieldType.LONG, 2L, 3L, 4L);

        @Test
        void union() {
            var u = a.union(b);
            assertEquals(4, u.size());
            assertTrue(u.contains(1L));
            assertTrue(u.contains(4L));
        }

        @Test
        void intersection() {
            var i = a.intersection(b);
            assertEquals(2, i.size());
            assertTrue(i.contains(2L));
            assertTrue(i.contains(3L));
            assertFalse(i.contains(1L));
        }

        @Test
        void difference() {
            var d = a.difference(b);
            assertEquals(1, d.size());
            assertTrue(d.contains(1L));
            assertFalse(d.contains(2L));
        }

        @Test
        void add() {
            var added = a.add(4L);
            assertEquals(4, added.size());
            assertTrue(added.contains(4L));
            assertEquals(3, a.size()); // original unchanged
        }

        @Test
        void remove() {
            var removed = a.remove(2L);
            assertEquals(2, removed.size());
            assertFalse(removed.contains(2L));
            assertEquals(3, a.size()); // original unchanged
        }

        @Test
        void isSubsetOf() {
            var subset = SpnSetValue.of(FieldType.LONG, 1L, 2L);
            assertTrue(subset.isSubsetOf(a));
            assertFalse(a.isSubsetOf(subset));
        }

        @Test
        void emptySetIsSubsetOfEverything() {
            assertTrue(SpnSetValue.empty(FieldType.LONG).isSubsetOf(a));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SET BINARY OP NODES
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class SetOpNodes {

        @Test
        void unionNode() {
            var a = new SpnSetLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(1), new SpnLongLiteralNode(2));
            var b = new SpnSetLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(2), new SpnLongLiteralNode(3));
            var union = SpnSetBinaryOpNodeGen.create(a, b, SpnSetBinaryOpNode.SetOp.UNION);
            var result = (SpnSetValue) execute(union);
            assertEquals(3, result.size());
        }

        @Test
        void intersectionNode() {
            var a = new SpnSetLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(1), new SpnLongLiteralNode(2));
            var b = new SpnSetLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(2), new SpnLongLiteralNode(3));
            var inter = SpnSetBinaryOpNodeGen.create(a, b, SpnSetBinaryOpNode.SetOp.INTERSECTION);
            var result = (SpnSetValue) execute(inter);
            assertEquals(1, result.size());
            assertTrue(result.contains(2L));
        }

        @Test
        void differenceNode() {
            var a = new SpnSetLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(1), new SpnLongLiteralNode(2));
            var b = new SpnSetLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(2), new SpnLongLiteralNode(3));
            var diff = SpnSetBinaryOpNodeGen.create(a, b, SpnSetBinaryOpNode.SetOp.DIFFERENCE);
            var result = (SpnSetValue) execute(diff);
            assertEquals(1, result.size());
            assertTrue(result.contains(1L));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PATTERN MATCHING
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class PatternMatching {

        @Test
        void matchEmptySet() {
            var emptyBranch = new SpnMatchBranchNode(
                    new MatchPattern.EmptySet(), new int[]{},
                    new SpnStringLiteralNode("empty"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("non-empty"));

            var emptySet = new SpnSetLiteralNode(FieldType.LONG);
            assertEquals("empty", execute(new SpnMatchNode(emptySet, emptyBranch, fallback)));
        }

        @Test
        void emptySetDoesNotMatchNonEmpty() {
            var emptyBranch = new SpnMatchBranchNode(
                    new MatchPattern.EmptySet(), new int[]{},
                    new SpnStringLiteralNode("empty"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("non-empty"));

            var set = new SpnSetLiteralNode(FieldType.LONG, new SpnLongLiteralNode(1));
            assertEquals("non-empty", execute(new SpnMatchNode(set, emptyBranch, fallback)));
        }

        @Test
        void setContainingMatch() {
            // match s { {contains :red, :blue} -> "has both" }
            var containsBranch = new SpnMatchBranchNode(
                    new MatchPattern.SetContaining(new Object[]{RED, BLUE}),
                    new int[]{},
                    new SpnStringLiteralNode("has both"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("missing"));

            var set = new SpnSetLiteralNode(FieldType.SYMBOL,
                    new SpnSymbolLiteralNode(RED),
                    new SpnSymbolLiteralNode(GREEN),
                    new SpnSymbolLiteralNode(BLUE));
            assertEquals("has both", execute(new SpnMatchNode(set, containsBranch, fallback)));
        }

        @Test
        void setContainingRejectsMissing() {
            var containsBranch = new SpnMatchBranchNode(
                    new MatchPattern.SetContaining(new Object[]{RED, BLUE}),
                    new int[]{},
                    new SpnStringLiteralNode("has both"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("missing"));

            // Set only has :red, not :blue
            var set = new SpnSetLiteralNode(FieldType.SYMBOL,
                    new SpnSymbolLiteralNode(RED),
                    new SpnSymbolLiteralNode(GREEN));
            assertEquals("missing", execute(new SpnMatchNode(set, containsBranch, fallback)));
        }

        @Test
        void setContainingWithBinding() {
            var fdBuilder = FrameDescriptor.newBuilder();
            int sSlot = fdBuilder.addSlot(FrameSlotKind.Object, "s", null);
            var desc = fdBuilder.build();

            // match s { {contains :red}(s) -> size(s) }
            var branch = new SpnMatchBranchNode(
                    new MatchPattern.SetContaining(new Object[]{RED}),
                    new int[]{sSlot},
                    SpnSetSizeNodeGen.create(
                            spn.node.local.SpnReadLocalVariableNodeGen.create(sSlot)));
            var set = new SpnSetLiteralNode(FieldType.SYMBOL,
                    new SpnSymbolLiteralNode(RED),
                    new SpnSymbolLiteralNode(GREEN));

            assertEquals(2L, executeWithFrame(desc, new SpnMatchNode(set, branch)));
        }

        @Test
        void setContainingLongValues() {
            // match s { {contains 1, 3} -> "has 1 and 3" }
            var branch = new SpnMatchBranchNode(
                    new MatchPattern.SetContaining(new Object[]{1L, 3L}),
                    new int[]{},
                    new SpnStringLiteralNode("found"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("not found"));

            var set = new SpnSetLiteralNode(FieldType.LONG,
                    new SpnLongLiteralNode(1), new SpnLongLiteralNode(2), new SpnLongLiteralNode(3));
            assertEquals("found", execute(new SpnMatchNode(set, branch, fallback)));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // FIELDTYPE
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class SetFieldType {

        @Test
        void ofSetAcceptsMatching() {
            var ft = FieldType.ofSet(FieldType.LONG);
            assertTrue(ft.accepts(SpnSetValue.of(FieldType.LONG, 1L)));
            assertFalse(ft.accepts(SpnSetValue.of(FieldType.STRING, "hi")));
            assertFalse(ft.accepts(42L));
        }

        @Test
        void untypedSetAcceptsAny() {
            var ft = FieldType.ofSet(FieldType.UNTYPED);
            assertTrue(ft.accepts(SpnSetValue.of(FieldType.LONG, 1L)));
            assertTrue(ft.accepts(SpnSetValue.of(FieldType.STRING, "hi")));
        }

        @Test
        void describe() {
            assertEquals("Set<Long>", FieldType.ofSet(FieldType.LONG).describe());
            assertEquals("Set", FieldType.ofSet(FieldType.UNTYPED).describe());
        }

        @Test
        void asStructField() {
            var desc = SpnStructDescriptor.builder("TaggedItem")
                    .field("tags", FieldType.ofSet(FieldType.SYMBOL))
                    .field("value")
                    .build();

            var set = new SpnSetLiteralNode(FieldType.SYMBOL,
                    new SpnSymbolLiteralNode(RED));
            var node = new SpnStructConstructNode(desc, set, new SpnLongLiteralNode(42));
            var result = (SpnStructValue) execute(node);
            assertInstanceOf(SpnSetValue.class, result.get(0));
        }
    }
}
