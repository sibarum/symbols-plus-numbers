package spn.node;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import spn.language.SpnException;
import spn.node.expr.*;
import spn.node.match.MatchPattern;
import spn.node.match.SpnMatchBranchNode;
import spn.node.match.SpnMatchNode;
import spn.node.struct.*;
import spn.type.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypedStructAndTupleTest {

    private static Object execute(SpnExpressionNode node) {
        return new SpnRootNode(null, new FrameDescriptor(), node, "test")
                .getCallTarget().call();
    }

    private static Object executeWithFrame(FrameDescriptor desc, SpnExpressionNode node) {
        return new SpnRootNode(null, desc, node, "test")
                .getCallTarget().call();
    }

    // ════════════════════════════════════════════════════════════════════════
    // TYPED STRUCTS
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class TypedStructs {

        static final SpnStructDescriptor POINT = SpnStructDescriptor.builder("Point")
                .field("x", FieldType.DOUBLE)
                .field("y", FieldType.DOUBLE)
                .build();

        @Test
        void acceptsCorrectTypes() {
            var node = new SpnStructConstructNode(POINT,
                    new SpnDoubleLiteralNode(1.0),
                    new SpnDoubleLiteralNode(2.0));
            var result = (SpnStructValue) execute(node);
            assertEquals(1.0, result.get(0));
            assertEquals(2.0, result.get(1));
        }

        @Test
        void rejectsWrongType() {
            var node = new SpnStructConstructNode(POINT,
                    new SpnLongLiteralNode(1),       // Long, not Double!
                    new SpnDoubleLiteralNode(2.0));
            var ex = assertThrows(SpnException.class, () -> execute(node));
            assertTrue(ex.getMessage().contains("x"));
            assertTrue(ex.getMessage().contains("Double"));
        }

        @Test
        void untypedStructStillWorks() {
            // Backwards compat: untyped fields accept anything
            var untyped = new SpnStructDescriptor("Bag", "stuff");
            var node = new SpnStructConstructNode(untyped,
                    new SpnStringLiteralNode("anything"));
            var result = (SpnStructValue) execute(node);
            assertEquals("anything", result.get(0));
        }

        @Test
        void mixedTypedAndUntyped() {
            var labeled = SpnStructDescriptor.builder("Labeled")
                    .field("label", FieldType.STRING)
                    .field("value")  // untyped
                    .build();

            // String label + any value → OK
            var node = new SpnStructConstructNode(labeled,
                    new SpnStringLiteralNode("count"),
                    new SpnLongLiteralNode(42));
            var result = (SpnStructValue) execute(node);
            assertEquals("count", result.get(0));
            assertEquals(42L, result.get(1));
        }

        @Test
        void mixedRejectsWrongTypedField() {
            var labeled = SpnStructDescriptor.builder("Labeled")
                    .field("label", FieldType.STRING)
                    .field("value")
                    .build();

            // Long label → rejected
            var node = new SpnStructConstructNode(labeled,
                    new SpnLongLiteralNode(42),
                    new SpnStringLiteralNode("val"));
            var ex = assertThrows(SpnException.class, () -> execute(node));
            assertTrue(ex.getMessage().contains("label"));
        }

        @Test
        void structTypedField() {
            var inner = new SpnStructDescriptor("Inner", "x");
            var outer = SpnStructDescriptor.builder("Outer")
                    .field("child", FieldType.ofStruct(inner))
                    .build();

            var node = new SpnStructConstructNode(outer,
                    new SpnStructConstructNode(inner, new SpnLongLiteralNode(1)));
            var result = (SpnStructValue) execute(node);
            assertInstanceOf(SpnStructValue.class, result.get(0));
        }

        @Test
        void structTypedFieldRejectsWrong() {
            var inner = new SpnStructDescriptor("Inner", "x");
            var other = new SpnStructDescriptor("Other", "x");
            var outer = SpnStructDescriptor.builder("Outer")
                    .field("child", FieldType.ofStruct(inner))
                    .build();

            // Other instead of Inner → rejected (nominal typing)
            var node = new SpnStructConstructNode(outer,
                    new SpnStructConstructNode(other, new SpnLongLiteralNode(1)));
            assertThrows(SpnException.class, () -> execute(node));
        }

        @Test
        void hasTypedFieldsFlag() {
            assertTrue(POINT.hasTypedFields());
            assertFalse(new SpnStructDescriptor("Bag", "x").hasTypedFields());
        }

        @Test
        void toStringShowsTypes() {
            String s = POINT.toString();
            assertTrue(s.contains("Point"));
            assertTrue(s.contains("x: Double"));
            assertTrue(s.contains("y: Double"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // GENERIC STRUCTS
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class GenericStructs {

        static final SpnStructDescriptor PAIR = SpnStructDescriptor.builder("Pair")
                .typeParam("T").typeParam("U")
                .field("first", FieldType.generic("T"))
                .field("second", FieldType.generic("U"))
                .build();

        @Test
        void unresolvedGenericAcceptsAnything() {
            // Unresolved Pair<T,U> acts like untyped
            var node = new SpnStructConstructNode(PAIR,
                    new SpnLongLiteralNode(42),
                    new SpnStringLiteralNode("hello"));
            var result = (SpnStructValue) execute(node);
            assertEquals(42L, result.get(0));
            assertEquals("hello", result.get(1));
        }

        @Test
        void resolvedGenericEnforcesTypes() {
            var resolved = PAIR.resolve(Map.of(
                    "T", FieldType.LONG,
                    "U", FieldType.STRING));

            // Long + String → OK
            var ok = new SpnStructConstructNode(resolved,
                    new SpnLongLiteralNode(42),
                    new SpnStringLiteralNode("hello"));
            execute(ok); // should not throw

            // String + String → field 'first' rejects
            var bad = new SpnStructConstructNode(resolved,
                    new SpnStringLiteralNode("wrong"),
                    new SpnStringLiteralNode("hello"));
            assertThrows(SpnException.class, () -> execute(bad));
        }

        @Test
        void isGenericFlag() {
            assertTrue(PAIR.isGeneric());
            assertFalse(new SpnStructDescriptor("Plain", "x").isGeneric());
        }

        @Test
        void resolvedIsNotGeneric() {
            var resolved = PAIR.resolve(Map.of("T", FieldType.LONG, "U", FieldType.STRING));
            assertFalse(resolved.isGeneric());
        }

        @Test
        void toStringShowsTypeParams() {
            assertTrue(PAIR.toString().contains("Pair<T, U>"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // TUPLES
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class Tuples {

        @Test
        void untypedTuple() {
            var desc = SpnTupleDescriptor.untyped(3);
            var node = new SpnTupleConstructNode(desc,
                    new SpnLongLiteralNode(1),
                    new SpnStringLiteralNode("two"),
                    new SpnDoubleLiteralNode(3.0));
            var result = (SpnTupleValue) execute(node);
            assertEquals(1L, result.get(0));
            assertEquals("two", result.get(1));
            assertEquals(3.0, result.get(2));
            assertEquals(3, result.arity());
        }

        @Test
        void fullyTypedTuple() {
            var desc = new SpnTupleDescriptor(FieldType.LONG, FieldType.STRING);
            var node = new SpnTupleConstructNode(desc,
                    new SpnLongLiteralNode(42),
                    new SpnStringLiteralNode("hello"));
            var result = (SpnTupleValue) execute(node);
            assertEquals(42L, result.get(0));
            assertEquals("hello", result.get(1));
        }

        @Test
        void mixedSpecificityTuple() {
            // (Long, _, Double) → position 0 typed, position 1 untyped, position 2 typed
            var desc = new SpnTupleDescriptor(FieldType.LONG, FieldType.UNTYPED, FieldType.DOUBLE);
            var node = new SpnTupleConstructNode(desc,
                    new SpnLongLiteralNode(42),
                    new SpnBooleanLiteralNode(true),  // anything goes here
                    new SpnDoubleLiteralNode(3.14));
            var result = (SpnTupleValue) execute(node);
            assertEquals(42L, result.get(0));
            assertEquals(true, result.get(1));
            assertEquals(3.14, result.get(2));
        }

        @Test
        void typedPositionRejects() {
            var desc = new SpnTupleDescriptor(FieldType.LONG, FieldType.STRING);
            var node = new SpnTupleConstructNode(desc,
                    new SpnStringLiteralNode("wrong"),   // Long expected!
                    new SpnStringLiteralNode("hello"));
            var ex = assertThrows(SpnException.class, () -> execute(node));
            assertTrue(ex.getMessage().contains("position 0"));
            assertTrue(ex.getMessage().contains("Long"));
        }

        @Test
        void elementAccess() {
            var desc = SpnTupleDescriptor.untyped(2);
            var construct = new SpnTupleConstructNode(desc,
                    new SpnLongLiteralNode(10),
                    new SpnDoubleLiteralNode(20.0));
            var access0 = SpnTupleElementAccessNodeGen.create(construct, 0);
            assertEquals(10L, execute(access0));
        }

        @Test
        void tupleToString() {
            var desc = SpnTupleDescriptor.untyped(2);
            var v = new SpnTupleValue(desc, 42L, "hello");
            assertEquals("(42, hello)", v.toString());
        }

        @Test
        void tupleEquality() {
            var desc = SpnTupleDescriptor.untyped(2);
            var a = new SpnTupleValue(desc, 1L, 2L);
            var b = new SpnTupleValue(desc, 1L, 2L);
            var c = new SpnTupleValue(desc, 1L, 3L);
            assertEquals(a, b);
            assertNotEquals(a, c);
        }

        @Test
        void descriptorStructuralEquality() {
            var a = new SpnTupleDescriptor(FieldType.LONG, FieldType.STRING);
            var b = new SpnTupleDescriptor(FieldType.LONG, FieldType.STRING);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        void descriptorDescribe() {
            var desc = new SpnTupleDescriptor(FieldType.LONG, FieldType.UNTYPED, FieldType.DOUBLE);
            assertEquals("(Long, _, Double)", desc.describe());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // TUPLE PATTERN MATCHING
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class TuplePatternMatching {

        @Test
        void matchTupleByStructure() {
            var fdBuilder = FrameDescriptor.newBuilder();
            int aSlot = fdBuilder.addSlot(FrameSlotKind.Object, "a", null);
            int bSlot = fdBuilder.addSlot(FrameSlotKind.Object, "b", null);
            var desc = fdBuilder.build();

            var pairDesc = new SpnTupleDescriptor(FieldType.LONG, FieldType.STRING);

            // match t { (Long, String)(a, b) -> a }
            var branch = new SpnMatchBranchNode(
                    new MatchPattern.Tuple(pairDesc),
                    new int[]{aSlot, bSlot},
                    spn.node.local.SpnReadLocalVariableNodeGen.create(aSlot));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnLongLiteralNode(-1));

            var tupleNode = new SpnTupleConstructNode(pairDesc,
                    new SpnLongLiteralNode(42),
                    new SpnStringLiteralNode("hello"));
            var match = new SpnMatchNode(tupleNode, branch, fallback);

            assertEquals(42L, executeWithFrame(desc, match));
        }

        @Test
        void tuplePatternRejectsWrongArity() {
            var pair = new SpnTupleDescriptor(FieldType.LONG, FieldType.STRING);
            var triple = SpnTupleDescriptor.untyped(3);

            // Pattern expects (Long, String) but value is a 3-tuple
            var branch = new SpnMatchBranchNode(
                    new MatchPattern.Tuple(pair), new int[]{},
                    new SpnLongLiteralNode(1));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnLongLiteralNode(0));

            var tripleNode = new SpnTupleConstructNode(triple,
                    new SpnLongLiteralNode(1),
                    new SpnLongLiteralNode(2),
                    new SpnLongLiteralNode(3));
            var match = new SpnMatchNode(tripleNode, branch, fallback);

            assertEquals(0L, execute(match));  // falls through to wildcard
        }

        @Test
        void tuplePatternRejectsWrongElementType() {
            var intPair = new SpnTupleDescriptor(FieldType.LONG, FieldType.LONG);
            var anyPair = SpnTupleDescriptor.untyped(2);

            // Pattern expects (Long, Long) but value has (Long, String)
            var branch = new SpnMatchBranchNode(
                    new MatchPattern.Tuple(intPair), new int[]{},
                    new SpnLongLiteralNode(1));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnLongLiteralNode(0));

            var tupleNode = new SpnTupleConstructNode(anyPair,
                    new SpnLongLiteralNode(42),
                    new SpnStringLiteralNode("not a long"));
            var match = new SpnMatchNode(tupleNode, branch, fallback);

            assertEquals(0L, execute(match));  // falls through to wildcard
        }

        @Test
        void untypedTuplePatternMatchesAnyTupleOfSameArity() {
            var anyPair = SpnTupleDescriptor.untyped(2);
            var typedPair = new SpnTupleDescriptor(FieldType.LONG, FieldType.STRING);

            // Untyped (_, _) pattern matches any 2-tuple
            var branch = new SpnMatchBranchNode(
                    new MatchPattern.Tuple(anyPair), new int[]{},
                    new SpnLongLiteralNode(1));

            var tupleNode = new SpnTupleConstructNode(typedPair,
                    new SpnLongLiteralNode(42),
                    new SpnStringLiteralNode("hello"));
            var match = new SpnMatchNode(tupleNode, branch);

            assertEquals(1L, execute(match));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // TYPE PATTERN MATCHING (MatchPattern.OfType)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class TypePatternMatching {

        @Test
        void matchOnPrimitiveType() {
            // match x { Long(n) -> "long", Double(n) -> "double", _ -> "other" }
            var fdBuilder = FrameDescriptor.newBuilder();
            int nSlot = fdBuilder.addSlot(FrameSlotKind.Object, "n", null);
            var desc = fdBuilder.build();

            var longBranch = new SpnMatchBranchNode(
                    new MatchPattern.OfType(FieldType.LONG), new int[]{nSlot},
                    new SpnStringLiteralNode("long"));
            var doubleBranch = new SpnMatchBranchNode(
                    new MatchPattern.OfType(FieldType.DOUBLE), new int[]{nSlot},
                    new SpnStringLiteralNode("double"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("other"));

            assertEquals("long", executeWithFrame(desc,
                    new SpnMatchNode(new SpnLongLiteralNode(42), longBranch, doubleBranch, fallback)));
            assertEquals("double", executeWithFrame(desc,
                    new SpnMatchNode(new SpnDoubleLiteralNode(3.14),
                            // need fresh nodes for second execution
                            new SpnMatchBranchNode(new MatchPattern.OfType(FieldType.LONG), new int[]{nSlot}, new SpnStringLiteralNode("long")),
                            new SpnMatchBranchNode(new MatchPattern.OfType(FieldType.DOUBLE), new int[]{nSlot}, new SpnStringLiteralNode("double")),
                            new SpnMatchBranchNode(new MatchPattern.Wildcard(), new int[]{}, new SpnStringLiteralNode("other")))));
            assertEquals("other", executeWithFrame(desc,
                    new SpnMatchNode(new SpnStringLiteralNode("hello"),
                            new SpnMatchBranchNode(new MatchPattern.OfType(FieldType.LONG), new int[]{nSlot}, new SpnStringLiteralNode("long")),
                            new SpnMatchBranchNode(new MatchPattern.OfType(FieldType.DOUBLE), new int[]{nSlot}, new SpnStringLiteralNode("double")),
                            new SpnMatchBranchNode(new MatchPattern.Wildcard(), new int[]{}, new SpnStringLiteralNode("other")))));
        }

        @Test
        void matchOnConstrainedType() {
            var natural = new SpnTypeDescriptor("Natural", new Constraint.GreaterThanOrEqual(0));
            var natVal = new SpnConstrainedValue(42L, natural);

            // MatchPattern.OfType with constrained type
            var natBranch = new SpnMatchBranchNode(
                    new MatchPattern.OfType(FieldType.ofConstrainedType(natural)),
                    new int[]{},
                    new SpnStringLiteralNode("natural"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("other"));

            // Value node that produces the constrained value directly
            var valNode = new SpnExpressionNode() {
                @Override public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                    return natVal;
                }
            };
            assertEquals("natural", execute(new SpnMatchNode(valNode, natBranch, fallback)));

            // Raw long should NOT match the constrained type pattern
            var rawBranch = new SpnMatchBranchNode(
                    new MatchPattern.OfType(FieldType.ofConstrainedType(natural)), new int[]{},
                    new SpnStringLiteralNode("natural"));
            var rawFallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("other"));
            assertEquals("other", execute(new SpnMatchNode(new SpnLongLiteralNode(42), rawBranch, rawFallback)));
        }

        @Test
        void typePatternWithBinding() {
            // match x { Long(n) -> n + 1 }
            var fdBuilder = FrameDescriptor.newBuilder();
            int nSlot = fdBuilder.addSlot(FrameSlotKind.Object, "n", null);
            var desc = fdBuilder.build();

            var branch = new SpnMatchBranchNode(
                    new MatchPattern.OfType(FieldType.LONG),
                    new int[]{nSlot},
                    SpnAddNodeGen.create(
                            spn.node.local.SpnReadLocalVariableNodeGen.create(nSlot),
                            new SpnLongLiteralNode(1)));

            var match = new SpnMatchNode(new SpnLongLiteralNode(41), branch);
            assertEquals(42L, executeWithFrame(desc, match));
        }

        @Test
        void matchOnStringType() {
            var strBranch = new SpnMatchBranchNode(
                    new MatchPattern.OfType(FieldType.STRING), new int[]{},
                    new SpnLongLiteralNode(1));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnLongLiteralNode(0));

            assertEquals(1L, execute(new SpnMatchNode(new SpnStringLiteralNode("hi"), strBranch, fallback)));
            assertEquals(0L, execute(
                    new SpnMatchNode(new SpnLongLiteralNode(42),
                            new SpnMatchBranchNode(new MatchPattern.OfType(FieldType.STRING), new int[]{}, new SpnLongLiteralNode(1)),
                            new SpnMatchBranchNode(new MatchPattern.Wildcard(), new int[]{}, new SpnLongLiteralNode(0)))));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // FIELDTYPE ACCEPTS
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class FieldTypeAccepts {

        @Test
        void untypedAcceptsEverything() {
            assertTrue(FieldType.UNTYPED.accepts(42L));
            assertTrue(FieldType.UNTYPED.accepts("hello"));
            assertTrue(FieldType.UNTYPED.accepts(true));
        }

        @Test
        void longAcceptsLong() {
            assertTrue(FieldType.LONG.accepts(42L));
            assertFalse(FieldType.LONG.accepts(3.14));
            assertFalse(FieldType.LONG.accepts("hello"));
        }

        @Test
        void doubleAcceptsDouble() {
            assertTrue(FieldType.DOUBLE.accepts(3.14));
            assertFalse(FieldType.DOUBLE.accepts(42L));
        }

        @Test
        void booleanAcceptsBoolean() {
            assertTrue(FieldType.BOOLEAN.accepts(true));
            assertFalse(FieldType.BOOLEAN.accepts(42L));
        }

        @Test
        void stringAcceptsString() {
            assertTrue(FieldType.STRING.accepts("hello"));
            assertFalse(FieldType.STRING.accepts(42L));
        }

        @Test
        void genericParamAcceptsAnything() {
            var gp = FieldType.generic("T");
            assertTrue(gp.accepts(42L));
            assertTrue(gp.accepts("hello"));
        }

        @Test
        void ofStructNominal() {
            var desc = new SpnStructDescriptor("Foo", "x");
            var other = new SpnStructDescriptor("Foo", "x");
            var ft = FieldType.ofStruct(desc);

            assertTrue(ft.accepts(new SpnStructValue(desc, 1L)));
            assertFalse(ft.accepts(new SpnStructValue(other, 1L))); // different descriptor
            assertFalse(ft.accepts(42L));
        }

        @Test
        void ofTupleStructural() {
            var innerDesc = new SpnTupleDescriptor(FieldType.LONG, FieldType.STRING);
            var ft = FieldType.ofTuple(innerDesc);

            assertTrue(ft.accepts(new SpnTupleValue(innerDesc, 42L, "hi")));
            assertFalse(ft.accepts(new SpnTupleValue(innerDesc, "wrong", "hi")));
            assertFalse(ft.accepts(42L));
        }
    }
}
