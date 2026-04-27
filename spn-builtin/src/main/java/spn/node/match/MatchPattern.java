package spn.node.match;

import spn.type.FieldType;
import spn.type.SpnArrayValue;
import spn.type.SpnDictionaryValue;
import spn.type.SpnProductValue;
import spn.type.SpnSetValue;
import spn.type.SpnSymbol;
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
     * Matches a tuple/array where each position is checked against a sub-pattern.
     * Supports full nesting: literals, wildcards, captures, struct patterns, etc.
     *
     * <pre>
     *   // match (n, d) | (0, _) -> ...
     *   new TupleElements(new MatchPattern[]{new Literal(0L), new Wildcard()}, 2)
     *
     *   // match pair | (Rational(0, _), Rational(0, _)) -> ...
     *   new TupleElements(new MatchPattern[]{
     *       new StructDestructure(rationalDesc, new MatchPattern[]{new Literal(0L), new Wildcard()}),
     *       new StructDestructure(rationalDesc, new MatchPattern[]{new Literal(0L), new Wildcard()})
     *   }, 2)
     * </pre>
     */
    record TupleElements(MatchPattern[] elements, int arity) implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            Object[] fields = extractFields(value);
            if (fields == null || fields.length != arity) return false;
            for (int i = 0; i < arity; i++) {
                if (!elements[i].matches(fields[i])) return false;
            }
            return true;
        }

        static Object[] extractFields(Object value) {
            if (value instanceof SpnTupleValue tv) return tv.getElements();
            if (value instanceof SpnArrayValue arr) return arr.getElements();
            if (value instanceof SpnStructValue sv) return sv.getFields();
            if (value instanceof SpnProductValue pv) return pv.getComponents();
            return null;
        }

        @Override
        public String describe() {
            var sb = new StringBuilder("(");
            for (int i = 0; i < arity; i++) {
                if (i > 0) sb.append(", ");
                sb.append(elements[i].describe());
            }
            return sb.append(")").toString();
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

    // ── Set patterns ────────────────────────────────────────────────────────

    /**
     * Matches an empty set.
     */
    record EmptySet() implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            return value instanceof SpnSetValue sv && sv.isEmpty();
        }

        @Override
        public String describe() {
            return "{}";
        }
    }

    /**
     * Matches a set that contains ALL of the specified required elements.
     * This is a membership check, not positional destructuring.
     *
     * <pre>
     *   // match s { {contains :red, :blue}  -> "has both" }
     *   new MatchPattern.SetContaining(new Object[]{red, blue})
     * </pre>
     *
     * When used with bindings, slot 0 gets the entire set (for further operations).
     */
    record SetContaining(Object[] requiredElements) implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            if (!(value instanceof SpnSetValue sv)) return false;
            for (Object required : requiredElements) {
                if (!sv.contains(required)) return false;
            }
            return true;
        }

        @Override
        public String describe() {
            var sb = new StringBuilder("{contains ");
            for (int i = 0; i < requiredElements.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(requiredElements[i]);
            }
            return sb.append("}").toString();
        }
    }

    // ── Dictionary patterns ─────────────────────────────────────────────────

    /**
     * Matches an empty dictionary.
     */
    record EmptyDictionary() implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            return value instanceof SpnDictionaryValue dv && dv.isEmpty();
        }

        @Override
        public String describe() {
            return "{:}";
        }
    }

    /**
     * Matches a dictionary that contains ALL of the specified keys.
     * Binds the values of those keys to frame slots (in key order).
     *
     * <pre>
     *   // match d { {:name n, :age a} -> ... }
     *   new MatchPattern.DictionaryKeys(new SpnSymbol[]{name, age})
     *   // bindingSlots[0] = slot for value of :name
     *   // bindingSlots[1] = slot for value of :age
     * </pre>
     *
     * The dictionary may contain additional keys beyond the required ones --
     * this is a "has at least these keys" check, not an exact match.
     */
    record DictionaryKeys(SpnSymbol[] requiredKeys) implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            if (!(value instanceof SpnDictionaryValue dv)) return false;
            for (SpnSymbol key : requiredKeys) {
                if (!dv.containsKey(key)) return false;
            }
            return true;
        }

        @Override
        public String describe() {
            var sb = new StringBuilder("{");
            for (int i = 0; i < requiredKeys.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(requiredKeys[i]);
            }
            return sb.append("}").toString();
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

    // ── Nested / compositional patterns ────────────────────────────────────

    /**
     * Matches any value and binds it to a frame slot. Used inside composite
     * patterns (TupleElements, StructDestructure) for variable capture.
     *
     * <pre>
     *   // match pair | (x, y) -> x + y
     *   // x and y are each a Capture with their own frame slot
     * </pre>
     */
    record Capture(int slot) implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            return true;
        }

        @Override
        public String describe() {
            return "<bind:" + slot + ">";
        }
    }

    /**
     * Matches a struct by descriptor and recursively checks each field against
     * a sub-pattern. Enables nested deconstruction like {@code Rational(0, _)}.
     *
     * <pre>
     *   // match r | Rational(0, _) -> ...
     *   new StructDestructure(rationalDesc, new MatchPattern[]{
     *       new Literal(0L), new Wildcard()
     *   })
     * </pre>
     */
    record StructDestructure(SpnStructDescriptor descriptor,
                             MatchPattern[] fieldPatterns) implements MatchPattern {
        @Override
        public boolean matches(Object value) {
            if (!(value instanceof SpnStructValue sv)) return false;
            if (sv.getDescriptor() != descriptor) return false;
            Object[] fields = sv.getFields();
            if (fields.length < fieldPatterns.length) return false;
            for (int i = 0; i < fieldPatterns.length; i++) {
                if (!fieldPatterns[i].matches(fields[i])) return false;
            }
            return true;
        }

        @Override
        public String describe() {
            var sb = new StringBuilder(descriptor.getName()).append("(");
            for (int i = 0; i < fieldPatterns.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(fieldPatterns[i].describe());
            }
            return sb.append(")").toString();
        }
    }
}
