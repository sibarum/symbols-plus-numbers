package spn.type;

import com.oracle.truffle.api.CallTarget;

/**
 * A {@link CallTarget} wrapper that prepends a bound {@code this} reference
 * to every invocation. Used to implement {@code do(params) { body }}
 * closures over a {@link SpnStatefulInstance}: the underlying target's
 * first parameter is the instance; the user's call supplies only the
 * remaining args.
 *
 * <p>Implements {@link CallTarget} so existing call sites (Truffle's
 * IndirectCallNode, Java-side button handlers, etc.) dispatch uniformly.
 */
public final class SpnBoundClosure implements CallTarget {

    private final CallTarget target;
    private final Object boundThis;

    public SpnBoundClosure(CallTarget target, Object boundThis) {
        this.target = target;
        this.boundThis = boundThis;
    }

    public CallTarget underlyingTarget() { return target; }

    public Object boundThis() { return boundThis; }

    @Override
    public Object call(Object... arguments) {
        Object[] combined = new Object[arguments.length + 1];
        combined[0] = boundThis;
        System.arraycopy(arguments, 0, combined, 1, arguments.length);
        return target.call(combined);
    }
}
