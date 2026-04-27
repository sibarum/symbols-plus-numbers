package spn.node.builtin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Truffle node as a built-in SPN standard library function.
 *
 * The registry generator reads this annotation (along with @NodeChild and
 * constructor signatures) to auto-generate function descriptors and wiring
 * code in SpnStdlibRegistry.
 *
 * <pre>
 *   @SpnBuiltin(name = "abs", module = "Math", params = {"value"})
 *   @NodeChild("value")
 *   public abstract class SpnAbsNode extends SpnExpressionNode { ... }
 * </pre>
 *
 * The {@code params} field lists parameter names matching the @NodeChild declarations.
 * This is required because @NodeChild uses CLASS retention and is invisible to
 * runtime reflection. Use {@code returns} to set the return type, and
 * {@link SpnParamHint} for parameter-level type overrides or function markers.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SpnBuiltin {

    /** The SPN function name (e.g., "abs", "map", "fold"). */
    String name();

    /** The stdlib module this function belongs to (e.g., "Math", "Array", "String"). */
    String module() default "";

    /** Whether this is a pure function (default true for stdlib). */
    boolean pure() default true;

    /**
     * Parameter names, matching @NodeChild declarations in order.
     * Required because Truffle's @NodeChild uses CLASS retention and can't be
     * read at runtime. Must list the same names as @NodeChild annotations.
     */
    String[] params() default {};

    /**
     * Override the inferred return type. Use SPN type names:
     * "Long", "Double", "Boolean", "String", "Symbol", "Array", "Set", "Dict", "Option".
     * Leave empty to let the generator infer from @Specialization return types.
     */
    String returns() default "";

    /**
     * If set, this builtin is also callable as a method on the named type —
     * i.e. {@code receiver.methodName(args)} where {@code receiver} is
     * implicitly passed as the first argument. Use SPN type names
     * ("Array", "Dict", "Set", "Option", etc.). Leave empty for flat functions
     * only. The first {@code @NodeChild} param MUST be the receiver.
     */
    String receiver() default "";

    /**
     * Method name on the receiver type. Defaults to {@link #name()} if empty,
     * which is right for normal cases. Set this when the flat function name
     * is disambiguated (e.g., {@code dictSize} flat → {@code size} method).
     */
    String method() default "";
}
