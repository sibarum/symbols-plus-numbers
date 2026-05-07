package spn.node.expr;

import spn.language.SpnTypeName;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * The addition operator -- THE showcase of Truffle's type specialization.
 *
 * KEY TRUFFLE CONCEPT: @NodeChild
 * Declares that this node has child nodes "left" and "right". The Truffle DSL
 * processor generates:
 *   - Fields to hold the child nodes (@Child annotated)
 *   - A factory method: SpnAddNodeGen.create(leftNode, rightNode)
 *   - Execute dispatch: before calling any @Specialization method, the generated code
 *     executes the child nodes to get their values, then dispatches to the right
 *     specialization based on the runtime types of those values.
 *
 * KEY TRUFFLE CONCEPT: @Specialization
 *
 * Each specialization handles one shape of operands. The DSL picks the best fit
 * at the call site (more-specific wins; @ImplicitCast lets long widen to double
 * for the double specialization when the caller passes a long alongside a double).
 *
 *   1. addLongs: Both operands long. Uses Math.addExact and surfaces overflow as
 *      a located SpnException. We deliberately do NOT widen long→double on
 *      overflow — silent widening breaks bit-exactness for rational/exact-arithmetic
 *      types and hides the actual point of failure. If you want floating-point
 *      semantics, write doubles.
 *
 *   2. addDoubles: At least one operand is a double. Standard IEEE-754.
 *
 *   3. typeError: @Fallback catches any type combination not handled above.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "+")
public abstract class SpnAddNode extends SpnExpressionNode {

    /**
     * Long addition with overflow detection.
     * If both children produce longs (the common case for integer arithmetic),
     * this is the only specialization that exists in compiled code.
     */
    @Specialization
    protected long addLongs(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw new SpnException("long overflow: " + left + " + " + right, this);
        }
    }

    /**
     * Double addition. Activated when at least one operand is a double; the
     * @ImplicitCast in SpnTypes widens the other long operand. Long+long
     * never lands here — overflow on Math.addExact above is reported, not
     * silently widened, so bit-exact arithmetic stays bit-exact.
     */
    @Specialization
    protected double addDoubles(double left, double right) {
        return left + right;
    }

    /**
     * Type error fallback. @Fallback matches any combination of types not covered
     * by the specializations above.
     *
     * String concatenation is handled separately by SpnStringConcatNode (the ++ operator)
     * to keep string handling in a single place -- future work will allow configurable
     * string strategies (StringBuilder, StringBuffer, LinkedList, etc.).
     */
    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("Type error: +(" + SpnTypeName.of(left)
                + ", " + SpnTypeName.of(right) + ") is not defined", this);
    }
}
