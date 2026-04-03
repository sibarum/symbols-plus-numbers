package spn.node.dict;

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

class DictionaryTest {

    static final SpnSymbolTable TABLE = new SpnSymbolTable();
    static final SpnSymbol NAME = TABLE.intern("name");
    static final SpnSymbol AGE = TABLE.intern("age");
    static final SpnSymbol EMAIL = TABLE.intern("email");
    static final SpnSymbol CITY = TABLE.intern("city");

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
        void untypedDictionary() {
            var node = new SpnDictionaryLiteralNode(FieldType.UNTYPED,
                    new SpnSymbol[]{NAME, AGE},
                    new SpnExpressionNode[]{
                            new SpnStringLiteralNode("Alice"),
                            new SpnLongLiteralNode(30)});
            var dict = (SpnDictionaryValue) execute(node);
            assertEquals(2, dict.size());
            assertEquals("Alice", dict.get(NAME));
            assertEquals(30L, dict.get(AGE));
        }

        @Test
        void typedDictionary() {
            var node = new SpnDictionaryLiteralNode(FieldType.STRING,
                    new SpnSymbol[]{NAME, CITY},
                    new SpnExpressionNode[]{
                            new SpnStringLiteralNode("Alice"),
                            new SpnStringLiteralNode("Seattle")});
            var dict = (SpnDictionaryValue) execute(node);
            assertEquals("Alice", dict.get(NAME));
            assertEquals("Seattle", dict.get(CITY));
        }

        @Test
        void typedRejectsWrongValueType() {
            var node = new SpnDictionaryLiteralNode(FieldType.STRING,
                    new SpnSymbol[]{NAME, AGE},
                    new SpnExpressionNode[]{
                            new SpnStringLiteralNode("Alice"),
                            new SpnLongLiteralNode(30)});  // Long, not String!
            var ex = assertThrows(SpnException.class, () -> execute(node));
            assertTrue(ex.getMessage().contains(":age"));
            assertTrue(ex.getMessage().contains("String"));
        }

        @Test
        void emptyDictionary() {
            var node = new SpnDictionaryLiteralNode(FieldType.UNTYPED,
                    new SpnSymbol[]{}, new SpnExpressionNode[]{});
            var dict = (SpnDictionaryValue) execute(node);
            assertTrue(dict.isEmpty());
            assertEquals(0, dict.size());
        }

        @Test
        void dictionaryToString() {
            var dict = SpnDictionaryValue.of(FieldType.UNTYPED, NAME, "Alice", AGE, 30L);
            String s = dict.toString();
            assertTrue(s.contains(":name: Alice"));
            assertTrue(s.contains(":age: 30"));
        }

        @Test
        void dictionaryEquality() {
            var a = SpnDictionaryValue.of(FieldType.UNTYPED, NAME, "Alice");
            var b = SpnDictionaryValue.of(FieldType.UNTYPED, NAME, "Alice");
            var c = SpnDictionaryValue.of(FieldType.UNTYPED, NAME, "Bob");
            assertEquals(a, b);
            assertNotEquals(a, c);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // GET, CONTAINSKEY, SIZE
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class QueryNodes {

        @Test
        void get() {
            var dict = new SpnDictionaryLiteralNode(FieldType.UNTYPED,
                    new SpnSymbol[]{NAME},
                    new SpnExpressionNode[]{new SpnStringLiteralNode("Alice")});
            var get = SpnDictionaryGetNodeGen.create(dict, new SpnSymbolLiteralNode(NAME));
            assertEquals("Alice", execute(get));
        }

        @Test
        void getMissingKeyThrows() {
            var dict = new SpnDictionaryLiteralNode(FieldType.UNTYPED,
                    new SpnSymbol[]{NAME},
                    new SpnExpressionNode[]{new SpnStringLiteralNode("Alice")});
            var get = SpnDictionaryGetNodeGen.create(dict, new SpnSymbolLiteralNode(AGE));
            var ex = assertThrows(SpnException.class, () -> execute(get));
            assertTrue(ex.getMessage().contains(":age"));
        }

        @Test
        void containsKey() {
            var dict = new SpnDictionaryLiteralNode(FieldType.UNTYPED,
                    new SpnSymbol[]{NAME, AGE},
                    new SpnExpressionNode[]{
                            new SpnStringLiteralNode("Alice"),
                            new SpnLongLiteralNode(30)});
            var hasName = SpnDictionaryContainsKeyNodeGen.create(dict, new SpnSymbolLiteralNode(NAME));
            assertEquals(true, execute(hasName));
        }

        @Test
        void doesNotContainKey() {
            var dict = new SpnDictionaryLiteralNode(FieldType.UNTYPED,
                    new SpnSymbol[]{NAME},
                    new SpnExpressionNode[]{new SpnStringLiteralNode("Alice")});
            var hasEmail = SpnDictionaryContainsKeyNodeGen.create(dict, new SpnSymbolLiteralNode(EMAIL));
            assertEquals(false, execute(hasEmail));
        }

        @Test
        void size() {
            var dict = new SpnDictionaryLiteralNode(FieldType.UNTYPED,
                    new SpnSymbol[]{NAME, AGE},
                    new SpnExpressionNode[]{
                            new SpnStringLiteralNode("Alice"),
                            new SpnLongLiteralNode(30)});
            var size = SpnDictionarySizeNodeGen.create(dict);
            assertEquals(2L, execute(size));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // FUNCTIONAL OPERATIONS (on SpnDictionaryValue directly)
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class FunctionalOps {

        final SpnDictionaryValue original = SpnDictionaryValue.of(FieldType.UNTYPED,
                NAME, "Alice", AGE, 30L);

        @Test
        void set() {
            var updated = original.set(AGE, 31L);
            assertEquals(31L, updated.get(AGE));
            assertEquals(30L, original.get(AGE)); // original unchanged
        }

        @Test
        void setNewKey() {
            var updated = original.set(EMAIL, "alice@example.com");
            assertEquals(3, updated.size());
            assertEquals("alice@example.com", updated.get(EMAIL));
            assertEquals(2, original.size()); // original unchanged
        }

        @Test
        void remove() {
            var removed = original.remove(AGE);
            assertEquals(1, removed.size());
            assertFalse(removed.containsKey(AGE));
            assertEquals(2, original.size()); // original unchanged
        }

        @Test
        void merge() {
            var other = SpnDictionaryValue.of(FieldType.UNTYPED,
                    AGE, 31L, CITY, "Seattle");
            var merged = original.merge(other);
            assertEquals(3, merged.size());
            assertEquals(31L, merged.get(AGE));         // other wins
            assertEquals("Alice", merged.get(NAME));     // kept from original
            assertEquals("Seattle", merged.get(CITY));   // from other
        }

        @Test
        void keys() {
            SpnSymbol[] keys = original.keys();
            assertEquals(2, keys.length);
        }

        @Test
        void values() {
            Object[] values = original.values();
            assertEquals(2, values.length);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // FUNCTIONAL SET NODE
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class SetNode {

        @Test
        void functionalUpdate() {
            var dict = new SpnDictionaryLiteralNode(FieldType.UNTYPED,
                    new SpnSymbol[]{NAME},
                    new SpnExpressionNode[]{new SpnStringLiteralNode("Alice")});
            var updated = SpnDictionarySetNodeGen.create(
                    dict,
                    new SpnSymbolLiteralNode(AGE),
                    new SpnLongLiteralNode(30));
            var result = (SpnDictionaryValue) execute(updated);
            assertEquals(2, result.size());
            assertEquals("Alice", result.get(NAME));
            assertEquals(30L, result.get(AGE));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PATTERN MATCHING
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class PatternMatching {

        @Test
        void matchEmptyDictionary() {
            var emptyBranch = new SpnMatchBranchNode(
                    new MatchPattern.EmptyDictionary(), new int[]{},
                    new SpnStringLiteralNode("empty"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("non-empty"));

            var emptyDict = new SpnDictionaryLiteralNode(FieldType.UNTYPED,
                    new SpnSymbol[]{}, new SpnExpressionNode[]{});
            assertEquals("empty", execute(new SpnMatchNode(emptyDict, emptyBranch, fallback)));
        }

        @Test
        void emptyDictDoesNotMatchNonEmpty() {
            var emptyBranch = new SpnMatchBranchNode(
                    new MatchPattern.EmptyDictionary(), new int[]{},
                    new SpnStringLiteralNode("empty"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("non-empty"));

            var dict = new SpnDictionaryLiteralNode(FieldType.UNTYPED,
                    new SpnSymbol[]{NAME},
                    new SpnExpressionNode[]{new SpnStringLiteralNode("Alice")});
            assertEquals("non-empty", execute(new SpnMatchNode(dict, emptyBranch, fallback)));
        }

        @Test
        void dictionaryKeysMatch() {
            var fdBuilder = FrameDescriptor.newBuilder();
            int nameSlot = fdBuilder.addSlot(FrameSlotKind.Object, "n", null);
            int ageSlot = fdBuilder.addSlot(FrameSlotKind.Object, "a", null);
            var desc = fdBuilder.build();

            // match d { {:name n, :age a} -> n }
            var keysBranch = new SpnMatchBranchNode(
                    new MatchPattern.DictionaryKeys(new SpnSymbol[]{NAME, AGE}),
                    new int[]{nameSlot, ageSlot},
                    spn.node.local.SpnReadLocalVariableNodeGen.create(nameSlot));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("no match"));

            var dict = new SpnDictionaryLiteralNode(FieldType.UNTYPED,
                    new SpnSymbol[]{NAME, AGE, CITY},
                    new SpnExpressionNode[]{
                            new SpnStringLiteralNode("Alice"),
                            new SpnLongLiteralNode(30),
                            new SpnStringLiteralNode("Seattle")});

            assertEquals("Alice", executeWithFrame(desc,
                    new SpnMatchNode(dict, keysBranch, fallback)));
        }

        @Test
        void dictionaryKeysBindsValues() {
            var fdBuilder = FrameDescriptor.newBuilder();
            int ageSlot = fdBuilder.addSlot(FrameSlotKind.Object, "a", null);
            var desc = fdBuilder.build();

            // match d { {:age a} -> a }
            var keysBranch = new SpnMatchBranchNode(
                    new MatchPattern.DictionaryKeys(new SpnSymbol[]{AGE}),
                    new int[]{ageSlot},
                    spn.node.local.SpnReadLocalVariableNodeGen.create(ageSlot));

            var dict = new SpnDictionaryLiteralNode(FieldType.UNTYPED,
                    new SpnSymbol[]{NAME, AGE},
                    new SpnExpressionNode[]{
                            new SpnStringLiteralNode("Alice"),
                            new SpnLongLiteralNode(30)});

            assertEquals(30L, executeWithFrame(desc, new SpnMatchNode(dict, keysBranch)));
        }

        @Test
        void dictionaryKeysRejectsMissingKey() {
            var keysBranch = new SpnMatchBranchNode(
                    new MatchPattern.DictionaryKeys(new SpnSymbol[]{NAME, EMAIL}),
                    new int[]{},
                    new SpnStringLiteralNode("has both"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("missing"));

            // Dict has :name but not :email
            var dict = new SpnDictionaryLiteralNode(FieldType.UNTYPED,
                    new SpnSymbol[]{NAME, AGE},
                    new SpnExpressionNode[]{
                            new SpnStringLiteralNode("Alice"),
                            new SpnLongLiteralNode(30)});

            assertEquals("missing", execute(new SpnMatchNode(dict, keysBranch, fallback)));
        }

        @Test
        void multipleDictPatterns() {
            // match d {
            //   {:name n, :age a} -> "full"
            //   {:name n}         -> "name only"
            //   _                 -> "other"
            // }
            var fdBuilder = FrameDescriptor.newBuilder();
            int nSlot = fdBuilder.addSlot(FrameSlotKind.Object, "n", null);
            int aSlot = fdBuilder.addSlot(FrameSlotKind.Object, "a", null);
            var desc = fdBuilder.build();

            var fullBranch = new SpnMatchBranchNode(
                    new MatchPattern.DictionaryKeys(new SpnSymbol[]{NAME, AGE}),
                    new int[]{nSlot, aSlot},
                    new SpnStringLiteralNode("full"));
            var nameBranch = new SpnMatchBranchNode(
                    new MatchPattern.DictionaryKeys(new SpnSymbol[]{NAME}),
                    new int[]{nSlot},
                    new SpnStringLiteralNode("name only"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("other"));

            // Has both name and age → "full"
            var fullDict = new SpnDictionaryLiteralNode(FieldType.UNTYPED,
                    new SpnSymbol[]{NAME, AGE},
                    new SpnExpressionNode[]{
                            new SpnStringLiteralNode("Alice"),
                            new SpnLongLiteralNode(30)});
            assertEquals("full", executeWithFrame(desc,
                    new SpnMatchNode(fullDict, fullBranch, nameBranch, fallback)));

            // Has only name → "name only"
            var nameDict = new SpnDictionaryLiteralNode(FieldType.UNTYPED,
                    new SpnSymbol[]{NAME},
                    new SpnExpressionNode[]{new SpnStringLiteralNode("Bob")});
            var nameBranch2 = new SpnMatchBranchNode(
                    new MatchPattern.DictionaryKeys(new SpnSymbol[]{NAME}),
                    new int[]{nSlot},
                    new SpnStringLiteralNode("name only"));
            var fallback2 = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("other"));
            assertEquals("name only", executeWithFrame(desc,
                    new SpnMatchNode(nameDict, nameBranch2, fallback2)));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // FIELDTYPE
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class DictFieldType {

        @Test
        void ofDictAcceptsMatching() {
            var ft = FieldType.ofDictionary(FieldType.STRING);
            assertTrue(ft.accepts(SpnDictionaryValue.of(FieldType.STRING, NAME, "Alice")));
            assertFalse(ft.accepts(SpnDictionaryValue.of(FieldType.LONG, NAME, 42L)));
            assertFalse(ft.accepts(42L));
        }

        @Test
        void untypedDictAcceptsAny() {
            var ft = FieldType.ofDictionary(FieldType.UNTYPED);
            assertTrue(ft.accepts(SpnDictionaryValue.of(FieldType.STRING, NAME, "Alice")));
            assertTrue(ft.accepts(SpnDictionaryValue.of(FieldType.LONG, NAME, 42L)));
        }

        @Test
        void describe() {
            assertEquals("Dict<String>", FieldType.ofDictionary(FieldType.STRING).describe());
            assertEquals("Dict", FieldType.ofDictionary(FieldType.UNTYPED).describe());
        }

        @Test
        void asStructField() {
            var desc = SpnStructDescriptor.builder("Config")
                    .field("settings", FieldType.ofDictionary(FieldType.STRING))
                    .build();

            var dict = new SpnDictionaryLiteralNode(FieldType.STRING,
                    new SpnSymbol[]{NAME},
                    new SpnExpressionNode[]{new SpnStringLiteralNode("value")});
            var node = new SpnStructConstructNode(desc, dict);
            var result = (SpnStructValue) execute(node);
            assertInstanceOf(SpnDictionaryValue.class, result.get(0));
        }
    }
}
