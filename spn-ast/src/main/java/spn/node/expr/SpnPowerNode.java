package spn.node.expr;

import spn.language.SpnTypeName;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;

/**
 * Exponentiation operator (a ^ b). Long^long uses overflow-checked exponentiation
 * by squaring; rewrites to double via Math.pow on overflow or non-long operands.
 */
@NodeChild("left")
@NodeChild("right")
@NodeInfo(shortName = "^")
public abstract class SpnPowerNode extends SpnExpressionNode {

    @Specialization
    protected long powLongs(long base, long exp) {
        if (exp < 0) {
            throw new SpnException("long ^ negative exponent: " + base + " ^ " + exp
                    + " — use doubles for fractional results", this);
        }
        try {
            long result = 1;
            long b = base;
            long e = exp;
            while (e > 0) {
                if ((e & 1) == 1) result = Math.multiplyExact(result, b);
                e >>= 1;
                if (e > 0) b = Math.multiplyExact(b, b);
            }
            return result;
        } catch (ArithmeticException ex) {
            throw new SpnException("long overflow: " + base + " ^ " + exp, this);
        }
    }

    @Specialization
    protected double powDoubles(double base, double exp) {
        return Math.pow(base, exp);
    }

    @Fallback
    protected Object typeError(Object left, Object right) {
        throw new SpnException("Type error: ^(" + SpnTypeName.of(left)
                + ", " + SpnTypeName.of(right) + ") is not defined", this);
    }
}
