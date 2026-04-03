package spn.node.match;

import spn.type.FieldType;
import spn.type.SpnArrayValue;
import spn.type.SpnProductValue;
import spn.type.SpnStructDescriptor;
import spn.type.SpnStructValue;
import spn.type.SpnTupleDescriptor;
import spn.type.SpnTupleValue;
import spn.type.SpnTypeDescriptor;

import java.util.Objects;

/**
 * A pattern for use in match expressions.
 *
 * Patterns test whether a runtime value has a particular shape. They do NOT
 * perform variable binding -- that's handled by SpnMatchBranchNode, which
 * knows the frame slot layout.
 *
 * Separating "does it match?" from "bind variables" keeps patterns as pure
 * data objects suitable for static analysis (exhaustiveness checking, etc.).
 *
 * Pattern variants:
 *
 *   Struct(descriptor)   -- matches SpnStructValue with that exact descriptor (==)
 *   Product(type)        -- matches SpnProductValue with that exact type (==)
 *   Tuple(descriptor)    -- matches SpnTupleValue structurally (arity + element types)
 *   OfType(fieldType)    -- matches any value accepted by the FieldType (primitives, constrained types, etc.)
 *   Literal(value)       -- matches any value that .equals() the expected value
 *   Wildcard             -- matches anything (the "_ ->" or "else ->" case)
 *
 * Examples:
 * <pre>
 *   // match shape {
 *   //   Circle(r)          -> pi * r * r
 *   //   Rectangle(w, h)    -> w * h
 *   //   _                  -> 0
 *   // }
 *   new MatchPattern.Struct(circleDesc)
 *   new MatchPattern.Struct(rectangleDesc)
 *   new MatchPattern.Wildcard()
 * </pre>
 */
public sealed interface MatchPattern {

    /** Returns true if the value matches this pattern's shape. */
    boolean matches(Object value);

    /** Human-readable description for error messages. */
    String describe();

    /**
     * Matches a SpnStructValue whose descriptor is exactly the given one.
     * Uses reference equality (==) -- two descriptors with the same name
     * are still distinct types.
     */
    record Struct(SpnStructDescriptor descriptor) implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            return value instanceof SpnStructValue sv && sv.getDescriptor() == descriptor;
        }

        @Override
        public String describe() {
            return descriptor.getName();
        }
    }

    /**
     * Matches a SpnProductValue whose type descriptor is exactly the given one.
     * Enables pattern matching on product types (Complex, Vec2, etc.).
     */
    record Product(SpnTypeDescriptor typeDescriptor) implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            return value instanceof SpnProductValue pv && pv.getType() == typeDescriptor;
        }

        @Override
        public String describe() {
            return typeDescriptor.getName();
        }
    }

    /**
     * Matches a SpnTupleValue that structurally matches the given descriptor.
     * Uses structural typing: checks arity and that each element satisfies its FieldType.
     */
    record Tuple(SpnTupleDescriptor descriptor) implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            return value instanceof SpnTupleValue tv && descriptor.structurallyMatches(tv);
        }

        @Override
        public String describe() {
            return descriptor.describe();
        }
    }

    /**
     * Matches any value accepted by the given FieldType.
     *
     * This is the general-purpose type pattern that covers primitives, constrained
     * types, and any other FieldType variant. It reuses the FieldType.accepts() logic
     * so there's no duplication between type checking and pattern matching.
     *
     * <pre>
     *   MatchPattern.OfType(FieldType.LONG)                        // Long values
     *   MatchPattern.OfType(FieldType.DOUBLE)                      // Double values
     *   MatchPattern.OfType(FieldType.ofConstrainedType(natural))  // Natural values
     *   MatchPattern.OfType(FieldType.ofStruct(circleDesc))        // Circle structs
     * </pre>
     */
    record OfType(FieldType fieldType) implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            return fieldType.accepts(value);
        }

        @Override
        public String describe() {
            return fieldType.describe();
        }
    }

    // ── String patterns ────────────────────────────────────────────────────

    /**
     * Matches a string that starts with the given prefix.
     *
     * When used with bindings, slot 0 gets the remainder (the part after the prefix).
     * <pre>
     *   // match s { "http://"(rest) -> rest }
     *   new MatchPattern.StringPrefix("http://")
     * </pre>
     */
    record StringPrefix(String prefix) implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            return value instanceof String s && s.startsWith(prefix);
        }

        /** Extracts the remainder after the prefix. */
        public String remainder(String value) {
            return value.substring(prefix.length());
        }

        @Override
        public String describe() {
            return "\"" + prefix + "\"..";
        }
    }

    /**
     * Matches a string that ends with the given suffix.
     *
     * When used with bindings, slot 0 gets the prefix (the part before the suffix).
     */
    record StringSuffix(String suffix) implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            return value instanceof String s && s.endsWith(suffix);
        }

        /** Extracts the prefix before the suffix. */
        public String prefix(String value) {
            return value.substring(0, value.length() - suffix.length());
        }

        @Override
        public String describe() {
            return "..\"" + suffix + "\"";
        }
    }

    /**
     * Matches a string against a regular expression (full match).
     *
     * When used with bindings, slot 0 gets the full matched string.
     * If the regex has capture groups, slots 1..N get the group values.
     */
    record StringRegex(String regex) implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            return value instanceof String s && s.matches(regex);
        }

        /** Extracts capture groups. Index 0 is the full match, 1..N are groups. */
        public String[] groups(String value) {
            var matcher = java.util.regex.Pattern.compile(regex).matcher(value);
            if (!matcher.matches()) return new String[0];
            var result = new String[matcher.groupCount() + 1];
            for (int i = 0; i <= matcher.groupCount(); i++) {
                result[i] = matcher.group(i);
            }
            return result;
        }

        @Override
        public String describe() {
            return "/" + regex + "/";
        }
    }

    // ── Array patterns ──────────────────────────────────────────────────────

    /**
     * Matches an empty array.
     * <pre>
     *   // match arr { [] -> "empty", _ -> "non-empty" }
     * </pre>
     */
    record EmptyArray() implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            return value instanceof SpnArrayValue arr && arr.isEmpty();
        }

        @Override
        public String describe() {
            return "[]";
        }
    }

    /**
     * Matches a non-empty array, decomposing it into head and tail.
     *
     * When used with bindings:
     *   slot 0 = head (first element)
     *   slot 1 = tail (new SpnArrayValue with remaining elements)
     *
     * <pre>
     *   // match arr { [h | t] -> ... }
     *   new MatchPattern.ArrayHeadTail()
     * </pre>
     */
    record ArrayHeadTail() implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            return value instanceof SpnArrayValue arr && !arr.isEmpty();
        }

        @Override
        public String describe() {
            return "[h | t]";
        }
    }

    /**
     * Matches an array of exactly the specified length, binding each element.
     *
     * Binding slots correspond to each element position (slots 0..N-1).
     *
     * <pre>
     *   // match arr { [a, b, c] -> ... }  (exactly 3 elements)
     *   new MatchPattern.ArrayExactLength(3)
     * </pre>
     */
    record ArrayExactLength(int length) implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            return value instanceof SpnArrayValue arr && arr.length() == length;
        }

        @Override
        public String describe() {
            return "[" + "_, ".repeat(Math.max(0, length - 1)) + "_]";
        }
    }

    // ── Value patterns ──────────────────────────────────────────────────────

    /**
     * Matches a value that equals the expected literal.
     * Uses Objects.equals for comparison, so works with boxed primitives and strings.
     */
    record Literal(Object expected) implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            return Objects.equals(expected, value);
        }

        @Override
        public String describe() {
            return String.valueOf(expected);
        }
    }

    /**
     * Matches any value whatsoever. The universal fallback pattern.
     * Typically the last branch in a match expression.
     */
    record Wildcard() implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            return true;
        }

        @Override
        public String describe() {
            return "_";
        }
    }
}
