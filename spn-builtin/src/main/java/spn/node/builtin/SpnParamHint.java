package spn.node.builtin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Provides hints to the registry generator about a specific parameter
 * that can't be inferred from the Java code alone.
 *
 * <pre>
 *   // Tells the generator that "function" is a callable, not a value param
 *   @SpnParamHint(name = "function", function = true)
 *
 *   // Overrides the inferred type for a parameter
 *   @SpnParamHint(name = "array", type = "Array")
 * </pre>
 */
@Target(ElementType.TYPE)
@Repeatable(SpnParamHints.class)
@Retention(RetentionPolicy.RUNTIME)
public @interface SpnParamHint {

    /** The parameter name — must match a @NodeChild name or constructor parameter. */
    String name();

    /**
     * Override the inferred type. Use SPN type names:
     * "Long", "Double", "Boolean", "String", "Symbol", "Array", "Set", "Dict".
     * Leave empty to use inference.
     */
    String type() default "";

    /** True if this parameter is a function (CallTarget), not a value. */
    boolean function() default false;
}
