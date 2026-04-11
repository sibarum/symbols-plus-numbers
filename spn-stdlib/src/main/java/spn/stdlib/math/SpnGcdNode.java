package spn.stdlib.math;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import spn.language.SpnException;
import spn.node.SpnExpressionNode;
import spn.node.builtin.SpnBuiltin;

/**
 * Greatest common divisor (Euclidean algorithm).
 * gcd(12, 8) -> 4
 */
@SpnBuiltin(name = "gcd", module = "Math", params = {"a", "b"}, returns = "Long")
@NodeChild("a")
@NodeChild("b")
@NodeInfo(shortName = "gcd")
public abstract class SpnGcdNode extends SpnExpressionNode {

    @Specialization
    protected long gcd(long a, long b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a;
    }

    @Fallback
    protected Object typeError(Object a, Object b) {
        throw new SpnException("gcd expects two integers", this);
    }
}
