package spn.node.expr;

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
 * KEY TRUFFLE CONCEPT: @Specialization and the speculate-deoptimize cycle
 *
 * Truffle starts with the most optimistic assumption and widens only when necessary:
 *
 *   1. addLongs: Assumes both operands are long. Uses Math.addExact which throws
 *      ArithmeticException on overflow. The "rewriteOn" parameter tells Truffle:
 *      "if this throws ArithmeticException, don't retry this specialization --
 *      permanently rewrite the node to skip it."
 *
 *   2. addDoubles: Activated when operands are doubles, or when addLongs overflowed.
 *      The "replaces" parameter means: once we activate addDoubles, remove addLongs
 *      from consideration (since doubles subsume longs via our @ImplicitCast).
 *
 *   3. addStrings: String concatenation -- a completely separate type specialization.
 *
 *   4. typeError: The @Fallback catches any type combination not handled above.
 *
 * At steady state, if this node always sees long+long, only addLongs exists in the
 * compiled code. No type checks, no boxing, no polymorphic dispatch -- just a single
 * ADD instruction with an overflow check. THIS is why Truffle languages can match C.
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
    @Specialization(rewriteOn = ArithmeticException.class)
    protected long addLongs(long left, long right) {
        return Math.addExact(left, right);
    }

    /**
     * Double addition. Activated either because an operand is a double, or because
     * addLongs overflowed. The @ImplicitCast in SpnTypes means a long operand is
     * automatically widened to double here.
     */
    @Specialization(replaces = "addLongs")
    protected double addDoubles(double left, double right) {
        return left + right;
    }

    /**
     * String concatenation.
     */
    @Specialization
    protected String addStrings(String left, String right) {
        return left + right;
    }

    /**
     * Type error fallback. @Fallback matches any combination of types not covered
     * by the specializations above.
     */
    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("Cannot add " + left.getClass().getSimpleName()
                + " and " + right.getClass().getSimpleName(), this);
    }
}
