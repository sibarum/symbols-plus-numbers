package spn.type;

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
import spn.node.type.SpnCheckConstraintNode;
import spn.type.check.Diagnostic;
import spn.type.check.TotalityChecker;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SymbolTest {

    static final SpnSymbolTable TABLE = new SpnSymbolTable();

    private static Object execute(SpnExpressionNode node) {
        return new SpnRootNode(null, new FrameDescriptor(), node, "test")
                .getCallTarget().call();
    }

    private static Object executeWithFrame(FrameDescriptor desc, SpnExpressionNode node) {
        return new SpnRootNode(null, desc, node, "test")
                .getCallTarget().call();
    }

    // ════════════════════════════════════════════════════════════════════════
    // INTERNING AND IDENTITY
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class Interning {

        @Test
        void sameNameReturnsSameObject() {
            var table = new SpnSymbolTable();
            var a = table.intern("foo");
            var b = table.intern("foo");
            assertSame(a, b);
        }

        @Test
        void differentNamesReturnDifferentObjects() {
            var table = new SpnSymbolTable();
            var a = table.intern("foo");
            var b = table.intern("bar");
            assertNotSame(a, b);
            assertNotEquals(a.id(), b.id());
        }

        @Test
        void idsAreMonotonic() {
            var table = new SpnSymbolTable();
            var a = table.intern("first");
            var b = table.intern("second");
            var c = table.intern("third");
            assertEquals(0, a.id());
            assertEquals(1, b.id());
            assertEquals(2, c.id());
        }

        @Test
        void lookup() {
            var table = new SpnSymbolTable();
            assertNull(table.lookup("missing"));
            var sym = table.intern("found");
            assertSame(sym, table.lookup("found"));
        }

        @Test
        void internAll() {
            var table = new SpnSymbolTable();
            var syms = table.internAll("a", "b", "c");
            assertEquals(3, syms.length);
            assertEquals(3, table.size());
            assertSame(syms[0], table.intern("a"));
        }

        @Test
        void toStringFormat() {
            var table = new SpnSymbolTable();
            assertEquals(":red", table.intern("red").toString());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SYMBOL LITERAL NODE
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class LiteralNode {

        @Test
        void producesSymbol() {
            var sym = TABLE.intern("test");
            var node = new SpnSymbolLiteralNode(sym);
            assertSame(sym, execute(node));
        }

        @Test
        void identityPreserved() {
            var sym = TABLE.intern("identity");
            var node1 = new SpnSymbolLiteralNode(sym);
            var node2 = new SpnSymbolLiteralNode(sym);
            assertSame(execute(node1), execute(node2));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // CONSTRAINED SYMBOL TYPES
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class ConstrainedSymbols {

        final SpnSymbol red = TABLE.intern("red");
        final SpnSymbol green = TABLE.intern("green");
        final SpnSymbol blue = TABLE.intern("blue");
        final SpnSymbol yellow = TABLE.intern("yellow");

        final SpnTypeDescriptor COLOR = SpnTypeDescriptor.builder("Color")
                .constraint(Constraint.SymbolOneOf.of(red, green, blue))
                .build();

        @Test
        void acceptsValidSymbol() {
            var node = new SpnCheckConstraintNode(new SpnSymbolLiteralNode(red), COLOR);
            var result = (SpnConstrainedValue) execute(node);
            assertSame(red, result.getValue());
        }

        @Test
        void rejectsInvalidSymbol() {
            var node = new SpnCheckConstraintNode(new SpnSymbolLiteralNode(yellow), COLOR);
            var ex = assertThrows(SpnException.class, () -> execute(node));
            assertTrue(ex.getMessage().contains("oneOf"));
            assertTrue(ex.getMessage().contains("Color"));
        }

        @Test
        void rejectsNonSymbol() {
            var node = new SpnCheckConstraintNode(new SpnLongLiteralNode(42), COLOR);
            assertThrows(SpnException.class, () -> execute(node));
        }

        @Test
        void isSymbolConstraint() {
            var anySymbolType = SpnTypeDescriptor.builder("AnySymbol")
                    .constraint(new Constraint.IsSymbol())
                    .build();

            // Symbol passes
            assertNull(anySymbolType.findViolation(red));
            // Non-symbol fails
            assertNotNull(anySymbolType.findViolation(42L));
            assertNotNull(anySymbolType.findViolation("string"));
        }

        @Test
        void constraintDescribe() {
            var c = Constraint.SymbolOneOf.of(red, green, blue);
            String desc = c.describe();
            assertTrue(desc.contains(":red"));
            assertTrue(desc.contains(":green"));
            assertTrue(desc.contains(":blue"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SYMBOLS IN STRUCTS
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class SymbolsInStructs {

        @Test
        void symbolFieldInStruct() {
            var tagged = SpnStructDescriptor.builder("Tagged")
                    .field("tag", FieldType.SYMBOL)
                    .field("value")
                    .build();

            var sym = TABLE.intern("important");
            var node = new SpnStructConstructNode(tagged,
                    new SpnSymbolLiteralNode(sym),
                    new SpnLongLiteralNode(42));
            var result = (SpnStructValue) execute(node);
            assertSame(sym, result.get(0));
            assertEquals(42L, result.get(1));
        }

        @Test
        void symbolFieldRejectsNonSymbol() {
            var tagged = SpnStructDescriptor.builder("Tagged")
                    .field("tag", FieldType.SYMBOL)
                    .field("value")
                    .build();

            var node = new SpnStructConstructNode(tagged,
                    new SpnStringLiteralNode("not a symbol"),
                    new SpnLongLiteralNode(42));
            assertThrows(SpnException.class, () -> execute(node));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SYMBOL PATTERN MATCHING
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class SymbolPatternMatching {

        final SpnSymbol red = TABLE.intern("red");
        final SpnSymbol green = TABLE.intern("green");
        final SpnSymbol blue = TABLE.intern("blue");

        @Test
        void matchOnSpecificSymbol() {
            // match color { :red -> "stop", :green -> "go", :blue -> "sky" }
            var redBranch = new SpnMatchBranchNode(
                    new MatchPattern.Literal(red), new int[]{},
                    new SpnStringLiteralNode("stop"));
            var greenBranch = new SpnMatchBranchNode(
                    new MatchPattern.Literal(green), new int[]{},
                    new SpnStringLiteralNode("go"));
            var blueBranch = new SpnMatchBranchNode(
                    new MatchPattern.Literal(blue), new int[]{},
                    new SpnStringLiteralNode("sky"));

            assertEquals("stop", execute(new SpnMatchNode(new SpnSymbolLiteralNode(red),
                    redBranch, greenBranch, blueBranch)));
        }

        @Test
        void matchSymbolWithBinding() {
            var fdBuilder = FrameDescriptor.newBuilder();
            int sSlot = fdBuilder.addSlot(FrameSlotKind.Object, "s", null);
            var desc = fdBuilder.build();

            // match :green { s:Symbol -> s }
            var branch = new SpnMatchBranchNode(
                    new MatchPattern.OfType(FieldType.SYMBOL), new int[]{sSlot},
                    spn.node.local.SpnReadLocalVariableNodeGen.create(sSlot));

            var match = new SpnMatchNode(new SpnSymbolLiteralNode(green), branch);
            assertSame(green, executeWithFrame(desc, match));
        }

        @Test
        void symbolTypePatternDoesNotMatchString() {
            var branch = new SpnMatchBranchNode(
                    new MatchPattern.OfType(FieldType.SYMBOL), new int[]{},
                    new SpnStringLiteralNode("matched"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("no match"));

            // String is NOT a symbol
            assertEquals("no match", execute(new SpnMatchNode(
                    new SpnStringLiteralNode("hello"), branch, fallback)));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // TOTALITY CHECKER WITH SYMBOL SETS
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class SymbolTotality {

        final SpnSymbol red = TABLE.intern("red");
        final SpnSymbol green = TABLE.intern("green");
        final SpnSymbol blue = TABLE.intern("blue");
        final SpnSymbolSet COLOR = new SpnSymbolSet("Color", red, green, blue);

        private SpnMatchNode buildSymbolMatch(SpnSymbol... coveredSymbols) {
            var branches = new SpnMatchBranchNode[coveredSymbols.length];
            for (int i = 0; i < coveredSymbols.length; i++) {
                branches[i] = new SpnMatchBranchNode(
                        new MatchPattern.Literal(coveredSymbols[i]), new int[]{},
                        new SpnLongLiteralNode(i));
            }
            return new SpnMatchNode(new SpnLongLiteralNode(0), branches);
        }

        @Test
        void exhaustiveWithAllSymbols() {
            var match = buildSymbolMatch(red, green, blue);
            var diagnostics = TotalityChecker.check(match, List.of(), List.of(COLOR));
            assertFalse(TotalityChecker.hasErrors(diagnostics));
        }

        @Test
        void nonExhaustiveMissingSymbol() {
            var match = buildSymbolMatch(red, green);
            // Missing: blue
            var diagnostics = TotalityChecker.check(match, List.of(), List.of(COLOR));
            assertTrue(TotalityChecker.hasErrors(diagnostics));
            assertTrue(diagnostics.stream()
                    .anyMatch(d -> d.message().contains(":blue")));
        }

        @Test
        void exhaustiveWithWildcard() {
            var branches = new SpnMatchBranchNode[]{
                    new SpnMatchBranchNode(new MatchPattern.Literal(red), new int[]{},
                            new SpnLongLiteralNode(0)),
                    new SpnMatchBranchNode(new MatchPattern.Wildcard(), new int[]{},
                            new SpnLongLiteralNode(1))
            };
            var match = new SpnMatchNode(new SpnLongLiteralNode(0), branches);
            var diagnostics = TotalityChecker.check(match, List.of(), List.of(COLOR));
            assertFalse(TotalityChecker.hasErrors(diagnostics));
        }

        @Test
        void symbolSetToString() {
            assertEquals("Color = :red | :green | :blue", COLOR.toString());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // FIELDTYPE.SYMBOL
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class FieldTypeSymbol {

        @Test
        void acceptsSymbol() {
            assertTrue(FieldType.SYMBOL.accepts(TABLE.intern("anything")));
        }

        @Test
        void rejectsNonSymbol() {
            assertFalse(FieldType.SYMBOL.accepts(42L));
            assertFalse(FieldType.SYMBOL.accepts("string"));
            assertFalse(FieldType.SYMBOL.accepts(true));
        }

        @Test
        void describe() {
            assertEquals("Symbol", FieldType.SYMBOL.describe());
        }
    }
}
