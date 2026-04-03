package spn.node.lambda;

import com.oracle.truffle.api.frame.VirtualFrame;
import spn.node.SpnExpressionNode;

/**
 * Links a producer's yield to a consumer's lambda body.
 *
 * When a SpnStreamBlockNode executes, it creates a YieldContext that holds:
 *   - The lambda body (the code block to run for each yielded value)
 *   - The caller's frame (where the lambda reads and writes variables)
 *   - The parameter slot (where yielded values are bound)
 *
 * The producer receives this context (implicitly, via a frame slot). When
 * SpnYieldNode executes inside the producer, it calls context.yield(value),
 * which binds the value into the caller's frame and runs the lambda body.
 *
 * This is a callback-based design: yield is a function call back into the
 * caller's scope, not a coroutine suspension. This keeps the Truffle
 * implementation simple and JIT-friendly.
 *
 * The context is NOT a first-class value -- it exists only for the duration
 * of the stream block and cannot escape. This enforces the constraint that
 * lambda-scoped blocks cannot be passed by reference.
 */
public final class SpnYieldContext {

    private final SpnExpressionNode lambdaBody;
    private final VirtualFrame callerFrame;
    private final int paramSlot;

    public SpnYieldContext(SpnExpressionNode lambdaBody, VirtualFrame callerFrame,
                           int paramSlot) {
        this.lambdaBody = lambdaBody;
        this.callerFrame = callerFrame;
        this.paramSlot = paramSlot;
    }

    /**
     * Called by SpnYieldNode inside the producer. Binds the yielded value
     * into the caller's frame at the lambda's parameter slot, then executes
     * the lambda body in the caller's scope.
     *
     * @param value the yielded value
     * @return the result of the lambda body execution
     */
    public Object yield(Object value) {
        callerFrame.setObject(paramSlot, value);
        return lambdaBody.executeGeneric(callerFrame);
    }

    public VirtualFrame getCallerFrame() {
        return callerFrame;
    }
}
