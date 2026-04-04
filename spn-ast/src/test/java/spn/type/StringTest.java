package spn.type;

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
import spn.node.type.SpnCheckConstraintNode;

import static org.junit.jupiter.api.Assertions.*;

class StringTest {

    private static Object execute(SpnExpressionNode node) {
        return new SpnRootNode(null, new FrameDescriptor(), node, "test")
                .getCallTarget().call();
    }

    private static Object executeWithFrame(FrameDescriptor desc, SpnExpressionNode node) {
        return new SpnRootNode(null, desc, node, "test")
                .getCallTarget().call();
    }

    // ════════════════════════════════════════════════════════════════════════
    // STRING CONSTRAINTS
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class Constraints {

        @Test
        void minLength() {
            var c = new Constraint.MinLength(3);
            assertTrue(c.check("abc"));
            assertTrue(c.check("abcdef"));
            assertFalse(c.check("ab"));
            assertFalse(c.check(""));
            assertFalse(c.check(42L)); // non-string
        }

        @Test
        void maxLength() {
            var c = new Constraint.MaxLength(5);
            assertTrue(c.check("abc"));
            assertTrue(c.check("abcde"));
            assertFalse(c.check("abcdef"));
            assertFalse(c.check(42L));
        }

        @Test
        void matchesPattern() {
            var c = new Constraint.MatchesPattern("[a-z]+");
            assertTrue(c.check("hello"));
            assertFalse(c.check("Hello")); // capital H
            assertFalse(c.check(""));
            assertFalse(c.check(42L));
        }

        @Test
        void identifierPattern() {
            var c = new Constraint.MatchesPattern("[a-zA-Z_][a-zA-Z0-9_]*");
            assertTrue(c.check("foo"));
            assertTrue(c.check("_bar"));
            assertTrue(c.check("baz_123"));
            assertFalse(c.check("123abc"));
            assertFalse(c.check(""));
        }

        @Test
        void charSetAscii() {
            var c = new Constraint.CharSetConstraint(Constraint.CharClass.ASCII);
            assertTrue(c.check("hello"));
            assertTrue(c.check("test 123!"));
            assertFalse(c.check("caf\u00e9")); // é is non-ASCII
        }

        @Test
        void charSetAlphanumeric() {
            var c = new Constraint.CharSetConstraint(Constraint.CharClass.ALPHANUMERIC);
            assertTrue(c.check("hello123"));
            assertFalse(c.check("hello world")); // space
            assertFalse(c.check("hello!"));
        }

        @Test
        void charSetDigit() {
            var c = new Constraint.CharSetConstraint(Constraint.CharClass.DIGIT);
            assertTrue(c.check("12345"));
            assertFalse(c.check("12a45"));
        }

        @Test
        void charSetHex() {
            var c = new Constraint.CharSetConstraint(Constraint.CharClass.HEX);
            assertTrue(c.check("deadBEEF"));
            assertTrue(c.check("0123456789abcdef"));
            assertFalse(c.check("xyz"));
        }

        @Test
        void constraintDescribe() {
            assertEquals("length >= 3", new Constraint.MinLength(3).describe());
            assertEquals("length <= 10", new Constraint.MaxLength(10).describe());
            assertEquals("matches /[a-z]+/", new Constraint.MatchesPattern("[a-z]+").describe());
            assertEquals("charset ALPHANUMERIC",
                    new Constraint.CharSetConstraint(Constraint.CharClass.ALPHANUMERIC).describe());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // CONSTRAINED STRING TYPES
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class ConstrainedStringTypes {

        // type Identifier = String where length >= 1, length <= 64, matches /[a-zA-Z_]\w*/
        static final SpnTypeDescriptor IDENTIFIER = SpnTypeDescriptor.builder("Identifier")
                .constraint(new Constraint.MinLength(1))
                .constraint(new Constraint.MaxLength(64))
                .constraint(new Constraint.MatchesPattern("[a-zA-Z_][a-zA-Z0-9_]*"))
                .build();

        @Test
        void validIdentifier() {
            var node = new SpnCheckConstraintNode(new SpnStringLiteralNode("foo_bar"), IDENTIFIER);
            var cv = (SpnConstrainedValue) execute(node);
            assertEquals("foo_bar", cv.getValue());
        }

        @Test
        void rejectsEmpty() {
            var node = new SpnCheckConstraintNode(new SpnStringLiteralNode(""), IDENTIFIER);
            var ex = assertThrows(SpnException.class, () -> execute(node));
            assertTrue(ex.getMessage().contains("length >= 1"));
        }

        @Test
        void rejectsInvalidChars() {
            var node = new SpnCheckConstraintNode(new SpnStringLiteralNode("123abc"), IDENTIFIER);
            assertThrows(SpnException.class, () -> execute(node));
        }

        @Test
        void rejectsTooLong() {
            var node = new SpnCheckConstraintNode(
                    new SpnStringLiteralNode("a".repeat(65)), IDENTIFIER);
            var ex = assertThrows(SpnException.class, () -> execute(node));
            assertTrue(ex.getMessage().contains("length <= 64"));
        }

        @Test
        void hexColorType() {
            // type HexColor = String where length == 6, charset HEX
            var hexColor = SpnTypeDescriptor.builder("HexColor")
                    .constraint(new Constraint.MinLength(6))
                    .constraint(new Constraint.MaxLength(6))
                    .constraint(new Constraint.CharSetConstraint(Constraint.CharClass.HEX))
                    .build();

            assertNull(hexColor.findViolation("ff00aa"));
            assertNotNull(hexColor.findViolation("ff00a")); // too short
            assertNotNull(hexColor.findViolation("ff00aax")); // too long
            assertNotNull(hexColor.findViolation("gghhii")); // non-hex
        }

        @Test
        void asStructField() {
            var named = SpnStructDescriptor.builder("Named")
                    .field("name", FieldType.ofConstrainedType(IDENTIFIER))
                    .build();

            // Valid: name is a constrained Identifier
            var validId = new SpnCheckConstraintNode(
                    new SpnStringLiteralNode("my_var"), IDENTIFIER);
            var node = new SpnStructConstructNode(named, validId);
            var result = (SpnStructValue) execute(node);
            assertInstanceOf(SpnConstrainedValue.class, result.get(0));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // STRING PATTERN MATCHING
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class StringPatternMatching {

        @Test
        void prefixMatch() {
            var fdBuilder = FrameDescriptor.newBuilder();
            int restSlot = fdBuilder.addSlot(FrameSlotKind.Object, "rest", null);
            var desc = fdBuilder.build();

            // match url { "http://"(rest) -> rest, _ -> "unknown" }
            var httpBranch = new SpnMatchBranchNode(
                    new MatchPattern.StringPrefix("http://"), new int[]{restSlot},
                    spn.node.local.SpnReadLocalVariableNodeGen.create(restSlot));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("unknown"));

            assertEquals("example.com", executeWithFrame(desc,
                    new SpnMatchNode(new SpnStringLiteralNode("http://example.com"),
                            httpBranch, fallback)));
        }

        @Test
        void prefixNoMatch() {
            var httpBranch = new SpnMatchBranchNode(
                    new MatchPattern.StringPrefix("http://"), new int[]{},
                    new SpnStringLiteralNode("http"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("other"));

            assertEquals("other", execute(
                    new SpnMatchNode(new SpnStringLiteralNode("ftp://example.com"),
                            httpBranch, fallback)));
        }

        @Test
        void suffixMatch() {
            var fdBuilder = FrameDescriptor.newBuilder();
            int nameSlot = fdBuilder.addSlot(FrameSlotKind.Object, "name", null);
            var desc = fdBuilder.build();

            // match file { (name)".txt" -> name }
            var txtBranch = new SpnMatchBranchNode(
                    new MatchPattern.StringSuffix(".txt"), new int[]{nameSlot},
                    spn.node.local.SpnReadLocalVariableNodeGen.create(nameSlot));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("no match"));

            assertEquals("readme", executeWithFrame(desc,
                    new SpnMatchNode(new SpnStringLiteralNode("readme.txt"),
                            txtBranch, fallback)));
        }

        @Test
        void suffixNoMatch() {
            var txtBranch = new SpnMatchBranchNode(
                    new MatchPattern.StringSuffix(".txt"), new int[]{},
                    new SpnStringLiteralNode("text"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("other"));

            assertEquals("other", execute(
                    new SpnMatchNode(new SpnStringLiteralNode("image.png"),
                            txtBranch, fallback)));
        }

        @Test
        void regexMatch() {
            var fdBuilder = FrameDescriptor.newBuilder();
            int fullSlot = fdBuilder.addSlot(FrameSlotKind.Object, "full", null);
            int areaSlot = fdBuilder.addSlot(FrameSlotKind.Object, "area", null);
            int numSlot = fdBuilder.addSlot(FrameSlotKind.Object, "num", null);
            var desc = fdBuilder.build();

            // match phone { /(\d{3})-(\d{4})/(full, area, num) -> area }
            var phoneBranch = new SpnMatchBranchNode(
                    new MatchPattern.StringRegex("(\\d{3})-(\\d{4})"),
                    new int[]{fullSlot, areaSlot, numSlot},
                    spn.node.local.SpnReadLocalVariableNodeGen.create(areaSlot));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("invalid"));

            assertEquals("555", executeWithFrame(desc,
                    new SpnMatchNode(new SpnStringLiteralNode("555-1234"),
                            phoneBranch, fallback)));
        }

        @Test
        void regexNoMatch() {
            var phoneBranch = new SpnMatchBranchNode(
                    new MatchPattern.StringRegex("\\d{3}-\\d{4}"), new int[]{},
                    new SpnStringLiteralNode("phone"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("not phone"));

            assertEquals("not phone", execute(
                    new SpnMatchNode(new SpnStringLiteralNode("hello"),
                            phoneBranch, fallback)));
        }

        @Test
        void multiplePrefixBranches() {
            // match url {
            //   "http://"(rest) -> "web: " + rest
            //   "ftp://"(rest)  -> "file: " + rest
            //   _               -> "unknown"
            // }
            var fdBuilder = FrameDescriptor.newBuilder();
            int restSlot = fdBuilder.addSlot(FrameSlotKind.Object, "rest", null);
            var desc = fdBuilder.build();

            var httpBranch = new SpnMatchBranchNode(
                    new MatchPattern.StringPrefix("http://"), new int[]{restSlot},
                    SpnStringConcatNodeGen.create(new SpnStringLiteralNode("web: "),
                            spn.node.local.SpnReadLocalVariableNodeGen.create(restSlot)));
            var ftpBranch = new SpnMatchBranchNode(
                    new MatchPattern.StringPrefix("ftp://"), new int[]{restSlot},
                    SpnStringConcatNodeGen.create(new SpnStringLiteralNode("file: "),
                            spn.node.local.SpnReadLocalVariableNodeGen.create(restSlot)));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("unknown"));

            assertEquals("web: example.com", executeWithFrame(desc,
                    new SpnMatchNode(new SpnStringLiteralNode("http://example.com"),
                            httpBranch, ftpBranch, fallback)));

            // Need fresh nodes for second execution
            var ftpBranch2 = new SpnMatchBranchNode(
                    new MatchPattern.StringPrefix("ftp://"), new int[]{restSlot},
                    SpnStringConcatNodeGen.create(new SpnStringLiteralNode("file: "),
                            spn.node.local.SpnReadLocalVariableNodeGen.create(restSlot)));
            var fallback2 = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("unknown"));
            assertEquals("file: data.csv", executeWithFrame(desc,
                    new SpnMatchNode(new SpnStringLiteralNode("ftp://data.csv"),
                            ftpBranch2, fallback2)));
        }

        @Test
        void stringPatternDoesNotMatchNonString() {
            var branch = new SpnMatchBranchNode(
                    new MatchPattern.StringPrefix("hello"), new int[]{},
                    new SpnStringLiteralNode("matched"));
            var fallback = new SpnMatchBranchNode(
                    new MatchPattern.Wildcard(), new int[]{},
                    new SpnStringLiteralNode("no match"));

            // Long is not a string
            assertEquals("no match", execute(
                    new SpnMatchNode(new SpnLongLiteralNode(42), branch, fallback)));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // PATTERN DESCRIBE
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    class PatternDescribe {

        @Test
        void prefixDescribe() {
            assertEquals("\"http://\"..", new MatchPattern.StringPrefix("http://").describe());
        }

        @Test
        void suffixDescribe() {
            assertEquals("..\"txt\"", new MatchPattern.StringSuffix("txt").describe());
        }

        @Test
        void regexDescribe() {
            assertEquals("/\\d+/", new MatchPattern.StringRegex("\\d+").describe());
        }
    }
}
