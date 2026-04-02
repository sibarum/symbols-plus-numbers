package spn.type;

import com.oracle.truffle.api.dsl.ImplicitCast;
import com.oracle.truffle.api.dsl.TypeSystem;

/**
 * Defines the type system for SPN.
 *
 * The @TypeSystem annotation tells Truffle which types SPN values can have at runtime.
 * The order matters: Truffle tries specializations in this order, so the most specific
 * (and most frequently expected) types should come first.
 *
 * Types listed here:
 *   long    - integer arithmetic (64-bit, no boxing in specialized paths)
 *   double  - floating-point arithmetic
 *   boolean - logical values
 *   String  - text values
 *
 * Anything else falls through to Object (the generic/boxed case).
 *
 * The @ImplicitCast from long to double means: anywhere Truffle expects a double,
 * it can silently accept a long. This avoids requiring explicit casts in arithmetic
 * like  3 + 1.5  -- the 3 (long) is implicitly widened to 3.0 (double).
 */
@TypeSystem({long.class, double.class, boolean.class, String.class})
public abstract class SpnTypes {

    @ImplicitCast
    public static double castLongToDouble(long value) {
        return value;
    }
}
