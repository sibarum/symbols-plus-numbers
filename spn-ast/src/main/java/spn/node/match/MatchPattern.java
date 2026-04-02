package spn.node.match;

import spn.type.SpnProductValue;
import spn.type.SpnStructDescriptor;
import spn.type.SpnStructValue;
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
